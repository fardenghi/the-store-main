package com.amazon.sample.assistant.chat.llm;

import java.time.Duration;
import java.util.Optional;

/**
 * Error del proveedor de chat (NVIDIA), independiente de Spring AI y del
 * cliente HTTP (D10). En el modelo principal termina el stream con el evento
 * {@code error}; en la reescritura dispara el fallback.
 */
public class ChatProviderException extends RuntimeException {

  /** Causa del error, que decide el {@code type} del evento {@code error}. */
  public enum Reason {
    /** 429: cuota excedida. */
    QUOTA("llm-quota-exceeded", "Se excedió la cuota del proveedor de chat"),
    /** 401 o 403: clave ausente o inválida. */
    UNAUTHORIZED("llm-provider-unauthorized",
        "El proveedor de chat no está configurado o rechazó la clave"),
    /** 5xx, timeout o error de red. */
    UNAVAILABLE("llm-provider-unavailable", "El proveedor de chat no está disponible");

    private final String type;
    private final String detail;

    Reason(String type, String detail) {
      this.type = type;
      this.detail = detail;
    }

    /** Identificador estable del error (el {@code type} del evento). */
    public String type() {
      return type;
    }

    /** Mensaje legible para el evento. */
    public String detail() {
      return detail;
    }
  }

  private final Reason reason;
  private final Duration retryAfter;

  public ChatProviderException(Reason reason, Duration retryAfter, String message,
      Throwable cause) {
    super(message, cause);
    this.reason = reason;
    this.retryAfter = retryAfter;
  }

  public Reason reason() {
    return reason;
  }

  /** Espera que informó el proveedor con {@code Retry-After} (solo en {@code QUOTA}). */
  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}
