package com.amazon.sample.assistant.tools;

import com.amazon.sample.assistant.chat.session.ShownProduct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Sinks;

/**
 * Contexto de un turno para las tools (D2 de {@code add-assistant-tools}).
 * Viaja en el {@link ToolContext} de Spring AI, que no se expone al modelo: la
 * sesión que se usa como {@code customerId} del carrito sale de acá y nunca de
 * un argumento de la tool.
 *
 * <p>Lleva el canal de eventos del turno (para {@code tool} y
 * {@code cart-updated}), el presupuesto de tools, los productos ya agregados al
 * carrito en el turno, los productos que devolvieron las tools (para la memoria
 * de la sesión) y el resultado de cada tool (para la línea del turno).
 */
public class TurnToolContext {

  /** Clave del contexto en el mapa del {@link ToolContext}. */
  public static final String KEY = "assistant.turn";

  private final String sessionId;
  private final Sinks.Many<ServerSentEvent<?>> sink;
  private final int maxToolCalls;
  private final AtomicInteger toolCalls = new AtomicInteger();
  private final Set<String> added = ConcurrentHashMap.newKeySet();
  private final Map<String, ShownProduct> shown = new LinkedHashMap<>();
  private final List<String> outcomes = new ArrayList<>();

  public TurnToolContext(String sessionId, Sinks.Many<ServerSentEvent<?>> sink, int maxToolCalls) {
    this.sessionId = sessionId;
    this.sink = sink;
    this.maxToolCalls = maxToolCalls;
  }

  /** El contexto del turno dentro del {@link ToolContext} de una llamada. */
  public static TurnToolContext from(ToolContext toolContext) {
    Object value = toolContext == null ? null : toolContext.getContext().get(KEY);
    if (value instanceof TurnToolContext turn) {
      return turn;
    }
    throw new IllegalStateException("La tool se llamó sin el contexto del turno");
  }

  /** El mapa para {@code toolContext} de las opciones del prompt. */
  public Map<String, Object> asToolContext() {
    return Map.of(KEY, this);
  }

  /** El {@code X-Session-ID} del turno, que es el {@code customerId} del carrito. */
  public String sessionId() {
    return sessionId;
  }

  /**
   * Consume un lugar del presupuesto de tools del turno.
   *
   * @return {@code false} si ya se ejecutaron {@code max-tool-calls}
   */
  public boolean tryConsumeToolCall() {
    return toolCalls.incrementAndGet() <= maxToolCalls;
  }

  public int toolCalls() {
    return Math.min(toolCalls.get(), maxToolCalls);
  }

  /** Si el producto ya se agregó al carrito en este turno. */
  public boolean alreadyAdded(String productId) {
    return added.contains(productId);
  }

  public void markAdded(String productId) {
    added.add(productId);
  }

  /** Suma productos devueltos por una tool, sin repetidos y en orden de aparición. */
  public synchronized void addShown(List<ShownProduct> products) {
    products.forEach(product -> shown.putIfAbsent(product.id(), product));
  }

  /** Productos devueltos por las tools del turno, en orden de aparición. */
  public synchronized List<ShownProduct> shownProducts() {
    return List.copyOf(shown.values());
  }

  /** Registra el resultado de una tool ({@code ok} o el tipo de error). */
  public synchronized void recordOutcome(String tool, String outcome) {
    outcomes.add(tool + ":" + outcome);
  }

  /** Resultados de las tools del turno, por ejemplo {@code searchProducts:ok}. */
  public synchronized List<String> outcomes() {
    return List.copyOf(outcomes);
  }

  /** Emite un evento por el canal del turno; si el turno ya terminó, se descarta. */
  public void emit(ServerSentEvent<?> event) {
    sink.emitNext(event, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1)));
  }
}
