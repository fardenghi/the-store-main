package com.amazon.sample.assistant.products.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.amazon.sample.assistant.config.ApiKeysStartupLogger;
import com.amazon.sample.assistant.products.catalog.CatalogClient;
import com.amazon.sample.assistant.products.catalog.CatalogReadException;
import com.amazon.sample.assistant.products.vector.ProductVectorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Con la sincronización fallida, {@code productIndex} queda {@code DOWN} en
 * {@code /actuator/health} y la readiness sigue {@code UP} (D7).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "spring.ai.openai.api-key=" + ApiKeysStartupLogger.PLACEHOLDER,
    "spring.ai.google.genai.embedding.api-key=" + ApiKeysStartupLogger.PLACEHOLDER,
    "spring.ai.vectorstore.qdrant.port=1"
})
class ProductIndexHealthEndpointTest {

  @MockitoBean
  private CatalogClient catalog;

  @MockitoBean
  private ProductVectorRepository repository;

  @Autowired
  private ProductIndexer indexer;

  @Autowired
  private TestRestTemplate rest;

  @Test
  void failedSyncIsDownButReadinessIsUp() throws Exception {
    when(repository.collection()).thenReturn("products");
    when(catalog.fetchAll()).thenThrow(new CatalogReadException("catalog respondió 404", null));

    assertThat(indexer.sync()).isNull();

    ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
    ResponseEntity<String> readiness = rest.getForEntity("/actuator/health/readiness", String.class);
    assertThat(health.getBody()).contains("\"productIndex\":{\"status\":\"DOWN\"")
        .contains("catalog-unavailable");
    assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(readiness.getBody()).contains("\"status\":\"UP\"");
  }
}
