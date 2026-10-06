package com.amazon.sample.assistant.chat;

import com.amazon.sample.assistant.chat.llm.ChatProviderErrors;
import com.amazon.sample.assistant.chat.llm.ChatProviderException;
import com.amazon.sample.assistant.chat.llm.ChatRateLimiter;
import com.amazon.sample.assistant.chat.llm.ReasoningPolicy.TurnOptions;
import com.amazon.sample.assistant.chat.llm.ThinkTagFilter;
import com.amazon.sample.assistant.config.ChatProperties;
import com.amazon.sample.assistant.config.RateLimitProperties;
import com.amazon.sample.assistant.config.ToolsProperties;
import com.amazon.sample.assistant.tools.TurnToolContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Ciclo de tool calling de un turno, controlado por el {@code assistant} y no
 * por Spring AI (D1 de {@code add-assistant-tools}):
 *
 * <pre>
 * vuelta n (n = 1..max-model-calls)
 *   ─► reservar lugar en el limitador (D8), como Mono.delay
 *   ─► stream del modelo con las tools (en la última vuelta: tool_choice = none)
 *   ─► reenviar los fragmentos de texto al canal del turno
 *   ─► al cerrar el stream: ¿trajo tool calls?
 *        no ─► fin del turno
 *        sí ─► ejecutarlos con ToolCallingManager (boundedElastic) y seguir
 * </pre>
 *
 * <p>El modelo se llama con {@code internalToolExecutionEnabled=false}: el
 * stream devuelve los tool calls completos sin ejecutarlos. Los tiempos límite
 * al primer fragmento se miden por vuelta desde que el limitador otorga el
 * lugar; el del turno lo controla {@link ChatTurnService}. Si una vuelta recibe
 * un 429 antes de emitir algo, pausa el limitador por el {@code Retry-After} y
 * la reintenta, hasta {@code max-429-retries} veces (D9).
 */
public class ToolCallingLoop {

  private static final Logger log = LoggerFactory.getLogger(ToolCallingLoop.class);

  /** Texto de la persona cuando la última vuelta no trajo texto (D1). */
  static final String NO_ANSWER_TEXT = "Mission aborted, Operative: I couldn't complete that "
      + "operation this time. Try asking me again.";

  private final ChatClient mainChatClient;
  private final ToolCallingManager toolCallingManager;
  private final List<ToolCallback> toolCallbacks;
  private final ChatRateLimiter limiter;
  private final ToolsProperties tools;
  private final RateLimitProperties rateLimit;
  private final ChatProperties.Chat chat;

  /**
   * @param mainChatClient cliente del modelo principal, con las tools registradas
   * @param toolCallingManager ejecuta los tool calls de cada vuelta
   * @param toolCallbacks las tools, para ejecutar los tool calls con el contexto del turno
   */
  public ToolCallingLoop(ChatClient mainChatClient, ToolCallingManager toolCallingManager,
      List<ToolCallback> toolCallbacks, ChatRateLimiter limiter, ToolsProperties tools,
      RateLimitProperties rateLimit, ChatProperties.Chat chat) {
    this.mainChatClient = mainChatClient;
    this.toolCallingManager = toolCallingManager;
    this.toolCallbacks = List.copyOf(toolCallbacks);
    this.limiter = limiter;
    this.tools = tools;
    this.rateLimit = rateLimit;
    this.chat = chat;
  }

  /** Estado de un turno dentro del ciclo. */
  private record Run(ChatTurn turn, TurnStats stats, TurnOptions options,
      TurnToolContext toolTurn, long deadline, StringBuilder answer) {
  }

  /** Lo que devolvió una vuelta: su texto y sus tool calls. */
  private static final class Round {
    final StringBuilder text = new StringBuilder();
    final List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
    /** Si la vuelta ya emitió un fragmento o un tool call: después de eso no se reintenta. */
    volatile boolean emitted;
  }

  /**
   * Corre el ciclo del turno y devuelve el texto completo que vio el usuario.
   *
   * @param messages system prompt, historial y mensaje del usuario
   * @param deadline fin del turno (reloj del scheduler de Reactor, en ms)
   */
  Mono<String> run(ChatTurn turn, TurnStats stats, List<Message> messages, TurnOptions options,
      TurnToolContext toolTurn, long deadline) {
    Run run = new Run(turn, stats, options, toolTurn, deadline, new StringBuilder());
    return round(1, messages, run).then(Mono.fromSupplier(() -> run.answer().toString()));
  }

  private Mono<Void> round(int n, List<Message> messages, Run run) {
    boolean last = n >= tools.maxModelCalls();
    OpenAiChatOptions options = OpenAiChatOptions.fromOptions(run.options().options());
    options.setInternalToolExecutionEnabled(false);
    if (last) {
      // Mismas tools, pero sin poder pedirlas: la última vuelta tiene que ser texto.
      options.setToolChoice("none");
    }
    return attempt(messages, options, run, rateLimit.max429Retries())
        .flatMap(round -> {
          if (round.toolCalls.isEmpty() || last) {
            if (!round.toolCalls.isEmpty()) {
              log.warn("La última vuelta pidió {} tool calls con tool_choice none; se ignoran",
                  round.toolCalls.size());
            }
            if (round.text.isEmpty() && !run.turn().isCancelled()) {
              emitText(run, NO_ANSWER_TEXT);
            }
            return Mono.<Void>empty();
          }
          return execute(messages, round, run).flatMap(next -> round(n + 1, next, run));
        });
  }

