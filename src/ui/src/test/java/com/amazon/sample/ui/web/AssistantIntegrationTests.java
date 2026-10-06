package com.amazon.sample.ui.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.ui.web.util.SessionIDUtil;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * La ui completa con el provider {@code assistant} apuntando a un
 * {@link MockWebServer}: chat de punta a punta y ficha con similares.
 */
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "retail.ui.chat.enabled=true", "retail.ui.chat.provider=assistant",
  }
)
class AssistantIntegrationTests {

  private static final String PRODUCT_ID =
    "3600929b-2826-5a98-908f-82a1d50bcf2b";

  private static final MockWebServer SERVER = new MockWebServer();

  static {
    try {
      SERVER.start();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void assistantEndpoint(DynamicPropertyRegistry registry) {
    registry.add("retail.ui.endpoints.assistant", () ->
      SERVER.url("/").toString()
    );
  }

  @AfterAll
  static void stopServer() throws IOException {
    SERVER.shutdown();
  }

  @Autowired
  private WebTestClient webTestClient;

  @BeforeEach
  void drainRequests() throws InterruptedException {
    while (SERVER.takeRequest(0, TimeUnit.MILLISECONDS) != null) {
      // descarta requests de tests anteriores
    }
  }

  @Test
  void chatForwardsCookieSessionAndRelaysEvents() throws Exception {
    SERVER.enqueue(
      sse(
        "event:products\ndata:{\"products\":[]}\n\n" +
        "event:tool\ndata:{\"tool\":\"addToCart\",\"ok\":true}\n\n" +
        "data:{\"text\":\"Done, \"}\n\n" +
        "data:{\"text\":\"operative.\"}\n\n" +
        "event:cart-updated\ndata:{\"itemId\":\"x\",\"cartItemCount\":3}\n\n" +
        "event:done\ndata:{}\n\n"
      )
    );

    var body = submit("add it", "cookie-session-1", "browser-session");

    var request = SERVER.takeRequest(1, TimeUnit.SECONDS);
    assertThat(request.getHeader(SessionIDUtil.HEADER_NAME)).isEqualTo(
      "cookie-session-1"
    );
    assertThat(request.getBody().readUtf8()).isEqualTo(
      "{\"message\":\"add it\"}"
    );

    assertThat(body).containsSubsequence(
      "event:products",
      "event:tool",
      "data:{\"text\":\"Done, \"}",
      "data:{\"text\":\"operative.\"}",
      "event:cart-updated\ndata:{\"itemId\":\"x\",\"cartItemCount\":3}",
      "event:done"
    );
  }

  static Stream<Arguments> upstreamFailures() {
    return Stream.of(
      Arguments.of(
        new MockResponse()
          .setResponseCode(400)
          .setHeader("Content-Type", "application/problem+json")
          .setBody("{\"status\":400,\"detail\":\"message is required\"}"),
        "invalid-parameter"
      ),
      Arguments.of(new MockResponse().setResponseCode(409), "session-busy"),
      Arguments.of(
        new MockResponse().setResponseCode(503),
        "assistant-unavailable"
      ),
      Arguments.of(
        new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
        "assistant-unavailable"
      ),
      Arguments.of(
        sse("data:{\"text\":\"partial\"}\n\n"),
        "assistant-unavailable"
      )
    );
  }

  @ParameterizedTest
  @MethodSource("upstreamFailures")
  void upstreamFailuresEndInSingleErrorEventWithHttp200(
    MockResponse response,
    String type
  ) {
    SERVER.enqueue(response);

    var body = submit("hi", "cookie-session-2", null);

    assertThat(count(body, "event:error")).isEqualTo(1);
    assertThat(count(body, "event:done")).isZero();
    assertThat(body).contains("\"type\":\"" + type + "\"");
  }

  @Test
  void productPageShowsSimilarProductsInOrder() {
    SERVER.enqueue(
      new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
          "[" +
          similar("c104454c-d70b-5e39-ae01-6374b1bf5d23", "Frederick Sofa", 1519) +
          "," +
          similar("e340a893-20dc-51ea-8e03-ee430eed6b7e", "Alonzo Loveseat", 1339) +
          "," +
          similar("fad3214a-b0f1-53fc-9d14-c5963ad4eda2", "Sloane Loveseat", 869) +
          "," +
          similar("6be97fab-8ec3-5440-9a99-67a1f29d937d", "Hillman Sofa", 1679) +
          "]"
        )
    );

    var html = getPage("/catalog/" + PRODUCT_ID);

    assertThat(html).contains("id=\"similar-products\"");
    assertThat(html).contains("Similar gear for your lair");
    var section = html.substring(html.indexOf("id=\"similar-products\""));
    assertThat(section).containsSubsequence(
      "href=\"/catalog/c104454c-d70b-5e39-ae01-6374b1bf5d23\"",
      "src=\"/assets/img/products/c104454c-d70b-5e39-ae01-6374b1bf5d23.jpg\"",
      "Frederick Sofa",
      "1519",
      "href=\"/catalog/e340a893-20dc-51ea-8e03-ee430eed6b7e\"",
      "src=\"/assets/img/products/e340a893-20dc-51ea-8e03-ee430eed6b7e.jpg\"",
      "Alonzo Loveseat",
      "1339",
      "src=\"/assets/img/products/fad3214a-b0f1-53fc-9d14-c5963ad4eda2.jpg\"",
      "Sloane Loveseat",
      "869",
      "src=\"/assets/img/products/6be97fab-8ec3-5440-9a99-67a1f29d937d.jpg\"",
      "Hillman Sofa",
      "1679"
    );
    assertThat(count(section, "class=\"similar-product\"")).isEqualTo(4);
  }

  @Test
  void productPageWithoutIndexHasNoSimilarSection() throws Exception {
    SERVER.enqueue(
      new MockResponse()
        .setResponseCode(503)
        .setHeader("Content-Type", "application/problem+json")
        .setBody("{\"status\":503,\"type\":\"index-unavailable\"}")
    );

    var html = getPage("/catalog/" + PRODUCT_ID);

    var request = SERVER.takeRequest(1, TimeUnit.SECONDS);
    assertThat(request.getPath()).isEqualTo(
      "/assistant/products/" + PRODUCT_ID + "/similar?k=4"
    );
    assertThat(html).contains("id=\"add-to-cart\"");
    assertThat(html).doesNotContain("similar-products");
  }

  @Test
  void catalogCardsShowTheirOwnImage() {
    var html = getPage("/catalog");

    var card = Pattern.compile(
      "href=\"/catalog/([^\"]+)\"\\s*><img[^>]*src=\"/assets/img/products/([^\"]+)\\.jpg\""
    ).matcher(html);
    int cards = 0;
    while (card.find()) {
      assertThat(card.group(2)).isEqualTo(card.group(1));
      cards++;
    }
    assertThat(cards).isEqualTo(6);
  }

  private String submit(String message, String cookie, String header) {
    var spec = webTestClient
      .post()
      .uri("/chat/submit")
      .cookie(SessionIDUtil.COOKIE_NAME, cookie)
      .contentType(MediaType.APPLICATION_JSON)
      .accept(MediaType.TEXT_EVENT_STREAM);
    if (header != null) {
      spec = spec.header(SessionIDUtil.HEADER_NAME, header);
    }
    return spec
      .bodyValue(Map.of("message", message))
      .exchange()
      .expectStatus()
      .isOk()
      .expectBody(String.class)
      .returnResult()
      .getResponseBody();
  }

  private String getPage(String path) {
    return webTestClient
      .get()
      .uri(path)
      .exchange()
      .expectStatus()
      .isOk()
      .expectBody(String.class)
      .returnResult()
      .getResponseBody();
  }

  private static String similar(String id, String name, int price) {
    return (
      "{\"id\":\"" + id + "\",\"name\":\"" + name +
      "\",\"description\":\"d\",\"price\":" + price +
      ",\"tags\":[\"seating\"],\"score\":0.9}"
    );
  }

  private static MockResponse sse(String body) {
    return new MockResponse()
      .setHeader("Content-Type", "text/event-stream")
      .setBody(body);
  }

  private static int count(String text, String token) {
    int count = 0;
    for (int i = text.indexOf(token); i >= 0; i = text.indexOf(token, i + 1)) {
      count++;
    }
    return count;
  }
}
