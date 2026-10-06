package com.amazon.sample.assistant.smoke;

import com.amazon.sample.assistant.products.CatalogFixtures;
import com.amazon.sample.assistant.products.catalog.CatalogProduct;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * El catálogo real ({@code src/catalog/repository}) servido por HTTP como lo
 * expone {@code catalog}: {@code GET /catalog/products?page&size},
 * {@code GET /catalog/products/{id}} y {@code GET /catalog/tags}. Los smoke de
 * chat lo usan en lugar de un {@code MockRestServiceServer}, porque el
 * {@code RestClient.Builder} autoconfigurado también lo usa el cliente de
 * NVIDIA y no se puede atar solo al del catálogo.
 */
public final class FakeCatalog implements AutoCloseable {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpServer server;
  private final List<CatalogProduct> products = CatalogFixtures.realCatalog();

  public FakeCatalog() {
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/catalog/", this::handle);
    server.start();
  }

  public String baseUrl() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  public List<CatalogProduct> products() {
    return products;
  }

  public Optional<CatalogProduct> byId(String id) {
    return products.stream().filter(p -> p.id().equals(id)).findFirst();
  }

  public Optional<CatalogProduct> byName(String name) {
    return products.stream().filter(p -> p.name().equals(name)).findFirst();
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
    if (path.equals("/catalog/tags")) {
      Map<String, CatalogProduct.Tag> tags = new LinkedHashMap<>();
      products.forEach(p -> p.tags().forEach(t -> tags.putIfAbsent(t.name(), t)));
      send(exchange, 200, tags.values());
    } else if (path.equals("/catalog/products")) {
      int page = Integer.parseInt(query.getOrDefault("page", "1"));
      int size = Integer.parseInt(query.getOrDefault("size", "10"));
      int from = Math.min(products.size(), (page - 1) * size);
      send(exchange, 200, products.subList(from, Math.min(products.size(), from + size)));
    } else if (path.startsWith("/catalog/products/")) {
      Optional<CatalogProduct> product = byId(path.substring("/catalog/products/".length()));
      if (product.isPresent()) {
        send(exchange, 200, product.get());
      } else {
        send(exchange, 404, Map.of("error", "not found"));
      }
    } else {
      send(exchange, 404, Map.of("error", "not found"));
    }
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> values = new LinkedHashMap<>();
    if (raw != null) {
      for (String pair : raw.split("&")) {
        String[] kv = pair.split("=", 2);
        values.put(kv[0], kv.length > 1 ? kv[1] : "");
      }
    }
    return values;
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
