package com.amazon.sample.assistant.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Arranque con ambas claves en el placeholder y sin Qdrant (puerto donde no
 * escucha nadie): el servicio queda listo y no imprime el valor de las claves.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "spring.ai.openai.api-key=" + ApiKeysStartupLogger.PLACEHOLDER,
    "spring.ai.google.genai.embedding.api-key=" + ApiKeysStartupLogger.PLACEHOLDER,
    "spring.ai.vectorstore.qdrant.port=1"
})
class StartupWithoutKeysTest {

  @Autowired
  private TestRestTemplate rest;

  @Test
  void startsReadyWithPlaceholdersAndWithoutQdrant(CapturedOutput output) {
    ResponseEntity<String> readiness = rest.getForEntity("/actuator/health/readiness", String.class);
    ResponseEntity<String> liveness = rest.getForEntity("/actuator/health/liveness", String.class);

    assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(readiness.getBody()).contains("\"status\":\"UP\"");
    assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);

    assertThat(output).contains("NVIDIA_API_KEY (chat): NO configurada");
    assertThat(output).contains("GOOGLE_API_KEY (embeddings): NO configurada");
    assertThat(output).doesNotContain(ApiKeysStartupLogger.PLACEHOLDER);
  }

  @Test
  void qdrantDownDoesNotAffectReadiness() {
    ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
    ResponseEntity<String> readiness = rest.getForEntity("/actuator/health/readiness", String.class);

    assertThat(health.getBody()).contains("\"qdrant\":{\"status\":\"DOWN\"");
    assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
  }
}
