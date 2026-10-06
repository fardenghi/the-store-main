package com.amazon.sample.assistant.chat;

import com.amazon.sample.assistant.chat.context.ContextRetriever;
import com.amazon.sample.assistant.chat.context.Retrieval;
import com.amazon.sample.assistant.chat.context.SystemPromptFactory;
import com.amazon.sample.assistant.chat.llm.ChatProviderErrors;
import com.amazon.sample.assistant.chat.llm.ChatProviderException;
import com.amazon.sample.assistant.chat.llm.ReasoningPolicy;
import com.amazon.sample.assistant.chat.llm.ReasoningPolicy.TurnOptions;
import com.amazon.sample.assistant.chat.llm.ThinkTagFilter;
import com.amazon.sample.assistant.chat.rewrite.QueryRewriter;
import com.amazon.sample.assistant.chat.rewrite.Rewrite;
import com.amazon.sample.assistant.chat.session.SessionState;
import com.amazon.sample.assistant.chat.session.SessionStore;
import com.amazon.sample.assistant.chat.session.ShownProduct;
import com.amazon.sample.assistant.chat.session.Turn;
import com.amazon.sample.assistant.config.ChatProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

/**
 * Orquesta un turno de chat (D2):
 *
 * <pre>
 * lock de la sesión ─► reescritura ─► retrieval ─► evento products
 *   ─► modelo principal en streaming (persona + contexto + historial)
 *   ─► fragmentos {"text"} ─► OK: guardar el turno y done │ error: evento error, sin guardar
 *   ─► liberar el lock ─► en segundo plano: top-k crudo y línea de log
 * </pre>
 *
 * <p>La reescritura y la búsqueda son bloqueantes y corren en
 * {@code boundedElastic}. Todos los eventos salen por el {@link ChatTurn#sink()}
 * del turno, con un comentario {@code :keepalive} cada {@code keepalive} sin
 * otros eventos. Si el cliente corta la conexión, se cancela la llamada al
 * modelo, se libera el lock y el turno no se guarda.
 */
public class ChatTurnService {

  private static final Logger log = LoggerFactory.getLogger(ChatTurnService.class);

  static final ServerSentEvent<Object> KEEPALIVE =
      ServerSentEvent.builder().comment("keepalive").build();

  /** Producto tal como lo lleva el evento {@code products}. */
  record ProductEvent(String id, String name, long price) {
  }

  private record Prepared(Rewrite rewrite, Retrieval retrieval) {
  }

  private final SessionStore sessions;
  private final QueryRewriter rewriter;
  private final ContextRetriever retriever;
  private final SystemPromptFactory systemPrompt;
  private final ReasoningPolicy reasoning;
  private final ChatClient mainChatClient;
  private final ChatProperties.Chat properties;
  private final TurnLogger turnLogger = new TurnLogger();

  public ChatTurnService(SessionStore sessions, QueryRewriter rewriter, ContextRetriever retriever,
      SystemPromptFactory systemPrompt, ReasoningPolicy reasoning, ChatClient mainChatClient,
      ChatProperties.Chat properties) {
    this.sessions = sessions;
    this.rewriter = rewriter;
    this.retriever = retriever;
    this.systemPrompt = systemPrompt;
    this.reasoning = reasoning;
    this.mainChatClient = mainChatClient;
    this.properties = properties;
  }

  /**
   * Abre un turno: toma el lock de la sesión y devuelve el stream de eventos,
   * que arranca el pipeline al suscribirse.
   *
   * @throws SessionBusyException si la sesión ya tiene un turno en curso
   */
  public Flux<ServerSentEvent<?>> open(String sessionId, String message) {
    SessionState session = sessions.get(sessionId);
    if (!session.tryAcquire()) {
      throw new SessionBusyException();
    }
    return events(new ChatTurn(sessionId, session, message));
  }

  Flux<ServerSentEvent<?>> events(ChatTurn turn) {
    TurnStats stats = new TurnStats(turn.sessionId(), turn.message());
    return withKeepalive(turn.sink().asFlux())
        .doFirst(() -> turn.pipeline(pipeline(turn, stats).subscribe()))
        .doFinally(signal -> {
          if (signal == SignalType.CANCEL) {
            turn.cancel();
            log.info("Turno cancelado por el cliente (sesión {})", abbreviate(turn.sessionId()));
          }
          turn.session().release();
          stats.totalMillis = stats.elapsedMillis();
          afterTurn(turn, stats);
        });
  }

  private Mono<Void> pipeline(ChatTurn turn, TurnStats stats) {
    long deadline = now() + properties.timeouts().turn().toMillis();
    return Mono.fromCallable(() -> prepare(turn, stats))
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(prepared -> respond(turn, stats, prepared, deadline))
        .onErrorResume(error -> {
          ChatProviderException translated = ChatProviderErrors.translate(error);
          stats.outcome = "error:" + translated.reason().type();
          log.warn("Turno terminado con error {} (sesión {}): {}", translated.reason().type(),
              abbreviate(turn.sessionId()), translated.getMessage());
          turn.emit(errorEvent(translated));
          return Mono.empty();
        })
        .doFinally(signal -> {
          if (signal != SignalType.CANCEL) {
            turn.complete();
          }
        });
  }

