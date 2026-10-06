package com.amazon.sample.ui.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

class AssistantChatStreamServiceTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String SESSION = "0123456789abcdef";

  private MockWebServer server;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  void sendsOnlySessionHeaderAndMessageBody() throws Exception {
    server.enqueue(sse("event:done\ndata:{}\n\n"));

    StepVerifier.create(service().stream(SESSION, "I need a lamp"))
      .expectNextMatches(e -> "done".equals(e.event()))
      .verifyComplete();

    var request = server.takeRequest(1, TimeUnit.SECONDS);
    assertThat(request.getMethod()).isEqualTo("POST");
    assertThat(request.getPath()).isEqualTo("/assistant/chat");
    assertThat(request.getHeaders().values("X-Session-ID")).containsExactly(
      SESSION
    );
    assertThat(request.getHeader("Accept")).isEqualTo("text/event-stream");
    assertThat(request.getHeader("Content-Type")).startsWith("application/json");

    var body = MAPPER.readTree(request.getBody().readUtf8());
    assertThat(body.size()).isEqualTo(1);
    assertThat(body.get("message").asText()).isEqualTo("I need a lamp");
  }

  @Test
  void forwardsEventsUnchangedAndInOrder() {
    var products = "{\"products\":[{\"id\":\"p1\",\"name\":\"Lamp\"}]}";
    var tool = "{\"tool\":\"addToCart\",\"ok\":true}";
    var text1 = "{\"text\":\"Line one\\nline two\"}";
    var text2 = "{\"text\":\" and more\"}";
    var cart =
      "{\"itemId\":\"p1\",\"name\":\"Lamp\",\"quantity\":2,\"unitPrice\":49,\"cartItemCount\":3}";

    server.enqueue(
      sse(
        ":keepalive\n\n" +
        "event:products\ndata:" + products + "\n\n" +
        "event:tool\ndata:" + tool + "\n\n" +
        "data:" + text1 + "\n\n" +
        ":keepalive\n\n" +
        "data:" + text2 + "\n\n" +
        "event:cart-updated\ndata:" + cart + "\n\n" +
        "event:done\ndata:{}\n\n"
      )
    );

    StepVerifier.create(service().stream(SESSION, "hi"))
      .expectNextMatches(e -> is(e, "products", products))
      .expectNextMatches(e -> is(e, "tool", tool))
      .expectNextMatches(e -> is(e, null, text1))
      .expectNextMatches(e -> is(e, null, text2))
      .expectNextMatches(e -> is(e, "cart-updated", cart))
      .expectNextMatches(e -> is(e, "done", "{}"))
      .verifyComplete();
  }

  @Test
  void eventsSplitAcrossTcpChunksArriveWhole() {
    var text = "{\"text\":\"A fragment that will be split in several chunks\"}";
    var body =
      "data:" + text + "\n\n" + "event:tool\ndata:{\"tool\":\"x\"}\n\n" +
      "event:done\ndata:{}\n\n";

    server.enqueue(
      new MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setChunkedBody(body, 5)
        .throttleBody(5, 5, TimeUnit.MILLISECONDS)
    );

    StepVerifier.create(service().stream(SESSION, "hi"))
      .expectNextMatches(e -> is(e, null, text))
      .expectNextMatches(e -> is(e, "tool", "{\"tool\":\"x\"}"))
      .expectNextMatches(e -> is(e, "done", "{}"))
      .verifyComplete();
  }

  @Test
  void badRequestBecomesInvalidParameterWithDetail() {
    server.enqueue(
      new MockResponse()
        .setResponseCode(400)
        .setHeader("Content-Type", "application/problem+json")
        .setBody(
          "{\"type\":\"invalid-parameter\",\"title\":\"Bad Request\",\"status\":400,\"detail\":\"message is too long\"}"
        )
    );

    StepVerifier.create(service().stream(SESSION, "hi"))
      .expectNextMatches(e ->
        isError(e, "invalid-parameter") && e.data().contains("message is too long")
      )
      .verifyComplete();
  }

  @Test
  void conflictBecomesSessionBusy() {
    server.enqueue(
      new MockResponse()
        .setResponseCode(409)
        .setHeader("Content-Type", "application/problem+json")
        .setBody("{\"type\":\"session-busy\",\"status\":409}")
    );

    StepVerifier.create(service().stream(SESSION, "hi"))
      .expectNextMatches(e -> isError(e, "session-busy"))
      .verifyComplete();
  }

  @Test
  void otherStatusBecomesAssistantUnavailable() {
    server.enqueue(new MockResponse().setResponseCode(503).setBody("down"));

    StepVerifier.create(service().stream(SESSION, "hi"))
      .expectNextMatches(e -> isError(e, "assistant-unavailable"))
      .verifyComplete();
  }

  @Test
  void connectionRefusedBecomesAssistantUnavailable() throws IOException {
    int port;
    try (var socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }

    StepVerifier.create(
      service("http://localhost:" + port, Duration.ofSeconds(5)).stream(
        SESSION,
        "hi"
      )
    )
      .expectNextMatches(e -> isError(e, "assistant-unavailable"))
      .expectComplete()
      .verify(Duration.ofSeconds(3));
  }

  @Test
  void connectTimeoutBecomesAssistantUnavailable() {
    // 10.255.255.1 no responde al SYN: la conexión no se abre nunca.
    var client = webClient(300);
    var service = new AssistantChatStreamService(
      client,
      "http://10.255.255.1:81",
      Duration.ofSeconds(30)
    );

    StepVerifier.create(service.stream(SESSION, "hi"))
      .expectNextMatches(e -> isError(e, "assistant-unavailable"))
      .expectComplete()
      .verify(Duration.ofSeconds(5));
  }

  @Test
  void streamEndingWithoutFinalEventAppendsAssistantUnavailable() {
    server.enqueue(sse("data:{\"text\":\"partial\"}\n\n"));

    StepVerifier.create(service().stream(SESSION, "hi"))
      .expectNextMatches(e -> is(e, null, "{\"text\":\"partial\"}"))
      .expectNextMatches(e -> isError(e, "assistant-unavailable"))
      .verifyComplete();
  }

  @Test
  void streamCutMidwayAppendsAssistantUnavailable() {
    var body = new StringBuilder("data:{\"text\":\"first\"}\n\n");
    for (int i = 0; i < 2000; i++) {
      body.append("data:{\"text\":\"more text to fill the socket\"}\n\n");
    }
    server.enqueue(
      sse(body.toString()).setSocketPolicy(
        SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY
      )
    );

    StepVerifier.create(service().stream(SESSION, "hi"))
      .thenConsumeWhile(e -> e.event() == null)
      .expectNextMatches(e -> isError(e, "assistant-unavailable"))
      .expectComplete()
      .verify(Duration.ofSeconds(5));
  }

  @Test
  void chatTimeoutCancelsUpstreamAndEmitsAssistantUnavailable() {
    server.enqueue(
      sse("data:{\"text\":\"slow\"}\n\nevent:done\ndata:{}\n\n").setBodyDelay(
        2,
        TimeUnit.SECONDS
      )
    );

    StepVerifier.create(
      service(server.url("/").toString(), Duration.ofMillis(500)).stream(
        SESSION,
        "hi"
      )
    )
      .expectNextMatches(e -> isError(e, "assistant-unavailable"))
      .expectComplete()
      .verify(Duration.ofSeconds(3));
  }

  @Test
  void assistantErrorIsForwardedUnchanged() {
    var error =
      "{\"type\":\"llm-quota-exceeded\",\"detail\":\"quota\",\"retryAfterSeconds\":20}";
    server.enqueue(
      sse(
        "data:{\"text\":\"hi\"}\n\nevent:error\ndata:" + error + "\n\n" +
        "data:{\"text\":\"ignored\"}\n\n"
      )
    );

    StepVerifier.create(service().stream(SESSION, "hi"))
      .expectNextMatches(e -> is(e, null, "{\"text\":\"hi\"}"))
      .expectNextMatches(e -> is(e, "error", error))
      .verifyComplete();
  }

  @Test
  void logsOneLinePerTurnWithoutTheMessage() {
    var logged = new CopyOnWriteArrayList<String>();
    var appender = new AbstractAppender(
      "memory",
      null,
      null,
      true,
      Property.EMPTY_ARRAY
    ) {
      @Override
      public void append(LogEvent event) {
        logged.add(event.getMessage().getFormattedMessage());
      }
    };
    appender.start();
    var logger = (Logger) LogManager.getLogger(AssistantChatStreamService.class);
    var previousLevel = logger.getLevel();
    Configurator.setLevel(logger.getName(), Level.INFO);
    logger.addAppender(appender);

    try {
      server.enqueue(sse("data:{\"text\":\"ok\"}\n\nevent:done\ndata:{}\n\n"));
      server.enqueue(new MockResponse().setResponseCode(409));

      StepVerifier.create(service().stream(SESSION, "secret lamp question"))
        .expectNextCount(2)
        .verifyComplete();
      StepVerifier.create(service().stream(SESSION, "another secret"))
        .expectNextCount(1)
        .verifyComplete();
      // La línea se escribe en doFinally, después de que el suscriptor recibe onComplete.
      await().atMost(Duration.ofSeconds(2)).until(() -> logged.size() == 2);
    } finally {
      logger.removeAppender(appender);
      Configurator.setLevel(logger.getName(), previousLevel);
      appender.stop();
    }

    assertThat(logged).hasSize(2);
    assertThat(logged.get(0))
      .contains("session=01234567 ")
      .contains("upstream=200")
      .contains("final=done")
      .contains("durationMs=")
      .doesNotContain(SESSION);
    assertThat(logged.get(1))
      .contains("upstream=409")
      .contains("final=error:session-busy");
    assertThat(logged).noneMatch(l -> l.contains("secret"));
  }

  private AssistantChatStreamService service() {
    return service(server.url("/").toString(), Duration.ofSeconds(10));
  }

  private static AssistantChatStreamService service(
    String endpoint,
    Duration chatTimeout
  ) {
    return new AssistantChatStreamService(webClient(2000), endpoint, chatTimeout);
  }

  private static WebClient webClient(int connectTimeoutMillis) {
    var httpClient = HttpClient.create()
      .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis);
    return WebClient.builder()
      .clientConnector(new ReactorClientHttpConnector(httpClient))
      .build();
  }

  private static MockResponse sse(String body) {
    return new MockResponse()
      .setHeader("Content-Type", "text/event-stream")
      .setBody(body);
  }

  private static boolean is(
    ServerSentEvent<String> event,
    String name,
    String data
  ) {
    assertThat(event.event()).isEqualTo(name);
    assertThat(event.data()).isEqualTo(data);
    assertThat(event.comment()).isNull();
    return true;
  }

  private static boolean isError(ServerSentEvent<String> event, String type) {
    assertThat(event.event()).isEqualTo("error");
    try {
      assertThat(MAPPER.readTree(event.data()).get("type").asText()).isEqualTo(
        type
      );
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    return true;
  }
}
