package com.amazon.sample.assistant.products.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.amazon.sample.assistant.config.ToolsConfiguration;
import com.amazon.sample.assistant.config.ToolsProperties;
import com.amazon.sample.assistant.products.Backoff;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Lecturas de las tools al catálogo (D5 de {@code add-assistant-tools}): un
 * solo reintento ante 5xx, timeout o error de red, y 404 como vacío.
 */
class CatalogToolReadsTest {

  private static final String ID = "3600929b-2826-5a98-908f-82a1d50bcf2b";
  private static final String PRODUCT = """
      {"id":"%s","name":"Aiden Mid-Century Velvet Armchair","description":"Plush.",
       "price":139,"tags":[{"name":"seating","displayName":"Seating"}]}""".formatted(ID);

  private final RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog");
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  private final CatalogClient client = new CatalogClient(builder.build(), 50, Backoff.DEFAULT,
      duration -> {
        throw new AssertionError("las lecturas de las tools no esperan entre intentos");
      });

  @Test
  void serverErrorFollowedBySuccessReturnsTheProduct() {
    server.expect(requestTo("http://catalog/catalog/products/" + ID)).andRespond(withServerError());
    server.expect(requestTo("http://catalog/catalog/products/" + ID))
        .andRespond(withSuccess(PRODUCT, MediaType.APPLICATION_JSON));

    assertThat(client.getProduct(ID)).hasValueSatisfying(product -> {
      assertThat(product.name()).isEqualTo("Aiden Mid-Century Velvet Armchair");
      assertThat(product.price()).isEqualTo(139);
      assertThat(product.tagNames()).containsExactly("seating");
    });
    server.verify();
  }

  @Test
  void twoServerErrorsThrowServiceUnavailable() {
    server.expect(ExpectedCount.times(2), requestTo("http://catalog/catalog/products/" + ID))
        .andRespond(withServerError());

    assertThatThrownBy(() -> client.getProduct(ID))
        .isInstanceOf(CatalogUnavailableException.class);
    server.verify();
  }

  @Test
  void notFoundIsEmptyAndNotRetried() {
    server.expect(ExpectedCount.once(), requestTo("http://catalog/catalog/products/" + ID))
        .andRespond(withResourceNotFound());

    assertThat(client.getProduct(ID)).isEmpty();
    server.verify();
  }

  @Test
  void listSendsTagsAndOrderAsQueryParams() {
    // La coma de los tags va codificada (%2C): Gin la decodifica en ctx.Query("tags").
    server.expect(requestTo(
            "http://catalog/catalog/products?page=2&size=50&tags=lighting%2Cdecor&order=price_asc"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[" + PRODUCT + "]", MediaType.APPLICATION_JSON));

    List<CatalogProduct> products = client.listProducts(List.of("lighting", "decor"), "price_asc",
        2, 50);

    assertThat(products).extracting(CatalogProduct::id).containsExactly(ID);
    server.verify();
  }

  @Test
  void listWithoutTagsNorOrderOmitsThem() {
    server.expect(requestTo("http://catalog/catalog/products?page=1&size=50"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    assertThat(client.listProducts(List.of(), null, 1, 50)).isEmpty();
    server.verify();
  }

  @Test
  void listRetriesOnceOnServerError() {
    server.expect(requestTo("http://catalog/catalog/products?page=1&size=50"))
        .andRespond(withServerError());
    server.expect(requestTo("http://catalog/catalog/products?page=1&size=50"))
        .andRespond(withSuccess("[" + PRODUCT + "]", MediaType.APPLICATION_JSON));

    assertThat(client.listProducts(null, null, 1, 50)).hasSize(1);
    server.verify();
  }

  @Test
  void readTimeoutOfTheToolsClientIsRetriedOnceAndThenUnavailable() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    HttpServer slow = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    slow.createContext("/", exchange -> {
      requests.incrementAndGet();
      try {
        Thread.sleep(1_000);
        exchange.sendResponseHeaders(200, -1);
      } catch (Exception ignored) {
        // el cliente ya cortó
      } finally {
        exchange.close();
      }
    });
    slow.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    slow.start();
    try {
      String baseUrl = "http://localhost:" + slow.getAddress().getPort();
      RestClient tools = ToolsConfiguration.toolsRestClient(RestClient.builder(), baseUrl,
          new ToolsProperties.Http(Duration.ofSeconds(2), Duration.ofMillis(200)));
      CatalogClient timed = new CatalogClient(RestClient.create(baseUrl), tools, 50,
          Backoff.DEFAULT, duration -> { });

      long start = System.nanoTime();
      assertThatThrownBy(() -> timed.getProduct(ID))
          .isInstanceOf(CatalogUnavailableException.class);

      assertThat(requests.get()).isEqualTo(2);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    } finally {
      slow.stop(0);
    }
  }
}
