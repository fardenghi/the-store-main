package com.amazon.sample.ui.services.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.ui.services.catalog.model.Product;
import com.amazon.sample.ui.services.catalog.model.ProductTag;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class AssistantClientTests {

  private static final Duration SIMILAR_TIMEOUT = Duration.ofSeconds(2);

  private MockWebServer server;

  private AssistantClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
    client = new AssistantClient(
      WebClient.create(),
      server.url("/").toString(),
      SIMILAR_TIMEOUT
    );
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  void mapsSimilarProductsInOrder() throws Exception {
    server.enqueue(
      json(
        "[" +
        product("a", "Sofa A", 100, "seating\",\"velvet") +
        "," +
        product("b", "Chair B", 200, "seating") +
        "," +
        product("c", "Lamp C", 300, "lighting") +
        "," +
        product("d", "Bench D", 400, "seating") +
        "]"
      )
    );

    StepVerifier.create(client.similar("x", 4))
      .assertNext(products -> {
        assertThat(products).extracting(Product::getId).containsExactly(
          "a",
          "b",
          "c",
          "d"
        );
        var first = products.get(0);
        assertThat(first.getName()).isEqualTo("Sofa A");
        assertThat(first.getPrice()).isEqualTo(100);
        assertThat(first.getDescription()).isEqualTo("desc");
        assertThat(first.getTags()).containsExactly(
          new ProductTag("seating", "seating"),
          new ProductTag("velvet", "velvet")
        );
      })
      .verifyComplete();

    var request = server.takeRequest(1, TimeUnit.SECONDS);
    assertThat(request.getPath()).isEqualTo("/assistant/products/x/similar?k=4");
  }

  @Test
  void excludesTheRequestedProduct() {
    server.enqueue(
      json(
        "[" + product("x", "Self", 1, "seating") + "," +
        product("a", "Other", 2, "seating") + "]"
      )
    );

    StepVerifier.create(client.similar("x", 4))
      .assertNext(products ->
        assertThat(products).extracting(Product::getId).containsExactly("a")
      )
      .verifyComplete();
  }

  @Test
  void notFoundReturnsEmptyList() {
    server.enqueue(new MockResponse().setResponseCode(404));
    expectEmpty(client);
  }

  @Test
  void indexUnavailableReturnsEmptyList() {
    server.enqueue(new MockResponse().setResponseCode(503));
    expectEmpty(client);
  }

  @Test
  void invalidBodyReturnsEmptyList() {
    server.enqueue(json("{not json"));
    expectEmpty(client);
  }

  @Test
  void unresponsiveServerReturnsEmptyListWithinTimeout() {
    server.enqueue(
      new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
    );

    long start = System.nanoTime();
    StepVerifier.create(client.similar("x", 4))
      .expectNext(List.of())
      .expectComplete()
      .verify(SIMILAR_TIMEOUT.plusSeconds(1));
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThanOrEqualTo(
      SIMILAR_TIMEOUT.plusSeconds(1)
    );
  }

  @Test
  void emptyEndpointReturnsEmptyListWithoutCalling() {
    expectEmpty(new AssistantClient(WebClient.create(), "", SIMILAR_TIMEOUT));
    expectEmpty(new AssistantClient(WebClient.create(), null, SIMILAR_TIMEOUT));
    assertThat(server.getRequestCount()).isZero();
  }

  private static void expectEmpty(AssistantClient client) {
    StepVerifier.create(client.similar("x", 4))
      .expectNext(List.of())
      .verifyComplete();
  }

  private static MockResponse json(String body) {
    return new MockResponse()
      .setHeader("Content-Type", "application/json")
      .setBody(body);
  }

  private static String product(String id, String name, int price, String tags) {
    return (
      "{\"id\":\"" + id + "\",\"name\":\"" + name +
      "\",\"description\":\"desc\",\"price\":" + price + ",\"tags\":[\"" +
      tags + "\"],\"score\":0.8}"
    );
  }
}
