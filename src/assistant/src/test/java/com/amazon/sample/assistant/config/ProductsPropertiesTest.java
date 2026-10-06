package com.amazon.sample.assistant.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/** Las propiedades de indexación y búsqueda se cargan con los valores de application.yml. */
@SpringBootTest(properties = {
    "spring.ai.openai.api-key=" + ApiKeysStartupLogger.PLACEHOLDER,
    "spring.ai.google.genai.embedding.api-key=" + ApiKeysStartupLogger.PLACEHOLDER,
    "spring.ai.vectorstore.qdrant.port=1"
})
class ProductsPropertiesTest {

  @Autowired
  private IndexingProperties indexing;

  @Autowired
  private SearchProperties search;

  @Value("${spring.ai.vectorstore.qdrant.collection-name}")
  private String collection;

  @Value("${spring.mvc.problemdetails.enabled}")
  private boolean problemDetails;

  @Test
  void loadsDefaults() {
    assertThat(collection).isEqualTo("products");
    assertThat(problemDetails).isTrue();

    assertThat(indexing.pageSize()).isEqualTo(50);
    assertThat(indexing.batchSize()).isEqualTo(100);
    assertThat(indexing.maxProviderRetries()).isEqualTo(5);

    assertThat(search.defaultK()).isEqualTo(5);
    assertThat(search.maxK()).isEqualTo(20);
    assertThat(search.similarDefaultK()).isEqualTo(4);
    assertThat(search.similarMaxK()).isEqualTo(12);
    assertThat(search.queryCacheSize()).isEqualTo(256);
  }
}
