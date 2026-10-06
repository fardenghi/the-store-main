package com.amazon.sample.assistant.chat;

import com.amazon.sample.assistant.chat.llm.ChatProviderErrors;
import com.amazon.sample.assistant.chat.llm.ChatProviderException;
import com.amazon.sample.assistant.chat.llm.ChatRateLimiter;
import com.amazon.sample.assistant.chat.llm.ReasoningPolicy.TurnOptions;
import com.amazon.sample.assistant.chat.llm.ThinkTagFilter;
import com.amazon.sample.assistant.chat.session.ToolRound;
import com.amazon.sample.assistant.config.ChatProperties;
import com.amazon.sample.assistant.config.RateLimitProperties;
import com.amazon.sample.assistant.config.ToolsProperties;
import com.amazon.sample.assistant.tools.CompactToolResult;
import com.amazon.sample.assistant.tools.TurnToolContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
 *
 * <p>Correcciones posteriores (confirmación fiel de las acciones):
 * <ul>
 *   <li>El texto de cada vuelta pasa por un {@link CartClaimFilter}: una oración
 *       que afirma un agregado al carrito sin un {@code addToCart} correcto en el
 *       turno no se emite. Si la vuelta que cierra el turno tuvo una, o si
 *       termina anunciando una acción que no hizo ({@link ActionAnnouncement}),
 *       se agrega una vuelta correctiva con un aviso al modelo (hasta
 *       {@link #MAX_CORRECTIONS} por turno, si queda presupuesto y la vuelta
 *       siguiente no es la última). Si el usuario pidió agregar al carrito y el
 *       modelo no le estaba preguntando nada, esa vuelta obliga a pedir una tool:
 *       con {@code tool_choice: required} o, para los modelos que lo ignoran,
 *       con {@link #TOOL_CALL_NOTE} en el aviso
 *       ({@code retail.assistant.tools.corrective-tool-choice}). En los dos casos
 *       el texto de esa vuelta sigue pasando por el {@link CartClaimFilter}.
 *       Si el turno igual termina con una afirmación
 *       descartada y sin agregado, se emite {@link #NOT_ADDED_TEXT}.</li>
 *   <li>Si una vuelta no pidió tools pero las escribió como texto
 *       ({@link TextualToolCalls}), se ejecutan como tool calls.</li>
 *   <li>Las vueltas con tool calls se devuelven con el texto, para guardarlas en
 *       la memoria de la sesión con un resultado compacto de cada tool.</li>
 * </ul>
 */
public class ToolCallingLoop {

  private static final Logger log = LoggerFactory.getLogger(ToolCallingLoop.class);

  /** Texto de la persona cuando la última vuelta no trajo texto (D1). */
  static final String NO_ANSWER_TEXT = "Mission aborted, Operative: I couldn't complete that "
      + "operation this time. Try asking me again.";

  /** Texto de la persona cuando el modelo afirmó un agregado que no ocurrió (salvaguarda). */
  static final String NOT_ADDED_TEXT = "Heads-up, Operative: nothing was added to your cart in "
      + "this turn. If you want something added, tell me which product and how many.";

  /** Aviso al modelo en la vuelta correctiva, como un mensaje del sistema de la tienda. */
  static final String CORRECTION_NOTE = "[Store system note, not written by the customer] Your "
      + "last reply said that products were added to the cart, but you did not call addToCart "
      + "in this turn, so nothing was added and that sentence was not shown to the customer. If "
      + "the customer explicitly asked to add a product and it is clear which one, call "
      + "addToCart now with its id and quantity. Otherwise, do not say that anything was added. "
      + "Continue your reply without repeating what you already said.";

  /** Aviso al modelo cuando su respuesta terminó anunciando una acción que no hizo. */
  static final String ANNOUNCED_NOTE = "[Store system note, not written by the customer] Your "
      + "last reply announced an action (checking, searching or adding) but you did not call "
      + "any tool, so nothing was done. Call the tool now instead of announcing it. If no tool "
      + "is needed, answer with the information you already have. Do not repeat what you "
      + "already said.";

  /**
   * Agregado al aviso de la vuelta correctiva de un pedido de carrito cuando no
   * se manda {@code tool_choice: required} ({@code corrective-tool-choice: prompt}).
   */
  static final String TOOL_CALL_NOTE = "The customer asked to add a product to the cart: this "
      + "reply must be a tool call, not text. Call addToCart with the product id and quantity, "
      + "or searchProducts or getProductDetails first if you do not know the id.";

  /** Vueltas correctivas por turno (afirmación descartada o anuncio sin tool). */
  static final int MAX_CORRECTIONS = 2;

  /**
   * Un pedido de agregar al carrito ("add two of the first one to my cart",
   * "agregá la lámpara al carrito"). Solo con un pedido así la vuelta correctiva
   * va con {@code tool_choice: required}.
   */
  private static final Pattern CART_REQUEST = Pattern.compile(
      "\\b(add|put|agreg\\w*|añad\\w*|sum[aá]\\w*|met[eé]\\w*)\\b.*\\b(cart|basket|inventory"
          + "|carrito|canasta|cesta)\\b",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

  /** Respuesta del ciclo: el texto que vio el usuario y las vueltas con tool calls. */
  record Answer(String text, List<ToolRound> toolRounds) {
  }

  private final ChatClient mainChatClient;
  private final ToolCallingManager toolCallingManager;
  private final List<ToolCallback> toolCallbacks;
  private final Set<String> toolNames;
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
    this.toolNames = this.toolCallbacks.stream()
        .map(callback -> callback.getToolDefinition().name()).collect(Collectors.toSet());
    this.limiter = limiter;
    this.tools = tools;
    this.rateLimit = rateLimit;
    this.chat = chat;
  }

  /** Estado de un turno dentro del ciclo. */
  private static final class Run {
    final ChatTurn turn;
    final TurnStats stats;
    final TurnOptions options;
    final TurnToolContext toolTurn;
    final long deadline;
    /** Texto que vio el usuario en todo el turno. */
    final StringBuilder answer = new StringBuilder();
    /** Texto emitido desde la última vuelta con tool calls. */
    final StringBuilder pendingText = new StringBuilder();
    final List<ToolRound> toolRounds = new ArrayList<>();
    int falseClaims;
    /**
     * Correcciones del turno: vueltas correctivas ({@code claim}, {@code announce})
     * y tool calls escritos como texto que se ejecutaron ({@code textual}).
     */
    final List<String> corrections = new ArrayList<>();

    long correctiveRounds() {
      return corrections.stream().filter(reason -> !"textual".equals(reason)).count();
    }

    Run(ChatTurn turn, TurnStats stats, TurnOptions options, TurnToolContext toolTurn,
        long deadline) {
      this.turn = turn;
      this.stats = stats;
      this.options = options;
      this.toolTurn = toolTurn;
      this.deadline = deadline;
    }

    synchronized void append(String text) {
      answer.append(text);
      pendingText.append(text);
    }

    synchronized void recordToolRound(List<ToolRound.Call> calls) {
      toolRounds.add(new ToolRound(pendingText.toString(), calls));
      pendingText.setLength(0);
    }

    /** Si lo último que se emitió en el turno no termina en un espacio. */
    synchronized boolean endsWithText() {
      return !answer.isEmpty() && !Character.isWhitespace(answer.charAt(answer.length() - 1));
    }

    synchronized Answer result() {
      return new Answer(answer.toString(), List.copyOf(toolRounds));
    }
  }

  /** Lo que devolvió una vuelta: su texto y sus tool calls. */
  private static final class Round {
    /** Texto que mandó el modelo, incluidas las oraciones que el filtro descartó. */
    final StringBuilder text = new StringBuilder();
    /** Texto que se emitió al usuario. */
    final StringBuilder shown = new StringBuilder();
    final List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
    /** Si la vuelta ya emitió un fragmento o un tool call: después de eso no se reintenta. */
    volatile boolean emitted;
    /** Oraciones descartadas por afirmar un agregado sin {@code addToCart}. */
    volatile int falseClaims;
    /** Tool calls que el modelo escribió como texto ({@link TextualToolCalls}). */
    volatile List<AssistantMessage.ToolCall> textualCalls = List.of();
  }

  /**
   * Corre el ciclo del turno y devuelve el texto completo que vio el usuario,
   * con las vueltas que pidieron tools.
   *
   * @param messages system prompt, historial y mensaje del usuario
   * @param deadline fin del turno (reloj del scheduler de Reactor, en ms)
   */
  Mono<Answer> run(ChatTurn turn, TurnStats stats, List<Message> messages, TurnOptions options,
      TurnToolContext toolTurn, long deadline) {
    Run run = new Run(turn, stats, options, toolTurn, deadline);
    return round(1, messages, run, false).then(Mono.fromSupplier(run::result));
  }

  /**
   * El modelo le está preguntando o pidiendo una aclaración al usuario: en ese
   * caso la vuelta correctiva no se fuerza, para que pueda preguntar en lugar de
   * elegir un producto (spec "Pedido ambiguo").
   */
  private static final Pattern ASKS_USER = Pattern.compile(
      "\\?|¿|\\b(clarify|which one|which of|specify|confirm|do you mean|cuál|aclar\\w*)\\b",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

  /** Si el texto de la vuelta le pregunta algo al usuario. */
  static boolean asksUser(CharSequence text) {
    return ASKS_USER.matcher(text).find();
  }

  /** Si el mensaje del usuario pide agregar algo al carrito. */
  static boolean isCartRequest(String message) {
    return message != null && CART_REQUEST.matcher(message).find();
  }

  /**
   * @param requireTool vuelta correctiva de un pedido de carrito: el modelo ya quiso
   *     una tool y no la pidió, así que va con {@code tool_choice: required} (si
   *     {@code corrective-tool-choice} es {@code required}; si no, el aviso ya lo pide)
   */
  private Mono<Void> round(int n, List<Message> messages, Run run, boolean requireTool) {
    boolean last = n >= tools.maxModelCalls();
    OpenAiChatOptions options = OpenAiChatOptions.fromOptions(run.options.options());
    options.setInternalToolExecutionEnabled(false);
    if (last) {
      // Mismas tools, pero sin poder pedirlas: la última vuelta tiene que ser texto.
      options.setToolChoice("none");
    } else if (requireTool && requiredToolChoice()) {
      options.setToolChoice("required");
    }
    return attempt(messages, options, run, rateLimit.max429Retries())
        .flatMap(round -> {
          if (requireTool && requiredToolChoice() && !last && round.toolCalls.isEmpty()
              && round.textualCalls.isEmpty()) {
            log.warn("El modelo ignoró tool_choice required en la vuelta correctiva (sesión {}): "
                + "con este modelo conviene corrective-tool-choice=prompt",
                abbreviate(run.turn.sessionId()));
          }
          if (round.toolCalls.isEmpty() && !round.textualCalls.isEmpty() && !last) {
            log.warn("El modelo escribió {} tool calls como texto (sesión {}): se ejecutan",
                round.textualCalls.size(), abbreviate(run.turn.sessionId()));
            round.toolCalls.addAll(round.textualCalls);
            run.corrections.add("textual");
          }
          if (round.toolCalls.isEmpty() || last) {
            if (!round.toolCalls.isEmpty()) {
              log.warn("La última vuelta pidió {} tool calls con tool_choice none; se ignoran",
                  round.toolCalls.size());
            }
            boolean falseClaim = round.falseClaims > 0 && !run.toolTurn.hasAdded();
            boolean announced = round.toolCalls.isEmpty()
                && ActionAnnouncement.endsWithAnnouncement(round.shown);
            if (run.turn.isCancelled()) {
              return Mono.<Void>empty();
            }
            if ((falseClaim || announced) && run.correctiveRounds() < MAX_CORRECTIONS
                && n + 1 < tools.maxModelCalls()) {
              boolean cartRequest = isCartRequest(run.turn.message())
                  && !asksUser(round.text);
              run.corrections.add((falseClaim ? "claim" : "announce")
                  + (cartRequest ? (requiredToolChoice() ? "+required" : "+prompt") : ""));
              log.warn("{} (sesión {}): se pide una vuelta correctiva{}", falseClaim
                      ? "El modelo afirmó un agregado al carrito sin llamar a addToCart"
                      : "La respuesta terminó anunciando una acción sin llamar a una tool",
                  abbreviate(run.turn.sessionId()), !cartRequest ? ""
                      : requiredToolChoice() ? " con tool_choice required"
                      : " que pide el tool call en el aviso");
              String note = falseClaim ? CORRECTION_NOTE : ANNOUNCED_NOTE;
              if (cartRequest && !requiredToolChoice()) {
                note = note + " " + TOOL_CALL_NOTE;
              }
              return round(n + 1, corrective(messages, round, note), run, cartRequest);
            }
            boolean notAdded = run.falseClaims > 0 && !run.toolTurn.hasAdded();
            if (notAdded) {
              emitText(run, (round.shown.isEmpty() ? "" : "\n\n") + NOT_ADDED_TEXT);
            } else if (round.shown.isEmpty()) {
              emitText(run, NO_ANSWER_TEXT);
            }
            if (run.falseClaims > 0) {
              run.stats.claimGuard = guardSummary(run, notAdded);
            }
            run.stats.corrections = List.copyOf(run.corrections);
            return Mono.<Void>empty();
          }
          return execute(messages, round, run).flatMap(next -> round(n + 1, next, run, false));
        });
  }

  /** Si la vuelta correctiva de un pedido de carrito va con {@code tool_choice: required}. */
  private boolean requiredToolChoice() {
    return tools.correctiveToolChoice() == ToolsProperties.CorrectiveToolChoice.REQUIRED;
  }

  /**
   * Historial de la vuelta correctiva: lo que dijo el modelo (con la oración
   * descartada, si la hubo, para que sepa a qué se refiere el aviso) y el aviso.
   */
  private static List<Message> corrective(List<Message> messages, Round round, String note) {
    List<Message> next = new ArrayList<>(messages);
    next.add(new AssistantMessage(round.text.toString()));
    next.add(new UserMessage(note));
    return next;
  }

  /** Valor de {@code claimGuard} en la línea del turno, por ejemplo {@code dropped:1+retry}. */
  private static String guardSummary(Run run, boolean notice) {
    return "dropped:" + run.falseClaims + (run.corrections.stream().anyMatch(reason -> reason.startsWith("claim"))
        ? "+retry" : "")
        + (notice ? "+notice" : "");
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
      run.stats.limiterWaitMillis += reservation.delay().toMillis();
      Round round = new Round();
      Mono<Void> call = Mono.defer(() -> stream(messages, options, run, round));
      Mono<Void> scheduled = reservation.delay().isZero() ? call
          : Mono.delay(reservation.delay()).then(call);
      return scheduled.thenReturn(round)
          .onErrorResume(error -> {
            ChatProviderException translated = ChatProviderErrors.translate(error);
            if (translated.reason() != ChatProviderException.Reason.QUOTA || round.emitted
                || retriesLeft <= 0 || run.turn.isCancelled()) {
              return Mono.error(error);
            }
            Duration pause = limiter.pause(translated);
            run.stats.retries429++;
            log.warn("NVIDIA respondió 429 a la vuelta del modelo principal: se pausan las "
                + "solicitudes por {} ms y se reintenta ({} reintentos restantes)",
                pause.toMillis(), retriesLeft - 1);
            return attempt(messages, options, run, retriesLeft - 1);
          });
    });
  }

  /**
   * Stream de una vuelta: reenvía el texto y junta los tool calls.
   *
   * <p>El tiempo límite al primer fragmento se cumple con el primer chunk que
   * trae algo: texto, razonamiento ({@code reasoning_content}) o un tool call.
   * Un modelo que razona siempre, aun con el esfuerzo mínimo (como
   * {@code meta/muse-glimmer-30b}), ya está respondiendo mientras razona, y
   * antes los turnos se cortaban a los 20 s sin texto visible
   * ({@code select-assistant-models}, segundo intento). Un chunk vacío (solo el
   * rol o el {@code finish_reason}) no cuenta. Después del primero, el límite es
   * el del turno.
   */
  private Mono<Void> stream(List<Message> messages, OpenAiChatOptions options, Run run,
      Round round) {
    run.turn.countProviderRequest();
    run.stats.modelCalls++;
    Duration firstToken = run.options.reasoning()
        ? chat.timeouts().firstTokenReasoning() : chat.timeouts().firstToken();
    Flux<String> text = mainChatClient.prompt()
        .messages(messages)
        .options(options)
        .stream()
        .chatResponse()
        .filter(ToolCallingLoop::hasContent)
        .timeout(Mono.delay(min(firstToken, remaining(run.deadline))),
            response -> Mono.delay(remaining(run.deadline)))
        .doOnNext(response -> {
          long reasoning = reasoningLength(response);
          if (reasoning > 0 && run.stats.firstReasoningMillis < 0) {
            run.stats.firstReasoningMillis = run.stats.elapsedMillis();
          }
          run.stats.reasoningChars += reasoning;
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
    TextualToolCalls textual = new TextualToolCalls(toolNames);
    CartClaimFilter guard = new CartClaimFilter(run.toolTurn::hasAdded, textual::extract);
    return text
        .doOnNext(fragment -> {
          round.emitted = true;
          round.text.append(fragment);
          show(run, round, guard.accept(fragment));
        })
        .then(Mono.<Void>fromRunnable(() -> finish(run, round, guard, textual)))
        // Ante un error a mitad de la vuelta, el texto retenido igual se juzga y se emite.
        .onErrorResume(error -> Mono.<Void>fromRunnable(() -> finish(run, round, guard, textual))
            .then(Mono.<Void>error(error)));
  }

  /** Cierre de la vuelta: emite lo que el filtro retenía y cuenta lo descartado. */
  private static void finish(Run run, Round round, CartClaimFilter guard,
      TextualToolCalls textual) {
    show(run, round, guard.flush());
    round.falseClaims = guard.dropped();
    round.textualCalls = textual.calls();
    run.falseClaims += guard.dropped();
  }

  /**
   * Emite el texto que dejó pasar el filtro de la vuelta. La primera vez en una
   * vuelta que sigue a otra con texto, separa ambas con un espacio si hace falta
   * ("…first. I need…" y no "…first.I need…").
   */
  private static void show(Run run, Round round, String text) {
    if (text.isEmpty()) {
      return;
    }
    if (round.shown.isEmpty() && run.endsWithText() && !Character.isWhitespace(text.charAt(0))) {
      text = " " + text;
    }
    round.shown.append(text);
    emitText(run, text);
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
          .toolContext(run.toolTurn.asToolContext())
          .internalToolExecutionEnabled(false)
          .build();
      // Lo que vio el usuario: sin las oraciones descartadas ni los tool calls escritos como texto.
      AssistantMessage request = AssistantMessage.builder()
          .content(round.shown.toString())
          .toolCalls(round.toolCalls)
          .build();
      List<Message> history = toolCallingManager.executeToolCalls(new Prompt(messages, execution),
          new ChatResponse(List.of(new Generation(request)))).conversationHistory();
      run.recordToolRound(memoryCalls(round.toolCalls, history));
      return history;
    }).subscribeOn(Schedulers.boundedElastic());
  }

  /**
   * Los tool calls de la vuelta con el resultado compacto de cada uno, para la
   * memoria de la sesión. El id es el que generó el modelo, el mismo del
   * {@code ToolResponseMessage}; si vino vacío, se genera uno para que el par
   * siga siendo consistente al reenviarlo.
   */
  static List<ToolRound.Call> memoryCalls(List<AssistantMessage.ToolCall> toolCalls,
      List<Message> history) {
    Map<String, String> results = new HashMap<>();
    if (!history.isEmpty() && history.get(history.size() - 1)
        instanceof ToolResponseMessage responses) {
      responses.getResponses().forEach(response ->
          results.putIfAbsent(response.id(), response.responseData()));
    }
    List<ToolRound.Call> calls = new ArrayList<>();
    for (AssistantMessage.ToolCall call : toolCalls) {
      String id = call.id() == null || call.id().isBlank()
          ? "call-" + UUID.randomUUID() : call.id();
      calls.add(new ToolRound.Call(id, call.name(),
          call.arguments() == null ? "{}" : call.arguments(),
          CompactToolResult.of(results.get(call.id()))));
    }
    return calls;
  }

  private static void emitText(Run run, String fragment) {
    if (run.stats.firstFragmentMillis < 0) {
      run.stats.firstFragmentMillis = run.stats.elapsedMillis();
    }
    run.append(fragment);
    run.turn.emit(ServerSentEvent.builder(Map.of("text", fragment)).build());
  }

  private static String abbreviate(String sessionId) {
    return sessionId.length() <= 8 ? sessionId : sessionId.substring(0, 8);
  }

  private static List<AssistantMessage.ToolCall> toolCalls(ChatResponse response) {
    if (response.getResult() == null || response.getResult().getOutput() == null
        || !response.getResult().getOutput().hasToolCalls()) {
      return List.of();
    }
    return response.getResult().getOutput().getToolCalls();
  }

  /** Si el chunk trae texto, razonamiento o tool calls (cuenta para el tiempo límite). */
  static boolean hasContent(ChatResponse response) {
    return !text(response).isEmpty() || reasoningLength(response) > 0
        || !toolCalls(response).isEmpty();
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
