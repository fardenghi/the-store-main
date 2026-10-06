package com.amazon.sample.assistant.products.embedding;

import com.amazon.sample.assistant.products.embedding.EmbeddingProviderException.Reason;
import com.google.genai.errors.ApiException;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Traduce las excepciones del SDK de Google GenAI a
 * {@link EmbeddingProviderException} (D6).
 */
final class GeminiErrors {

  /**
   * El SDK conserva solo {@code error.message} del cuerpo de la respuesta, así
   * que el {@code retryDelay} del detalle {@code RetryInfo} se pierde. Gemini lo
   * repite en el mensaje ("Please retry in 41.27s."); se acepta también el
   * formato {@code "retryDelay": "41s"} por si viene completo.
   */
  private static final Pattern RETRY_IN = Pattern.compile(
      "(?:retry in|\"?retryDelay\"?\\s*:\\s*\"?)\\s*([0-9]+(?:\\.[0-9]+)?)\\s*s",
      Pattern.CASE_INSENSITIVE);

  private GeminiErrors() {
  }

  static EmbeddingProviderException translate(RuntimeException e) {
    if (e instanceof EmbeddingProviderException translated) {
      return translated;
    }
    if (e instanceof ApiException api) {
      int code = api.code();
      String message = "Gemini respondió " + code + " " + api.status();
      if (code == 429) {
        return new EmbeddingProviderException(Reason.QUOTA, retryDelay(api.message()), message, e);
      }
      if (code == 400 || code == 401 || code == 403) {
        return new EmbeddingProviderException(Reason.UNAUTHORIZED, null, message, e);
      }
      return new EmbeddingProviderException(Reason.UNAVAILABLE, null, message, e);
    }
    // GenAiIOException (red, timeout) y cualquier otro error inesperado.
    return new EmbeddingProviderException(Reason.UNAVAILABLE, null,
        "Error al llamar a Gemini: " + e.getClass().getSimpleName(), e);
  }

  static Duration retryDelay(String message) {
    if (message == null) {
      return null;
    }
    Matcher matcher = RETRY_IN.matcher(message);
    if (!matcher.find()) {
      return null;
    }
    double seconds = Double.parseDouble(matcher.group(1));
    return Duration.ofMillis((long) Math.ceil(seconds * 1000));
  }
}