  private Prepared prepare(ChatTurn turn, TurnStats stats) {
    Rewrite rewrite = rewriter.rewrite(turn.message(), turn.session());
    for (int i = 0; i < rewrite.providerRequests(); i++) {
      turn.countProviderRequest();
    }
    stats.rewrite = rewrite;
    Retrieval retrieval = retriever.retrieve(rewrite, turn.session());
    stats.retrieval = retrieval;
    return new Prepared(rewrite, retrieval);
  }

  private Mono<Void> respond(ChatTurn turn, TurnStats stats, Prepared prepared, long deadline) {
    Retrieval retrieval = prepared.retrieval();
    turn.emit(ServerSentEvent.builder(retrieval.products().stream()
        .map(p -> new ProductEvent(p.id(), p.name(), p.price())).toList())
        .event("products").build());

    TurnOptions options = reasoning.optionsFor(prepared.rewrite().intent());
    stats.reasoning = options.reasoning();
    List<Message> history = new ArrayList<>();
    for (Turn previous : turn.session().turns()) {
      history.add(new UserMessage(previous.user()));
      history.add(new AssistantMessage(previous.assistant()));
    }
    turn.countProviderRequest();
    Flux<String> text = mainChatClient.prompt()
        .system(systemPrompt.render(retrieval))
        .messages(history)
        .user(turn.message())
        .options(options.options())
        .stream()
        .chatResponse()
        .doOnNext(response -> stats.reasoningChars += reasoningLength(response))
        .map(ChatTurnService::text)
        .filter(fragment -> !fragment.isEmpty());
    if (properties.reasoning().stripThinkTags()) {
      text = ThinkTagFilter.apply(text);
    }
    Duration firstToken = options.reasoning()
        ? properties.timeouts().firstTokenReasoning() : properties.timeouts().firstToken();
    StringBuilder answer = new StringBuilder();
    return text
        .timeout(Mono.delay(min(firstToken, remaining(deadline))),
            fragment -> Mono.delay(remaining(deadline)))
        .doOnNext(fragment -> {
          if (stats.firstFragmentMillis < 0) {
            stats.firstFragmentMillis = stats.elapsedMillis();
          }
          answer.append(fragment);
          turn.emit(ServerSentEvent.builder(Map.of("text", fragment)).build());
        })
        .then(Mono.fromRunnable(() -> {
          if (turn.isCancelled()) {
            return;
          }
          List<ShownProduct> shown = retrieval.searched() && !retrieval.catalogUnavailable()
              ? retrieval.products() : null;
          turn.session().commit(new Turn(turn.message(), answer.toString()), shown);
          stats.outcome = "done";
          turn.emit(ServerSentEvent.builder(Map.of()).event("done").build());
        }));
  }

  /**
   * Después de cerrar el stream: calcula el top-k de la consulta cruda (si la
   * comparación está activada y el turno buscó) y loguea la línea del turno,
   * en segundo plano para no sumar latencia (D9).
   */
  private void afterTurn(ChatTurn turn, TurnStats stats) {
    Retrieval retrieval = stats.retrieval;
    boolean compareRaw = properties.compareRawRetrieval() && retrieval != null
        && retrieval.searched() && !retrieval.catalogUnavailable();
    if (!compareRaw) {
      turnLogger.log(stats, turn.providerRequests());
      return;
    }
    Schedulers.boundedElastic().schedule(() -> {
      try {
        stats.rawTopK = retriever.search(turn.message(), null, null, List.of()).stream()
            .map(ShownProduct::id).toList();
      } catch (RuntimeException e) {
        log.warn("No se pudo calcular el top-k crudo del turno: {}", e.getMessage());
      }
      turnLogger.log(stats, turn.providerRequests());
    });
  }

  /**
   * Agrega un comentario {@code :keepalive} cada vez que pasa
   * {@code timeouts.keepalive} sin ningún evento, para que un proxy no corte
   * la conexión mientras el modelo razona (D1).
   */
  Flux<ServerSentEvent<?>> withKeepalive(Flux<ServerSentEvent<?>> events) {
    Duration every = properties.timeouts().keepalive();
    return events.publish(shared -> Flux.merge(shared,
        shared.map(event -> 0L).startWith(0L)
            .switchMap(event -> Flux.interval(every, every).map(tick -> KEEPALIVE))
            .takeUntilOther(shared.then(Mono.just(true)))));
  }

  private ServerSentEvent<Map<String, Object>> errorEvent(ChatProviderException error) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("type", error.reason().type());
    data.put("detail", error.reason().detail());
    if (error.reason() == ChatProviderException.Reason.QUOTA) {
      error.retryAfter().ifPresent(retryAfter -> data.put("retryAfterSeconds",
          Math.max(1, (long) Math.ceil(retryAfter.toMillis() / 1000.0))));
    }
    return ServerSentEvent.<Map<String, Object>>builder(data).event("error").build();
  }

  private static String text(ChatResponse response) {
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
  private static long now() {
    return Schedulers.parallel().now(TimeUnit.MILLISECONDS);
  }

  private static Duration remaining(long deadline) {
    return Duration.ofMillis(Math.max(0, deadline - now()));
  }

  private static Duration min(Duration a, Duration b) {
    return a.compareTo(b) <= 0 ? a : b;
  }

  private static String abbreviate(String sessionId) {
    return sessionId.length() <= 8 ? sessionId : sessionId.substring(0, 8);
  }
}
