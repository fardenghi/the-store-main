package com.amazon.sample.ui.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazon.sample.ui.chat.ChatEvents;
import com.amazon.sample.ui.chat.ChatStreamService;
import com.amazon.sample.ui.web.util.SessionIDUtil;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SpringBootTest(
  properties = {
    "retail.ui.chat.enabled=true", "retail.ui.chat.provider=mock",
  }
)
@AutoConfigureWebTestClient
class ChatControllerTests {

  @Autowired
  private WebTestClient webTestClient;

  @MockitoBean
  private ChatStreamService chatStreamService;

  @Test
  void forwardsCookieSessionAndSetsStreamingHeaders() {
    when(chatStreamService.stream(anyString(), anyString())).thenReturn(
      Flux.just(ChatEvents.text("hi"), ChatEvents.done())
    );

    var body = webTestClient
      .post()
      .uri("/chat/submit")
      .cookie(SessionIDUtil.COOKIE_NAME, "cookie-session")
      .header(SessionIDUtil.HEADER_NAME, "browser-session")
      .contentType(MediaType.APPLICATION_JSON)
      .accept(MediaType.TEXT_EVENT_STREAM)
      .bodyValue(Map.of("message", "I need a lamp"))
      .exchange()
      .expectStatus()
      .isOk()
      .expectHeader()
      .valueEquals(HttpHeaders.CACHE_CONTROL, "no-cache")
      .expectHeader()
      .valueEquals("X-Accel-Buffering", "no")
      .expectBody(String.class)
      .returnResult()
      .getResponseBody();

    verify(chatStreamService).stream(eq("cookie-session"), eq("I need a lamp"));
    assertThat(body)
      .contains("data:{\"text\":\"hi\"}")
      .contains("event:done");
  }

  @Test
  void emitsKeepalivesWhileWaitingAndStopsAfterDone() {
    var tickerCancelled = new AtomicBoolean();

    StepVerifier.withVirtualTime(() ->
      ChatController.withKeepalive(
        Mono.just(ChatEvents.text("late"))
          .delayElement(Duration.ofSeconds(35))
          .concatWith(Mono.just(ChatEvents.done())),
        Flux.interval(Duration.ofSeconds(10), Duration.ofSeconds(10)).doOnCancel(
          () -> tickerCancelled.set(true)
        )
      )
    )
      .thenAwait(Duration.ofSeconds(35))
      .expectNextMatches(ChatControllerTests::isKeepalive)
      .expectNextMatches(ChatControllerTests::isKeepalive)
      .expectNextMatches(ChatControllerTests::isKeepalive)
      .expectNextMatches(e -> e.data() != null && e.data().contains("late"))
      .expectNextMatches(e -> "done".equals(e.event()))
      .verifyComplete();

    assertThat(tickerCancelled).isTrue();
  }

  @Test
  void defaultKeepaliveIntervalCompletesWithStream() {
    StepVerifier.withVirtualTime(() ->
      ChatController.withKeepalive(
        Mono.just(ChatEvents.done()).delayElement(Duration.ofSeconds(35)).flux(),
        Duration.ofSeconds(10)
      )
    )
      .thenAwait(Duration.ofSeconds(35))
      .expectNextCount(3)
      .expectNextMatches(e -> "done".equals(e.event()))
      .verifyComplete();
  }

  private static boolean isKeepalive(ServerSentEvent<String> event) {
    return "keepalive".equals(event.comment()) && event.data() == null;
  }
}
