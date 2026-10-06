package com.amazon.sample.assistant.products.embedding;

import java.time.Duration;
import java.util.Optional;

/**
 * Error del proveedor de embeddings, independiente del SDK de Google GenAI
 * (D6). Lo usan la sincronización (para decidir si reintenta) y la búsqueda
 * (para elegir el {@code 503} que responde).
 */
public class EmbeddingProviderException extends RuntimeException {

  /** Causa del error, que decide si se reintenta y qué se responde. */
  public enum Reason {
    /** 429: cuota excedida. Se reintenta después de {@link #retryAfter()}. */
    QUOTA("embedding-quota-exceeded"),
    /** 400, 401 o 403: clave ausente o inválida. No se reintenta. */
    UNAUTHORIZED("embedding-provider-unauthorized"),
    /** 5xx, timeout o error de red. Se reintenta. */
    UNAVAILABLE("embedding-provider-unavailable");

    private final String type;

    Reason(String type) {
      this.type = type;
    }

    /** Identificador estable del error (el {@code type} del ProblemDetail). */
    public String type() {
      return type;
    }
  }

  private final Reason reason;
  private final Duration retryAfter;

  public EmbeddingProviderException(Reason reason, Duration retryAfter, String message,
      Throwable cause) {
    super(message, cause);
    this.reason = reason;
    this.retryAfter = retryAfter;
  }

  public Reason reason() {
    return reason;
  }

  /** Espera que informó el proveedor, si vino en el error (solo en {@code QUOTA}). */
  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}