  /**
   * Una vuelta con su lugar en el limitador, reintentada ante un 429 que llegó
   * antes de cualquier fragmento (D9).
   */
  private Mono<Round> attempt(List<Message> messages, OpenAiChatOptions options, Run run,
      int retriesLeft) {
    return Mono.defer(() -> {
      ChatRateLimiter.Reservation reservation = limiter.reserve(rateLimit.maxWait());
      if (!reservation.granted()) {
        log.warn("Sin lugar en el limitador de NVIDIA dentro de {} s (espera estimada {} s): el "
            + "turno termina sin llamar al modelo", rateLimit.maxWait().toSeconds(),
            reservation.delay().toSeconds());
        return Mono.error(new ChatProviderException(ChatProviderException.Reason.QUOTA,
            reservation.delay(), "Sin lugar en el limitador de solicitudes a NVIDIA", null));
      }
      run.stats().limiterWaitMillis += reservation.delay().toMillis();
      Round round = new Round();
      Mono<Void> call = Mono.defer(() -> stream(messages, options, run, round));
      Mono<Void> scheduled = reservation.delay().isZero() ? call
          : Mono.delay(reservation.delay()).then(call);
      return scheduled.thenReturn(round)
          .onErrorResume(error -> {
            ChatProviderException translated = ChatProviderErrors.translate(error);
            if (translated.reason() != ChatProviderException.Reason.QUOTA || round.emitted
                || retriesLeft <= 0 || run.turn().isCancelled()) {
              return Mono.error(error);
            }
            Duration pause = limiter.pause(translated);
            run.stats().retries429++;
            log.warn("NVIDIA respondió 429 a la vuelta del modelo principal: se pausan las "
                + "solicitudes por {} ms y se reintenta ({} reintentos restantes)",
                pause.toMillis(), retriesLeft - 1);
            return attempt(messages, options, run, retriesLeft - 1);
          });
    });
  }

  /** Stream de una vuelta: reenvía el texto y junta los tool calls. */
  private Mono<Void> stream(List<Message> messages, OpenAiChatOptions options, Run run,
      Round round) {
    run.turn().countProviderRequest();
    run.stats().modelCalls++;
    Flux<String> text = mainChatClient.prompt()
        .messages(messages)
        .options(options)
        .stream()
        .chatResponse()
        .doOnNext(response -> {
          run.stats().reasoningChars += reasoningLength(response);
          List<AssistantMessage.ToolCall> calls = toolCalls(response);
          if (!calls.isEmpty()) {
            round.emitted = true;
            round.toolCalls.addAll(calls);
          }
        })
        .map(ToolCallingLoop::text)
        .filter(fragment -> !fragment.isEmpty());
    if (chat.reasoning().stripThinkTags()) {
      text = ThinkTagFilter.apply(text);
    }
    Duration firstToken = run.options().reasoning()
        ? chat.timeouts().firstTokenReasoning() : chat.timeouts().firstToken();
    return text
        .timeout(Mono.delay(min(firstToken, remaining(run.deadline()))),
            fragment -> Mono.delay(remaining(run.deadline())))
        .doOnNext(fragment -> {
          round.emitted = true;
          round.text.append(fragment);
          emitText(run, fragment);
        })
        .then();
  }

  /**
   * Ejecuta los tool calls de la vuelta con {@link ToolCallingManager}, en
   * {@code boundedElastic} porque las tools hacen llamadas bloqueantes, y
   * devuelve el historial para la vuelta siguiente: los mensajes previos, el
   * {@link AssistantMessage} con los tool calls y las respuestas de las tools.
   */
  private Mono<List<Message>> execute(List<Message> messages, Round round, Run run) {
    return Mono.fromCallable(() -> {
      OpenAiChatOptions execution = OpenAiChatOptions.builder()
          .toolCallbacks(toolCallbacks)
          .toolContext(run.toolTurn().asToolContext())
          .internalToolExecutionEnabled(false)
          .build();
      AssistantMessage request = AssistantMessage.builder()
          .content(round.text.toString())
          .toolCalls(round.toolCalls)
          .build();
      return toolCallingManager.executeToolCalls(new Prompt(messages, execution),
          new ChatResponse(List.of(new Generation(request)))).conversationHistory();
    }).subscribeOn(Schedulers.boundedElastic());
  }

  private static void emitText(Run run, String fragment) {
    if (run.stats().firstFragmentMillis < 0) {
      run.stats().firstFragmentMillis = run.stats().elapsedMillis();
    }
    run.answer().append(fragment);
    run.turn().emit(ServerSentEvent.builder(Map.of("text", fragment)).build());
  }

  private static List<AssistantMessage.ToolCall> toolCalls(ChatResponse response) {
    if (response.getResult() == null || response.getResult().getOutput() == null
        || !response.getResult().getOutput().hasToolCalls()) {
      return List.of();
    }
    return response.getResult().getOutput().getToolCalls();
  }

  static String text(ChatResponse response) {
    if (response.getResult() == null || response.getResult().getOutput() == null) {
      return "";
    }
    String text = response.getResult().getOutput().getText();
    return text == null ? "" : text;
  }

  private static long reasoningLength(ChatResponse response) {
    if (response.getResult() == null || response.getResult().getOutput() == null) {
      return 0;
    }
    Object reasoningContent = response.getResult().getOutput().getMetadata()
        .get("reasoningContent");
    return reasoningContent instanceof String s ? s.length() : 0;
  }

  /** Reloj del scheduler de Reactor, para que los tests con tiempo virtual lo controlen. */
  static long now() {
    return Schedulers.parallel().now(TimeUnit.MILLISECONDS);
  }

  static Duration remaining(long deadline) {
    return Duration.ofMillis(Math.max(0, deadline - now()));
  }

  private static Duration min(Duration a, Duration b) {
    return a.compareTo(b) <= 0 ? a : b;
  }
}
