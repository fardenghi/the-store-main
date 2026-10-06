package com.amazon.sample.assistant.chat;

import com.amazon.sample.assistant.chat.session.SessionState;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

/**
 * Un turno en curso (D2). Todos los eventos SSE del turno salen por su
 * {@link #sink()}, en orden: el evento {@code products}, los fragmentos del
 * modelo, {@code done} o {@code error}. {@code add-assistant-tools} emite por
 * el mismo canal sus propios eventos (por ejemplo, el del carrito) con
 * {@link #emit}, y suma sus solicitudes a NVIDIA con
 * {@link #countProviderRequest()}.
 */
public class ChatTurn {

  private final String sessionId;
  private final SessionState session;
  private final String message;
  private final Sinks.Many<ServerSentEvent<?>> sink = Sinks.many().unicast().onBackpressureBuffer();
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final AtomicInteger providerRequests = new AtomicInteger();
  private volatile Disposable pipeline;

  ChatTurn(String sessionId, SessionState session, String message) {
    this.sessionId = sessionId;
    this.session = session;
    this.message = message;
  }

  public String sessionId() {
    return sessionId;
  }

  public SessionState session() {
    return session;
  }

  public String message() {
    return message;
  }

  /** Canal de eventos del turno. */
  public Sinks.Many<ServerSentEvent<?>> sink() {
    return sink;
  }

  /**
   * Emite un evento por el canal del turno. Es seguro llamarlo desde varios
   * hilos; si el turno ya terminó o se canceló, el evento se descarta.
   */
  public void emit(ServerSentEvent<?> event) {
    sink.emitNext(event, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1)));
  }

  /** Cuenta una solicitud al proveedor de chat hecha en este turno. */
  public void countProviderRequest() {
    providerRequests.incrementAndGet();
  }

  public int providerRequests() {
    return providerRequests.get();
  }

  /** Si el cliente cortó la conexión. */
  public boolean isCancelled() {
    return cancelled.get();
  }

  void complete() {
    sink.emitComplete(Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1)));
  }

  void pipeline(Disposable pipeline) {
    this.pipeline = pipeline;
  }

  /** Corta el pipeline (cliente desconectado): cancela la llamada al modelo. */
  void cancel() {
    cancelled.set(true);
    Disposable current = pipeline;
    if (current != null) {
      current.dispose();
    }
  }
}
