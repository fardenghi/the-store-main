package com.amazon.sample.ui.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.ui.web.util.SessionIDUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/**
 * Si el navegador corta {@code POST /chat/submit}, la ui cierra la conexión con
 * el assistant (D3). El assistant falso es un socket crudo, en lugar de
 * MockWebServer, porque hace falta observar el EOF de la conexión: MockWebServer
 * no expone cuándo el cliente la cierra mientras escribe un cuerpo lento.
 */
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "retail.ui.chat.enabled=true", "retail.ui.chat.provider=assistant",
  }
)
class ChatCancellationTests {

  private static final SlowAssistant ASSISTANT = new SlowAssistant();

  @DynamicPropertySource
  static void assistantEndpoint(DynamicPropertyRegistry registry) {
    registry.add("retail.ui.endpoints.assistant", ASSISTANT::url);
  }

  @AfterAll
  static void stopAssistant() throws IOException {
    ASSISTANT.close();
  }

  @Autowired
  private WebTestClient webTestClient;

  @LocalServerPort
  private int port;

  @Test
  void browserCancellationClosesAssistantConnection() throws Exception {
    var closed = ASSISTANT.expect(true);

    var events = webTestClient
      .post()
      .uri("/chat/submit")
      .cookie(SessionIDUtil.COOKIE_NAME, "cancel-session")
      .contentType(MediaType.APPLICATION_JSON)
      .accept(MediaType.TEXT_EVENT_STREAM)
      .bodyValue(Map.of("message", "compare two sofas"))
      .exchange()
      .expectStatus()
      .isOk()
      .returnResult(String.class)
      .getResponseBody();

    StepVerifier.create(events)
      .expectNextMatches(data -> data.contains("first"))
      .thenCancel()
      .verify(Duration.ofSeconds(5));

    long cancelledAt = System.nanoTime();
    long closedAt = closed.get(2, TimeUnit.SECONDS);

    assertThat(Duration.ofNanos(closedAt - cancelledAt)).isLessThan(
      Duration.ofSeconds(1)
    );
  }

  @Test
  void cancellationBeforeResponseHeadersClosesAssistantConnection()
    throws Exception {
    // El assistant no manda headers hasta su primer evento: el navegador puede
    // cerrar la pestaña antes de recibir nada.
    var closed = ASSISTANT.expect(false);

    var subscription = WebClient.create("http://localhost:" + port)
      .post()
      .uri("/chat/submit")
      .cookie(SessionIDUtil.COOKIE_NAME, "cancel-before-headers")
      .contentType(MediaType.APPLICATION_JSON)
      .accept(MediaType.TEXT_EVENT_STREAM)
      .bodyValue(Map.of("message", "compare two sofas"))
      .retrieve()
      .bodyToFlux(String.class)
      .subscribe();

    ASSISTANT.requestReceived.poll(5, TimeUnit.SECONDS);
    subscription.dispose();
    long cancelledAt = System.nanoTime();
    long closedAt = closed.get(5, TimeUnit.SECONDS);

    assertThat(Duration.ofNanos(closedAt - cancelledAt)).isLessThan(
      Duration.ofSeconds(1)
    );
  }

  /** Responde un primer fragmento y espera, sin cerrar, hasta que el cliente corta. */
  static final class SlowAssistant implements AutoCloseable {

    private final ServerSocket serverSocket;

    private final BlockingQueue<Boolean> modes = new LinkedBlockingQueue<>();

    private final BlockingQueue<CompletableFuture<Long>> closures =
      new LinkedBlockingQueue<>();

    final BlockingQueue<Boolean> requestReceived = new LinkedBlockingQueue<>();

    SlowAssistant() {
      try {
        serverSocket = new ServerSocket(0);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      var thread = new Thread(this::serve, "slow-assistant");
      thread.setDaemon(true);
      thread.start();
    }

    /** La próxima conexión manda (o no) los headers y un primer fragmento. */
    CompletableFuture<Long> expect(boolean sendFirstEvent) {
      var closed = new CompletableFuture<Long>();
      modes.add(sendFirstEvent);
      closures.add(closed);
      return closed;
    }

    String url() {
      return "http://localhost:" + serverSocket.getLocalPort();
    }

    private void serve() {
      while (!serverSocket.isClosed()) {
        try {
          Socket socket = serverSocket.accept();
          boolean sendFirstEvent = modes.take();
          var closed = closures.take();
          var handler = new Thread(() -> handle(socket, sendFirstEvent, closed));
          handler.setDaemon(true);
          handler.start();
        } catch (IOException | InterruptedException e) {
          return;
        }
      }
    }

    private void handle(
      Socket socket,
      boolean sendFirstEvent,
      CompletableFuture<Long> closed
    ) {
      try (socket) {
        var in = socket.getInputStream();
        readRequest(in);
        requestReceived.add(true);

        if (sendFirstEvent) {
          OutputStream out = socket.getOutputStream();
          var first = "data:{\"text\":\"first\"}\n\n";
          var response =
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream\r\n" +
            "Transfer-Encoding: chunked\r\n\r\n" +
            Integer.toHexString(first.getBytes(StandardCharsets.UTF_8).length) +
            "\r\n" + first + "\r\n";
          out.write(response.getBytes(StandardCharsets.UTF_8));
          out.flush();
        }

        // Bloquea hasta EOF: read devuelve -1 cuando la ui cierra la conexión.
        while (in.read() != -1) {
          // nada
        }
        closed.complete(System.nanoTime());
      } catch (IOException e) {
        closed.complete(System.nanoTime());
      }
    }

    private static void readRequest(InputStream in) throws IOException {
      var headers = new ByteArrayOutputStream();
      int matched = 0;
      byte[] end = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
      while (matched < end.length) {
        int b = in.read();
        if (b == -1) {
          throw new IOException("Connection closed before the request ended");
        }
        headers.write(b);
        matched = b == end[matched] ? matched + 1 : (b == end[0] ? 1 : 0);
      }
      int length = 0;
      for (var line : headers.toString(StandardCharsets.US_ASCII).split("\r\n")) {
        if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
          length = Integer.parseInt(line.substring(15).trim());
        }
      }
      in.readNBytes(length);
    }

    @Override
    public void close() throws IOException {
      serverSocket.close();
    }
  }
}
