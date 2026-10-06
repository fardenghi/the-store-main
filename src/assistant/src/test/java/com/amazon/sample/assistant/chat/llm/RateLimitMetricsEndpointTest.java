package com.amazon.sample.assistant.chat.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.assistant.config.ApiKeysStartupLogger;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * El gauge {@code assistant.ratelimit.window} está en {@code /actuator/metrics}
 * (D12). Usa un límite propio para tener su propio contexto: con las mismas
 * propiedades que {@code StartupWithoutKeysTest} reutilizaría el de ese test, que
 * necesita capturar el log del arranque.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "spring.ai.openai.api-key=" + ApiKeysStartupLogger.PLACEHOLDER,
    "spring.ai.google.genai.embedding.api-key=" + ApiKeysStartupLogger.PLACEHOLDER,
    "spring.ai.vectorstore.qdrant.port=1",
    "retail.assistant.rate-limit.requests-per-minute=35"
})
class RateLimitMetricsEndpointTest {

  @Autowired
  private TestRestTemplate rest;

  @Autowired
  private ChatRateLimiter limiter;

  @Test
  void windowGaugeIsExposed() {
    limiter.reserve(Duration.ZERO);
    limiter.reserve(Duration.ZERO);

    ResponseEntity<String> metric = rest.getForEntity(
        "/actuator/metrics/assistant.ratelimit.window", String.class);

    assertThat(metric.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(metric.getBody()).contains("\"name\":\"assistant.ratelimit.window\"")
        .contains("\"statistic\":\"VALUE\",\"value\":2.0");
  }
}
