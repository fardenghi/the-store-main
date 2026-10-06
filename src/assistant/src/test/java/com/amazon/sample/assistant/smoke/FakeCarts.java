package com.amazon.sample.assistant.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * El servicio {@code carts} en memoria, como lo usa la tool {@code addToCart}:
 * {@code POST /carts/{customerId}/items} agrega una línea (igual que el
 * proveedor {@code in-memory}) y {@code GET /carts/{customerId}} devuelve el
 * carrito. Con {@link #down(boolean)} responde 500 a todo.
 */
public final class FakeCarts implements AutoCloseable {

  /** Línea del carrito. */
  public record Item(String itemId, int quantity, int unitPrice) {
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpServer server;
  private final Map<String, List<Item>> carts = new ConcurrentHashMap<>();
  private volatile boolean down;

  public FakeCarts() {
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/carts/", this::handle);
    server.start();
  }

  public String baseUrl() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  public List<Item> items(String customerId) {
    return List.copyOf(carts.getOrDefault(customerId, List.of()));
  }

  public void down(boolean down) {
    this.down = down;
  }

  private void handle(HttpExchange exchange) throws IOException {
    if (down) {
      send(exchange, 500, Map.of("error", "carts down"));
      return;
    }
    String[] parts = exchange.getRequestURI().getPath().split("/");
    // /carts/{customerId} o /carts/{customerId}/items
    String customerId = parts.length > 2 ? parts[2] : "";
    if ("POST".equals(exchange.getRequestMethod()) && parts.length == 4
        && "items".equals(parts[3])) {
      JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
      Item item = new Item(body.path("itemId").asText(), body.path("quantity").asInt(),
          body.path("unitPrice").asInt());
      carts.computeIfAbsent(customerId, id -> new CopyOnWriteArrayList<>()).add(item);
      send(exchange, 201, item);
    } else if ("GET".equals(exchange.getRequestMethod()) && parts.length == 3) {
      send(exchange, 200, Map.of("customerId", customerId,
          "items", new ArrayList<>(items(customerId))));
    } else {
      send(exchange, 404, Map.of("error", "not found"));
    }
  }

  private static void send(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(body);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
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
