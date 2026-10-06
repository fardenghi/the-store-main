package com.amazon.sample.assistant.chat;

import com.amazon.sample.assistant.chat.session.SessionState;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
  private final CountDownLatch released = new CountDownLatch(1);
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

  /** Marca que el turno terminó y ya liberó el lock de la sesión. */
  void markReleased() {
    released.countDown();
  }

  /**
   * Comprueba si el cliente de este turno sigue conectado, para cuando otra
   * pestaña de la misma sesión quiere abrir un turno (D2).
   *
   * <p>Mientras el modelo razona no se escribe nada, y Tomcat solo detecta que
   * el cliente cerró la conexión al escribir en ella. Por eso se mandan dos
   * comentarios SSE separados por {@code gap}: con la conexión cerrada, la
   * primera escritura provoca el reset del otro extremo y la segunda falla,
   * Spring cancela el stream y el turno libera el lock como en cualquier corte.
   * Con el cliente conectado, los comentarios se ignoran.
   *
   * @param keepalive el comentario SSE que se escribe
   * @param gap espera entre las dos escrituras
   * @param wait cuánto esperar, después de la segunda, a que el turno libere el lock
   * @return {@code true} si el turno terminó y liberó el lock
   */
  boolean probe(ServerSentEvent<?> keepalive, Duration gap, Duration wait)
      throws InterruptedException {
    emit(keepalive);
    if (released.await(gap.toMillis(), TimeUnit.MILLISECONDS)) {
      return true;
    }
    emit(keepalive);
    return released.await(wait.toMillis(), TimeUnit.MILLISECONDS);
  }
}
