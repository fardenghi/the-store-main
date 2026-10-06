package com.amazon.sample.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.assistant.config.ApiKeysStartupLogger;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Spec "Sin clave de chat": con {@code NVIDIA_API_KEY} en el placeholder y un
 * proveedor que responde 401, el stream termina con
 * {@code llm-provider-unauthorized} y la readiness sigue en {@code UP}. La
 * reescritura también recibe 401 y cae al mensaje crudo; Qdrant no está, así
 * que el turno sigue con el catálogo no disponible.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "spring.ai.openai.api-key=" + ApiKeysStartupLogger.PLACEHOLDER,
    "spring.ai.google.genai.embedding.api-key=" + ApiKeysStartupLogger.PLACEHOLDER,
    "spring.ai.vectorstore.qdrant.port=1",
    "retail.assistant.endpoints.catalog=http://localhost:1"
})
class ChatWithoutKeyTest {

  static final FakeChatProvider PROVIDER;

  static {
    try {
      PROVIDER = new FakeChatProvider();
      PROVIDER.failWith(401, null);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void provider(DynamicPropertyRegistry registry) {
    registry.add("spring.ai.openai.base-url", PROVIDER::baseUrl);
  }

  @AfterAll
  static void stop() {
    PROVIDER.close();
  }

  @Autowired
  private TestRestTemplate rest;

  @Test
  void streamEndsWithUnauthorizedAndReadinessStaysUp() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setAccept(java.util.List.of(MediaType.TEXT_EVENT_STREAM));
    headers.add("X-Session-ID", "no-key-session");

    ResponseEntity<String> response = rest.postForEntity("/assistant/chat",
        new HttpEntity<>("{\"message\":\"a velvet armchair\"}", headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_EVENT_STREAM))
        .isTrue();
    assertThat(response.getBody())
        .contains("event:products")
        .contains("event:error")
        .contains("\"type\":\"llm-provider-unauthorized\"")
        .doesNotContain("event:done");
    // Una solicitud de reescritura y una del modelo principal, ambas rechazadas.
    assertThat(PROVIDER.requests()).hasSize(2);

    ResponseEntity<String> readiness =
        rest.getForEntity("/actuator/health/readiness", String.class);
    assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(readiness.getBody()).contains("\"status\":\"UP\"");
  }
}
