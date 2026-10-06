package com.amazon.sample.ui.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.ui.web.util.SessionIDUtil;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest
@AutoConfigureWebTestClient
class SessionCookieTests {

  private static final String PRODUCT_ID =
    "3600929b-2826-5a98-908f-82a1d50bcf2b";

  @Autowired
  private WebTestClient webTestClient;

  @Test
  void firstVisitToProductPageCreatesCookieForWholeStore() {
    var setCookie = webTestClient
      .get()
      .uri("/catalog/{id}", PRODUCT_ID)
      .exchange()
      .expectStatus()
      .isOk()
      .returnResult(String.class)
      .getResponseHeaders()
      .getFirst(HttpHeaders.SET_COOKIE);

    assertThat(setCookie)
      .startsWith(SessionIDUtil.COOKIE_NAME + "=")
      .contains("Path=/;")
      .contains("HttpOnly")
      .contains("SameSite=Lax");
  }

  @Test
  void sessionHeaderFromBrowserIsReplacedByCookie() {
    var headers = webTestClient
      .get()
      .uri("/utility/headers")
      .cookie(SessionIDUtil.COOKIE_NAME, "a")
      .header(SessionIDUtil.HEADER_NAME, "b")
      .exchange()
      .expectStatus()
      .isOk()
      .expectBody(new ParameterizedTypeReference<Map<String, List<String>>>() {})
      .returnResult()
      .getResponseBody();

    var sessionHeader = headers
      .entrySet()
      .stream()
      .filter(e -> e.getKey().equalsIgnoreCase(SessionIDUtil.HEADER_NAME))
      .map(Map.Entry::getValue)
      .findFirst()
      .orElseThrow();

    assertThat(sessionHeader).containsExactly("a");
  }
}
