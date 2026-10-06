package com.amazon.sample.assistant.products.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazon.sample.assistant.config.ApiKeysStartupLogger;
import com.amazon.sample.assistant.products.catalog.CatalogClient;
import com.amazon.sample.assistant.products.index.ProductIndexState.Phase;
import com.amazon.sample.assistant.products.vector.ProductVectorRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * La sincronización arranca sola con {@code ApplicationReadyEvent}, en segundo
 * plano: mientras {@code catalog} demora la respuesta, la readiness ya está
 * {@code UP} y {@code productIndex} está en {@code SYNCING} (D5).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "retail.assistant.indexing.sync-on-startup=true",
    "spring.ai.openai.api-key=" + ApiKeysStartupLogger.PLACEHOLDER,
    "spring.ai.google.genai.embedding.api-key=" + ApiKeysStartupLogger.PLACEHOLDER,
    "spring.ai.vectorstore.qdrant.port=1"
})
class ProductIndexStartupTest {

  private static final CountDownLatch RELEASE_CATALOG = new CountDownLatch(1);

  // Los dobles se arman antes de que el contexto publique ApplicationReadyEvent.
  @TestBean(methodName = "blockingCatalog")
  private CatalogClient catalog;

  @TestBean(methodName = "emptyRepository")
  private ProductVectorRepository repository;

  static CatalogClient blockingCatalog() throws InterruptedException {
    CatalogClient catalog = mock(CatalogClient.class);
    when(catalog.fetchAll()).thenAnswer(invocation -> {
      RELEASE_CATALOG.await(30, TimeUnit.SECONDS);
      return List.of();
    });
    return catalog;
  }

  static ProductVectorRepository emptyRepository() {
    ProductVectorRepository repository = mock(ProductVectorRepository.class);
    when(repository.collection()).thenReturn("products");
    when(repository.scrollAllPayloads()).thenReturn(Map.of());
    return repository;
  }

  @Autowired
  private ProductIndexer indexer;

  @Autowired
  private ProductIndexState state;

  @Autowired
  private TestRestTemplate rest;

  @AfterEach
  void release() {
    RELEASE_CATALOG.countDown();
  }

  @Test
  void readinessIsUpWhileSyncing() throws Exception {
    await().atMost(Duration.ofSeconds(10)).until(() -> state.phase() == Phase.SYNCING);

    ResponseEntity<String> readiness = rest.getForEntity("/actuator/health/readiness", String.class);
    ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
    assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(health.getBody()).contains("\"productIndex\":{\"status\":\"UNKNOWN\"")
        .contains("\"phase\":\"SYNCING\"");

    // Un segundo disparo mientras corre la primera se ignora.
    assertThat(indexer.syncAsync(new SyncTaskExecutor())).isFalse();
    assertThat(indexer.sync()).isNull();

    RELEASE_CATALOG.countDown();
    await().atMost(Duration.ofSeconds(10)).until(() -> state.phase() == Phase.READY);
    verify(catalog, times(1)).fetchAll();
  }
}
