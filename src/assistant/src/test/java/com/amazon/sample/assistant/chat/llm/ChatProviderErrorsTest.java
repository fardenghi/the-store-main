package com.amazon.sample.assistant.chat.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.assistant.chat.llm.ChatProviderException.Reason;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Traducción de los errores del proveedor de chat (D10). */
class ChatProviderErrorsTest {

  private static WebClientResponseException streamingError(int status, String retryAfter) {
    HttpHeaders headers = new HttpHeaders();
    if (retryAfter != null) {
      headers.add(HttpHeaders.RETRY_AFTER, retryAfter);
    }
    return WebClientResponseException.create(status, "error", headers,
        "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
  }

  @Test
  void streaming429WithRetryAfterIsQuota() {
    ChatProviderException e = ChatProviderErrors.translate(streamingError(429, "17"));

    assertThat(e.reason()).isEqualTo(Reason.QUOTA);
    assertThat(e.reason().type()).isEqualTo("llm-quota-exceeded");
    assertThat(e.retryAfter()).contains(Duration.ofSeconds(17));
  }

  @Test
  void streaming429WithoutRetryAfter() {
    ChatProviderException e = ChatProviderErrors.translate(streamingError(429, null));

    assertThat(e.reason()).isEqualTo(Reason.QUOTA);
    assertThat(e.retryAfter()).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"401, UNAUTHORIZED", "403, UNAUTHORIZED", "500, UNAVAILABLE", "502, UNAVAILABLE",
      "503, UNAVAILABLE", "404, UNAVAILABLE"})
  void streamingStatus(int status, Reason reason) {
    assertThat(ChatProviderErrors.translate(streamingError(status, null)).reason())
        .isEqualTo(reason);
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "429 - {\"status\":429,\"title\":\"Too Many Requests\"} | QUOTA",
      "401 - {\"status\":401,\"title\":\"Unauthorized\"} | UNAUTHORIZED",
      "403 - Forbidden | UNAUTHORIZED"})
  void nonStreamingClientErrors(String message, Reason reason) {
    assertThat(ChatProviderErrors.translate(new NonTransientAiException(message)).reason())
        .isEqualTo(reason);
  }

  @Test
  void nonStreamingServerErrorIsUnavailable() {
    assertThat(ChatProviderErrors.translate(new TransientAiException("503 - down")).reason())
        .isEqualTo(Reason.UNAVAILABLE);
  }

  @Test
  void timeoutsAndNetworkErrorsAreUnavailable() {
    assertThat(ChatProviderErrors.translate(new TimeoutException("first token")).reason())
        .isEqualTo(Reason.UNAVAILABLE);
    assertThat(ChatProviderErrors.translate(new WebClientRequestException(
        new ConnectException("refused"), HttpMethod.POST, URI.create("http://nvidia"),
        new HttpHeaders())).reason()).isEqualTo(Reason.UNAVAILABLE);
    assertThat(ChatProviderErrors.translate(new ResourceAccessException("timed out")).reason())
        .isEqualTo(Reason.UNAVAILABLE);
    assertThat(ChatProviderErrors.translate(new IllegalStateException("bug")).reason())
        .isEqualTo(Reason.UNAVAILABLE);
  }

  @Test
  void retryAfterParsing() {
    assertThat(ChatProviderErrors.retryAfter("2.5")).isEqualTo(Duration.ofMillis(2500));
    assertThat(ChatProviderErrors.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT")).isNull();
    assertThat(ChatProviderErrors.retryAfter(null)).isNull();
  }
}
