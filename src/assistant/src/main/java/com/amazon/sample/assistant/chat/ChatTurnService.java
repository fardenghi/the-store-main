package com.amazon.sample.assistant.chat;

import com.amazon.sample.assistant.chat.context.ContextRetriever;
import com.amazon.sample.assistant.chat.context.Retrieval;
import com.amazon.sample.assistant.chat.context.SystemPromptFactory;
import com.amazon.sample.assistant.chat.llm.ChatProviderErrors;
import com.amazon.sample.assistant.chat.llm.ChatProviderException;
import com.amazon.sample.assistant.chat.llm.ReasoningPolicy;
import com.amazon.sample.assistant.chat.llm.ReasoningPolicy.TurnOptions;
import com.amazon.sample.assistant.chat.rewrite.QueryRewriter;
import com.amazon.sample.assistant.chat.rewrite.Rewrite;
import com.amazon.sample.assistant.chat.session.SessionState;
import com.amazon.sample.assistant.chat.session.SessionStore;
import com.amazon.sample.assistant.chat.session.ShownProduct;
import com.amazon.sample.assistant.chat.session.ToolRound;
import com.amazon.sample.assistant.chat.session.Turn;
import com.amazon.sample.assistant.config.ChatProperties;
import com.amazon.sample.assistant.tools.TurnToolContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
 *   ─► modelo principal en streaming (persona + contexto + historial con tool calls), con tools
 *   ─► fragmentos {"text"} ─► OK: guardar el turno y done │ error: evento error, sin guardar
 *   ─► liberar el lock ─► en segundo plano: top-k crudo y línea de log
 * </pre>
 *
 * <p>La parte del modelo principal la resuelve el {@link ToolCallingLoop}
 * ({@code add-assistant-tools}, D1): vueltas con tools, eventos {@code tool} y
 * {@code cart-updated}, limitador y reintento ante 429. El razonamiento se
 * decide una vez por turno y vale para todas las vueltas.
 *
 * <p>La reescritura y la búsqueda son bloqueantes y corren en
 * {@code boundedElastic}. Todos los eventos salen por el {@link ChatTurn#sink()}
 * del turno, con un comentario {@code :keepalive} cada {@code keepalive} sin
 * otros eventos. Si el cliente corta la conexión, se cancela la llamada al
 * modelo, se libera el lock y el turno no se guarda.
 *
 * <p>El corte se detecta al escribir en la conexión, y mientras el modelo
 * razona no se escribe nada. Por eso, si llega un turno para una sesión
 * ocupada, primero se sondea la conexión del turno en curso
 * ({@link ChatTurn#probe}): si ese cliente ya se fue, su turno se cancela, libera
 * el lock y el nuevo turno entra; si sigue conectado, el nuevo recibe
 * {@code 409}.
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

  /** Productos que recuerda la sesión de un turno (D7 de {@code add-assistant-tools}). */
  static final int MAX_SHOWN_PRODUCTS = 10;

  /** Espera entre las dos escrituras del sondeo de un turno en curso. */
  static final Duration PROBE_GAP = Duration.ofMillis(100);

  /** Espera máxima, después del sondeo, a que el turno cortado libere el lock. */
  static final Duration PROBE_WAIT = Duration.ofMillis(500);

  private final SessionStore sessions;
  private final QueryRewriter rewriter;
  private final ContextRetriever retriever;
  private final SystemPromptFactory systemPrompt;
  private final ReasoningPolicy reasoning;
  private final ToolCallingLoop loop;
  private final int maxToolCalls;
  private final ChatProperties.Chat properties;
  private final TurnLogger turnLogger = new TurnLogger();
  /** Turno en curso de cada sesión, para sondear su conexión (D2). */
  private final ConcurrentMap<String, ChatTurn> active = new ConcurrentHashMap<>();

  public ChatTurnService(SessionStore sessions, QueryRewriter rewriter, ContextRetriever retriever,
      SystemPromptFactory systemPrompt, ReasoningPolicy reasoning, ToolCallingLoop loop,
      int maxToolCalls, ChatProperties.Chat properties) {
    this.sessions = sessions;
    this.rewriter = rewriter;
    this.retriever = retriever;
    this.systemPrompt = systemPrompt;
    this.reasoning = reasoning;
    this.loop = loop;
    this.maxToolCalls = maxToolCalls;
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
    ChatTurn turn = new ChatTurn(sessionId, session, message);
    if (!session.tryAcquire(turn) && !(currentTurnEnded(sessionId) && session.tryAcquire(turn))) {
      throw new SessionBusyException();
    }
    active.put(sessionId, turn);
    return events(turn);
  }

  /**
   * Sondea la conexión del turno en curso de la sesión y devuelve si terminó
   * (porque su cliente ya no estaba). Bloquea hasta {@code PROBE_GAP +
   * PROBE_WAIT}, solo cuando la sesión está ocupada.
   */
  private boolean currentTurnEnded(String sessionId) {
    ChatTurn current = active.get(sessionId);
    if (current == null) {
      return false;
    }
    try {
      boolean ended = current.probe(KEEPALIVE, PROBE_GAP, PROBE_WAIT);
      if (ended) {
        log.info("Sesión {} ocupada por un turno cuyo cliente ya cortó: se liberó para el turno "
            + "nuevo", abbreviate(sessionId));
      }
      return ended;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
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
          turn.session().release(turn);
          active.remove(turn.sessionId(), turn);
          turn.markReleased();
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
    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(systemPrompt.render(retrieval)));
    for (Turn previous : turn.session().turns()) {
      messages.addAll(history(previous));
    }
    messages.add(new UserMessage(turn.message()));
    TurnToolContext toolTurn = new TurnToolContext(turn.sessionId(), turn.sink(), maxToolCalls);
    return loop.run(turn, stats, messages, options, toolTurn, deadline)
        .timeout(remaining(deadline))
        .doOnTerminate(() -> stats.tools = toolTurn.outcomes())
        .flatMap(answer -> Mono.<Void>fromRunnable(() -> {
          // Un turno cortado libera el lock enseguida (D2): si eso ya pasó,
          // commitIfOwner no lo guarda aunque su respuesta termine después.
          if (turn.isCancelled() || !turn.session().commitIfOwner(turn,
              new Turn(turn.message(), answer.text(), answer.toolRounds()),
              shownProducts(retrieval, toolTurn))) {
            return;
          }
          stats.outcome = "done";
          turn.emit(ServerSentEvent.builder(Map.of()).event("done").build());
        }));
  }

  /**
   * Mensajes de un turno guardado para el historial del modelo principal: el del
   * usuario, cada vuelta con tools como un {@link AssistantMessage} con sus tool
   * calls seguido de un {@link ToolResponseMessage} con los resultados
   * compactos, y la respuesta final. Así el modelo ve qué acciones se hicieron
   * de verdad (corrección posterior de D7 de {@code add-assistant-tools}).
   */
  static List<Message> history(Turn turn) {
    List<Message> messages = new ArrayList<>();
    messages.add(new UserMessage(turn.user()));
    for (ToolRound round : turn.toolRounds()) {
      messages.add(AssistantMessage.builder()
          .content(round.text())
          .toolCalls(round.calls().stream()
              .map(call -> new AssistantMessage.ToolCall(call.id(), "function", call.name(),
                  call.arguments()))
              .toList())
          .build());
      messages.add(ToolResponseMessage.builder()
          .responses(round.calls().stream()
              .map(call -> new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                  call.result()))
              .toList())
          .build());
    }
    String answer = turn.finalText();
    if (!answer.isBlank() || turn.toolRounds().isEmpty()) {
      messages.add(new AssistantMessage(answer));
    }
    return messages;
  }

  /**
   * Productos que recuerda la sesión (D7 de {@code add-assistant-tools}): los
   * que devolvieron las tools del turno, primero, y después los del evento
   * {@code products}, sin repetidos y hasta 10. Sin productos de tools, sigue la
   * regla del chat: los del retrieval si el turno buscó, o {@code null} para
   * conservar los del turno anterior.
   */
  static List<ShownProduct> shownProducts(Retrieval retrieval, TurnToolContext toolTurn) {
    List<ShownProduct> fromRetrieval = retrieval.searched() && !retrieval.catalogUnavailable()
        ? retrieval.products() : null;
    List<ShownProduct> fromTools = toolTurn.shownProducts();
    if (fromTools.isEmpty()) {
      return fromRetrieval;
    }
    Map<String, ShownProduct> merged = new LinkedHashMap<>();
    Stream.concat(fromTools.stream(),
            fromRetrieval == null ? Stream.<ShownProduct>empty() : fromRetrieval.stream())
        .forEach(product -> merged.putIfAbsent(product.id(), product));
    return merged.values().stream().limit(MAX_SHOWN_PRODUCTS).toList();
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

  /** Reloj del scheduler de Reactor, para que los tests con tiempo virtual lo controlen. */
  private static long now() {
    return Schedulers.parallel().now(TimeUnit.MILLISECONDS);
  }

  private static Duration remaining(long deadline) {
    return Duration.ofMillis(Math.max(0, deadline - now()));
  }

  private static String abbreviate(String sessionId) {
    return sessionId.length() <= 8 ? sessionId : sessionId.substring(0, 8);
  }
}
