package com.amazon.sample.assistant.chat.rewrite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.amazon.sample.assistant.products.Backoff;
import com.amazon.sample.assistant.products.catalog.CatalogClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Tags del catálogo para la reescritura (D4): se cachean y un error no corta el turno. */
@RestClientTest
class CatalogTagsCacheTest {

  private static final String TAGS = """
      [{"name":"seating","displayName":"Seating"},{"name":"lighting","displayName":"Lighting"}]
      """;

  @TestConfiguration
  static class Config {
    @Bean
    CatalogClient catalogClient(RestClient.Builder builder) {
      return new CatalogClient(builder.baseUrl("http://catalog").build(), 50, Backoff.DEFAULT,
          delay -> { });
    }
  }

  @Autowired
  private CatalogClient client;

  @Autowired
  private MockRestServiceServer server;

  @Test
  void readsTheTagsOnceAndCachesThem() {
    server.expect(ExpectedCount.once(), requestTo("http://catalog/catalog/tags"))
        .andRespond(withSuccess(TAGS, MediaType.APPLICATION_JSON));
    CatalogTagsCache cache = new CatalogTagsCache(client);

    assertThat(cache.tagNames()).containsExactly("seating", "lighting");
    assertThat(cache.tagNames()).containsExactly("seating", "lighting");
    assertThat(cache.tags().get(1).displayName()).isEqualTo("Lighting");
    server.verify();
  }

  @Test
  void errorLeavesTheCacheEmptyAndRetriesOnTheNextRead() {
    server.expect(ExpectedCount.once(), requestTo("http://catalog/catalog/tags"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    server.expect(ExpectedCount.once(), requestTo("http://catalog/catalog/tags"))
        .andRespond(withSuccess(TAGS, MediaType.APPLICATION_JSON));
    CatalogTagsCache cache = new CatalogTagsCache(client);

    assertThat(cache.tagNames()).isEmpty();
    assertThat(cache.tagNames()).containsExactly("seating", "lighting");
    server.verify();
  }
}
