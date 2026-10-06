package com.amazon.sample.assistant.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Proveedor de chat falso con la API de OpenAI ({@code /v1/chat/completions}),
 * sobre el {@link HttpServer} del JDK. Guarda el cuerpo de cada request y
 * responde un JSON (sin streaming) o un stream SSE con el texto configurado, o
 * el status de error configurado.
 */
public class FakeChatProvider implements AutoCloseable {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpServer server;
  private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
  private volatile int errorStatus;
  private volatile String retryAfter;
  private volatile String text = "ok";
  private volatile List<String> fragments = List.of("Hello", " there");
  private volatile Duration streamDelay = Duration.ZERO;

  public FakeChatProvider() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/", this::handle);
    // Un hilo por request: una respuesta demorada no frena a las demás.
    server.setExecutor(Executors.newCachedThreadPool(task -> {
      Thread thread = new Thread(task, "fake-chat-provider");
      thread.setDaemon(true);
      return thread;
    }));
    server.start();
  }

  public String baseUrl() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  public List<JsonNode> requests() {
    return requests;
  }

  public void reset() {
    requests.clear();
    errorStatus = 0;
    retryAfter = null;
    text = "ok";
    fragments = List.of("Hello", " there");
    streamDelay = Duration.ZERO;
  }

  /** Responde con este status de error a todos los requests. */
  public void failWith(int status, String retryAfterHeader) {
    this.errorStatus = status;
    this.retryAfter = retryAfterHeader;
  }

  /** Texto de la respuesta sin streaming. */
  public void respondText(String text) {
    this.text = text;
  }

  /** Fragmentos de la respuesta en streaming. */
  public void respondFragments(List<String> fragments) {
    this.fragments = List.copyOf(fragments);
  }

  /**
   * Demora de las respuestas en streaming antes de mandar algo, como el modelo
   * cuando razona. Vale para los requests que lleguen después de llamarlo.
   */
  public void delayStreams(Duration delay) {
    this.streamDelay = delay;
  }

  private void handle(HttpExchange exchange) throws IOException {
    JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
    requests.add(body);
    Duration delay = streamDelay;
    if (body.path("stream").asBoolean(false) && !delay.isZero()) {
      try {
        Thread.sleep(delay.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (errorStatus != 0) {
      if (retryAfter != null) {
        exchange.getResponseHeaders().add("Retry-After", retryAfter);
      }
      send(exchange, errorStatus, "application/json",
          "{\"status\":" + errorStatus + ",\"title\":\"error\"}");
      return;
    }
    if (body.path("stream").asBoolean(false)) {
      StringBuilder sse = new StringBuilder();
      for (String fragment : fragments) {
        sse.append("data: ").append(chunk(fragment, null)).append("\n\n");
      }
      sse.append("data: ").append(chunk("", "stop")).append("\n\n");
      sse.append("data: [DONE]\n\n");
      send(exchange, 200, "text/event-stream", sse.toString());
    } else {
      ObjectNode response = JSON.createObjectNode();
      response.put("id", "fake").put("object", "chat.completion").put("created", 1)
          .put("model", body.path("model").asText());
      ObjectNode choice = response.putArray("choices").addObject();
      choice.put("index", 0).put("finish_reason", "stop");
      choice.putObject("message").put("role", "assistant").put("content", text);
      response.putObject("usage").put("prompt_tokens", 1).put("completion_tokens", 1)
          .put("total_tokens", 2);
      send(exchange, 200, "application/json", response.toString());
    }
  }

  private static String chunk(String content, String finishReason) {
    ObjectNode chunk = JSON.createObjectNode();
    chunk.put("id", "fake").put("object", "chat.completion.chunk").put("created", 1)
        .put("model", "fake");
    ObjectNode choice = chunk.putArray("choices").addObject();
    choice.put("index", 0);
    choice.putObject("delta").put("role", "assistant").put("content", content);
    if (finishReason == null) {
      choice.putNull("finish_reason");
    } else {
      choice.put("finish_reason", finishReason);
    }
    return chunk.toString();
  }

  private static void send(HttpExchange exchange, int status, String contentType, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
