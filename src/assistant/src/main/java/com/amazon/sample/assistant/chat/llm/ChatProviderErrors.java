package com.amazon.sample.assistant.chat.llm;

import com.amazon.sample.assistant.chat.llm.ChatProviderException.Reason;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Exceptions;

/**
 * Traduce los errores de Spring AI y de los clientes HTTP a
 * {@link ChatProviderException} (D10):
 * <ul>
 *   <li>streaming (modelo principal): {@link WebClientResponseException}, con
 *       el status y el header {@code Retry-After};</li>
 *   <li>sin streaming (reescritura): {@link NonTransientAiException} y
 *       {@link TransientAiException}, cuyo mensaje empieza con el status
 *       ({@code "429 - ..."});</li>
 *   <li>timeouts y errores de red: {@code UNAVAILABLE}.</li>
 * </ul>
 */
public final class ChatProviderErrors {

  private static final Pattern STATUS_PREFIX = Pattern.compile("^\\s*(?:HTTP\\s+)?(\\d{3})\\b");

  private ChatProviderErrors() {
  }

  public static ChatProviderException translate(Throwable error) {
    Throwable e = Exceptions.unwrap(error);
    if (e instanceof ChatProviderException translated) {
      return translated;
    }
    if (e instanceof WebClientResponseException response) {
      return fromStatus(response.getStatusCode().value(),
          retryAfter(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)), e);
    }
    if (e instanceof RestClientResponseException response) {
      return fromStatus(response.getStatusCode().value(), response.getResponseHeaders() == null
          ? null : retryAfter(response.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)), e);
    }
    if (e instanceof NonTransientAiException || e instanceof TransientAiException) {
      Matcher matcher = STATUS_PREFIX.matcher(String.valueOf(e.getMessage()));
      if (matcher.find()) {
        return fromStatus(Integer.parseInt(matcher.group(1)), null, e);
      }
      return new ChatProviderException(e instanceof NonTransientAiException
          ? Reason.UNAUTHORIZED : Reason.UNAVAILABLE, null,
          "Error del proveedor de chat: " + e.getClass().getSimpleName(), e);
    }
    if (e instanceof TimeoutException) {
      return new ChatProviderException(Reason.UNAVAILABLE, null,
          "El proveedor de chat no respondió a tiempo", e);
    }
    if (e instanceof WebClientRequestException || e instanceof ResourceAccessException
        || e instanceof IOException) {
      return new ChatProviderException(Reason.UNAVAILABLE, null,
          "No se pudo conectar con el proveedor de chat", e);
    }
    return new ChatProviderException(Reason.UNAVAILABLE, null,
        "Error al llamar al proveedor de chat: " + e.getClass().getSimpleName(), e);
  }

  /** Descripción corta para los logs (sin cuerpos de respuesta ni claves). */
  public static String describe(Throwable error) {
    ChatProviderException translated = translate(error);
    return translated.reason().type() + ": " + translated.getMessage();
  }

  static ChatProviderException fromStatus(int status, Duration retryAfter, Throwable cause) {
    String message = "El proveedor de chat respondió " + status;
    if (status == 429) {
      return new ChatProviderException(Reason.QUOTA, retryAfter, message, cause);
    }
    if (status == 401 || status == 403) {
      return new ChatProviderException(Reason.UNAUTHORIZED, null, message, cause);
    }
    return new ChatProviderException(Reason.UNAVAILABLE, null, message, cause);
  }

  /** {@code Retry-After} en segundos; la forma de fecha HTTP no se usa en NVIDIA. */
  static Duration retryAfter(String header) {
    if (header == null || header.isBlank()) {
      return null;
    }
    try {
      double seconds = Double.parseDouble(header.trim());
      return seconds < 0 ? null : Duration.ofMillis((long) Math.ceil(seconds * 1000));
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
