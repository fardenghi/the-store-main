package com.amazon.sample.assistant.products.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.amazon.sample.assistant.products.Backoff;
import com.amazon.sample.assistant.products.Backoff.Sleeper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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

@RestClientTest
class CatalogClientTest {

  static final List<Duration> SLEEPS = new ArrayList<>();

  @TestConfiguration
  static class Config {
    @Bean
    CatalogClient catalogClient(RestClient.Builder builder) {
      // Backoff inyectable: registra las esperas en lugar de dormir.
      return new CatalogClient(builder.baseUrl("http://catalog").build(), 50, Backoff.DEFAULT,
          SLEEPS::add);
    }
  }

  @Autowired
  private CatalogClient client;

  @Autowired
  private MockRestServiceServer server;

  @Test
  void readsAllPagesWithoutDuplicates() throws InterruptedException {
    List<String> ids = ids(120);
    expectPage(1, ids.subList(0, 50));
    expectPage(2, ids.subList(50, 100));
    expectPage(3, ids.subList(100, 120));

    List<CatalogProduct> products = client.fetchAll();

    assertThat(products).hasSize(120);
    assertThat(products).extracting(CatalogProduct::id).doesNotHaveDuplicates()
        .containsExactlyElementsOf(ids);
    CatalogProduct first = products.get(0);
    assertThat(first.name()).isEqualTo("Product 0");
    assertThat(first.price()).isEqualTo(100);
    assertThat(first.tags()).containsExactly(new CatalogProduct.Tag("seating", "Seating"));
    server.verify();
  }

  @Test
  void exactPageFollowedByEmptyPage() throws InterruptedException {
    List<String> ids = ids(50);
    expectPage(1, ids);
    expectPage(2, List.of());

    assertThat(client.fetchAll()).hasSize(50);
    server.verify();
  }

  @Test
  void deduplicatesRepeatedIds() throws InterruptedException {
    List<String> ids = ids(60);
    List<String> page2 = new ArrayList<>(ids.subList(50, 60));
    page2.add(ids.get(0));
    expectPage(1, ids.subList(0, 50));
    expectPage(2, page2);

    assertThat(client.fetchAll()).hasSize(60);
  }

  @Test
  void retriesAfterServerError() throws InterruptedException {
    SLEEPS.clear();
    server.expect(requestTo("http://catalog/catalog/products?page=1&size=50"))
        .andRespond(withServerError());
    expectPage(1, ids(3));

    assertThat(client.fetchAll()).hasSize(3);
    assertThat(SLEEPS).containsExactly(Duration.ofSeconds(2));
    server.verify();
  }

  @Test
  void errorOnPage2RestartsTheWholeRead() throws InterruptedException {
    SLEEPS.clear();
    List<String> ids = ids(70);
    expectPage(1, ids.subList(0, 50));
    server.expect(requestTo("http://catalog/catalog/products?page=2&size=50"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    expectPage(1, ids.subList(0, 50));
    expectPage(2, ids.subList(50, 70));

    List<CatalogProduct> products = client.fetchAll();

    assertThat(products).extracting(CatalogProduct::id).containsExactlyElementsOf(ids);
    assertThat(SLEEPS).hasSize(1);
    server.verify();
  }

  @Test
  void nonRetryableErrorOnPage2ReturnsNoPartialResult() {
    expectPage(1, ids(50));
    server.expect(requestTo("http://catalog/catalog/products?page=2&size=50"))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST));

    assertThatThrownBy(client::fetchAll).isInstanceOf(CatalogReadException.class);
    server.verify();
  }

  @Test
  void backoffGrowsUpTo30Seconds() {
    assertThat(IntStream.rangeClosed(1, 6).mapToObj(Backoff.DEFAULT::delay).toList())
        .containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8),
            Duration.ofSeconds(16), Duration.ofSeconds(30), Duration.ofSeconds(30));
    assertThat(Backoff.DEFAULT.delay(100)).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void interruptedWhileWaitingStopsRetrying() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog");
    MockRestServiceServer local = MockRestServiceServer.bindTo(builder).build();
    local.expect(ExpectedCount.once(), requestTo("http://catalog/catalog/products?page=1&size=50"))
        .andRespond(withServerError());
    Sleeper interrupted = duration -> {
      throw new InterruptedException();
    };
    CatalogClient interruptible = new CatalogClient(builder.build(), 50, Backoff.DEFAULT,
        interrupted);

    assertThatThrownBy(interruptible::fetchAll).isInstanceOf(InterruptedException.class);
  }

  private void expectPage(int page, List<String> ids) {
    server.expect(requestTo("http://catalog/catalog/products?page=" + page + "&size=50"))
        .andRespond(withSuccess(json(ids), MediaType.APPLICATION_JSON));
  }

  static List<String> ids(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> UUID.nameUUIDFromBytes(("p" + i).getBytes()).toString())
        .toList();
  }

  private static String json(List<String> ids) {
    return ids.stream()
        .map(id -> String.format("{\"id\":\"%s\",\"name\":\"Product %d\",\"description\":\"d\","
            + "\"price\":100,\"tags\":[{\"name\":\"seating\",\"displayName\":\"Seating\"}]}",
            id, ids(200).indexOf(id)))
        .collect(Collectors.joining(",", "[", "]"));
  }
}
