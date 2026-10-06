package com.amazon.sample.ui.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

/** La ui sola, sin endpoint del assistant: la ficha se muestra sin similares. */
@SpringBootTest
@AutoConfigureWebTestClient
class CatalogControllerTests {

  @Autowired
  private WebTestClient webTestClient;

  @Test
  void productPageWithoutAssistantEndpointHasNoSimilarSection() {
    var html = webTestClient
      .get()
      .uri("/catalog/3600929b-2826-5a98-908f-82a1d50bcf2b")
      .exchange()
      .expectStatus()
      .isOk()
      .expectBody(String.class)
      .returnResult()
      .getResponseBody();

    assertThat(html).contains("Eva Tufted Velvet Sofa");
    assertThat(html).contains("id=\"add-to-cart\"");
    assertThat(html).doesNotContain("similar-products");
  }
}
