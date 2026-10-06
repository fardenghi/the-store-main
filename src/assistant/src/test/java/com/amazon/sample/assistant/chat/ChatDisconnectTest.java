package com.amazon.sample.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.amazon.sample.assistant.chat.session.SessionStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Corte del cliente con Tomcat real (corrección posterior de D2 de
 * {@code add-assistant-chat}): mientras el modelo "razona" sin mandar nada, el
 * cliente cierra la conexión y la misma sesión vuelve a escribir enseguida,
 * como cuando se cierra una pestaña y se escribe desde otra. El segundo turno
 * tiene que entrar (no {@code 409}) y el turno cortado no puede quedar en la
 * memoria aunque su llamada al modelo termine después.
 *
 * <p>Sin Qdrant ni catálogo: la reescritura cae al mensaje crudo y el turno
 * sigue con el catálogo no disponible, como en {@link ChatWithoutKeyTest}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "spring.ai.vectorstore.qdrant.port=1",
    "retail.assistant.endpoints.catalog=http://localhost:1",
    "retail.assistant.indexing.sync-on-startup=false",
    "retail.assistant.chat.compare-raw-retrieval=false"
})
class ChatDisconnectTest {

  /** Lo que tarda el modelo en mandar el primer fragmento del turno cortado. */
  private static final Duration THINKING = Duration.ofSeconds(5);

  static final FakeChatProvider PROVIDER;

  static {
    try {
      PROVIDER = new FakeChatProvider();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void provider(DynamicPropertyRegistry registry) {
    registry.add("spring.ai.openai.base-url", PROVIDER::baseUrl);
  }

  @AfterAll
  static void stop() {
    PROVIDER.close();
  }

  @LocalServerPort
  private int port;

  @Autowired
  private TestRestTemplate rest;

  @Autowired
  private SessionStore sessions;

  @AfterEach
  void reset() {
    PROVIDER.reset();
  }

  /**
   * @param readEverything si el cliente lee todo lo recibido antes de cerrar, como
   *     la {@code ui} (cierre con FIN: la primera escritura del servidor no falla),
   *     o cierra con datos sin leer (el sistema operativo manda un reset)
   */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void anotherTabCanWriteRightAfterTheClientDisconnects(boolean readEverything)
      throws Exception {
    String session = readEverything ? "tab-closed-fin" : "tab-closed-rst";
    PROVIDER.delayStreams(THINKING);
    try (Socket client = new Socket("localhost", port)) {
      OutputStream out = client.getOutputStream();
      out.write(request(session, "compare the two sofas in detail"));
      out.flush();
      InputStream in = client.getInputStream();
      readHeaders(in);
      // La reescritura y el stream del modelo principal ya llegaron al proveedor.
      await().atMost(Duration.ofSeconds(5)).until(() -> PROVIDER.requests().size() == 2);
      if (readEverything) {
        readUntil(in, "event:products");
        Thread.sleep(100);
        in.skip(in.available());
      }
    }
    PROVIDER.delayStreams(Duration.ZERO);
    long closed = System.nanoTime();

    Thread.sleep(300);
    ResponseEntity<String> second = chat(session, "hello again");

    long waitedMillis = (System.nanoTime() - closed) / 1_000_000;
    assertThat(second.getStatusCode()).as("segundo turno a los 300 ms del corte")
        .isEqualTo(HttpStatus.OK);
    assertThat(second.getBody()).contains("event:done");
    assertThat(waitedMillis).as("el segundo turno no esperó a que el modelo terminara")
        .isLessThan(THINKING.toMillis());

    // Cuando la llamada del turno cortado termina, no escribe en la memoria.
    Thread.sleep(THINKING.toMillis());
    assertThat(sessions.get(session).turns()).singleElement()
        .satisfies(turn -> assertThat(turn.user()).isEqualTo("hello again"));
    assertThat(sessions.get(session).isBusy()).isFalse();
  }

  @Test
  void aSecondTabWhileTheFirstIsStillConnectedIsStillBusy() throws Exception {
    PROVIDER.delayStreams(Duration.ofSeconds(3));
    try (Socket client = new Socket("localhost", port)) {
      OutputStream out = client.getOutputStream();
      out.write(request("tab-open", "compare the two sofas in detail"));
      out.flush();
      InputStream in = client.getInputStream();
      readHeaders(in);
      await().atMost(Duration.ofSeconds(5)).until(() -> PROVIDER.requests().size() == 2);

      ResponseEntity<String> second = chat("tab-open", "hello again");

      assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      assertThat(second.getBody()).contains("session-busy");
      // El primer turno sigue vivo y termina bien.
      String rest = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertThat(rest).contains("event:done");
    }
    assertThat(sessions.get("tab-open").turns()).singleElement()
        .satisfies(turn -> assertThat(turn.user()).isEqualTo("compare the two sofas in detail"));
  }

  private ResponseEntity<String> chat(String session, String message) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
    headers.add("X-Session-ID", session);
    return rest.postForEntity("/assistant/chat",
        new HttpEntity<>("{\"message\":\"" + message + "\"}", headers), String.class);
  }

  private byte[] request(String session, String message) {
    String body = "{\"message\":\"" + message + "\"}";
    return ("POST /assistant/chat HTTP/1.1\r\n"
        + "Host: localhost:" + port + "\r\n"
        + "Content-Type: application/json\r\n"
        + "Accept: text/event-stream\r\n"
        + "X-Session-ID: " + session + "\r\n"
        + "Connection: close\r\n"
        + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
        + "\r\n" + body).getBytes(StandardCharsets.UTF_8);
  }

  private static void readUntil(InputStream in, String marker) throws IOException {
    StringBuilder read = new StringBuilder();
    while (!read.toString().contains(marker)) {
      int b = in.read();
      if (b < 0) {
        throw new IOException("La conexión se cerró antes de " + marker + ": " + read);
      }
      read.append((char) b);
    }
  }

  /** Lee hasta el fin de los headers de la respuesta y verifica el 200. */
  private static void readHeaders(InputStream in) throws IOException {
    StringBuilder headers = new StringBuilder();
    while (!headers.toString().endsWith("\r\n\r\n")) {
      int b = in.read();
      if (b < 0) {
        throw new IOException("La conexión se cerró antes de los headers: " + headers);
      }
      headers.append((char) b);
    }
    assertThat(headers.toString()).startsWith("HTTP/1.1 200");
  }
}
