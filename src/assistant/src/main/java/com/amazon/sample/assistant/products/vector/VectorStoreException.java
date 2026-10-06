package com.amazon.sample.assistant.products.vector;

import java.util.concurrent.TimeoutException;

/** Error al hablar con Qdrant. */
public class VectorStoreException extends RuntimeException {

  /**
   * {@code io.grpc} llega solo como dependencia de runtime del cliente de
   * Qdrant, así que el código de estado se lee del mensaje de la
   * {@code StatusRuntimeException}, que siempre empieza con él
   * ({@code "UNAVAILABLE: io exception"}).
   */
  private static final String GRPC_STATUS_EXCEPTION = "io.grpc.StatusRuntimeException";

  public VectorStoreException(String message, Throwable cause) {
    super(message, cause);
  }

  /** Qdrant no respondió (caído, sin red o timeout): vale la pena reintentar. */
  public boolean isUnavailable() {
    if (getCause() instanceof TimeoutException) {
      return true;
    }
    String code = grpcCode();
    return "UNAVAILABLE".equals(code) || "DEADLINE_EXCEEDED".equals(code);
  }

  boolean isNotFound() {
    return "NOT_FOUND".equals(grpcCode());
  }

  private String grpcCode() {
    for (Throwable t = getCause(); t != null; t = t.getCause()) {
      if (GRPC_STATUS_EXCEPTION.equals(t.getClass().getName()) && t.getMessage() != null) {
        String message = t.getMessage();
        int colon = message.indexOf(':');
        return colon < 0 ? message.trim() : message.substring(0, colon).trim();
      }
    }
    return null;
  }
}
