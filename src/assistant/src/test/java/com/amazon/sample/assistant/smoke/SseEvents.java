package com.amazon.sample.assistant.smoke;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Eventos SSE de una respuesta completa de {@code POST /assistant/chat},
 * incluidos los {@code tool} y {@code cart-updated} de {@code add-assistant-tools}.
 */
record SseEvents(List<Map<String, Object>> products, String text, boolean done,
    Map<String, Object> error, String raw, List<Map<String, Object>> tools,
    List<Map<String, Object>> cartUpdates) {

  private static final ObjectMapper JSON = new ObjectMapper();

  static SseEvents parse(String body) {
    List<Map<String, Object>> products = null;
    StringBuilder text = new StringBuilder();
    boolean done = false;
    Map<String, Object> error = null;
    List<Map<String, Object>> tools = new ArrayList<>();
    List<Map<String, Object>> cartUpdates = new ArrayList<>();
    try {
      for (String block : body.split("\n\n")) {
        String event = null;
        StringBuilder data = new StringBuilder();
        for (String line : block.split("\n")) {
          if (line.startsWith("event:")) {
            event = line.substring(6).trim();
          } else if (line.startsWith("data:")) {
            data.append(line.substring(5));
          }
        }
        if (data.isEmpty()) {
          continue;
        }
        if ("products".equals(event)) {
          products = JSON.readValue(data.toString(), new TypeReference<>() { });
        } else if ("done".equals(event)) {
          done = true;
        } else if ("tool".equals(event)) {
          tools.add(JSON.readValue(data.toString(), new TypeReference<>() { }));
        } else if ("cart-updated".equals(event)) {
          cartUpdates.add(JSON.readValue(data.toString(), new TypeReference<>() { }));
        } else if ("error".equals(event)) {
          error = JSON.readValue(data.toString(), new TypeReference<>() { });
        } else if (event == null) {
          Map<String, Object> fragment = JSON.readValue(data.toString(), new TypeReference<>() { });
          text.append(fragment.get("text"));
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("SSE inválido: " + body, e);
    }
    return new SseEvents(products == null ? new ArrayList<>() : products, text.toString(), done,
        error, body, tools, cartUpdates);
  }

  /**
   * Productos del evento {@code products} y de los eventos {@code tool}, sin
   * repetidos: los que el turno puede nombrar (spec "Nombres verificables").
   */
  @SuppressWarnings("unchecked")
  List<Map<String, Object>> allProducts() {
    Map<Object, Map<String, Object>> all = new java.util.LinkedHashMap<>();
    products.forEach(p -> all.putIfAbsent(p.get("id"), p));
    for (Map<String, Object> tool : tools) {
      Object listed = tool.get("products");
      if (listed instanceof List<?> list) {
        list.forEach(p -> all.putIfAbsent(((Map<String, Object>) p).get("id"),
            (Map<String, Object>) p));
      }
    }
    return new ArrayList<>(all.values());
  }

  /** Eventos {@code tool} de una tool. */
  List<Map<String, Object>> toolEvents(String tool) {
    return tools.stream().filter(t -> tool.equals(t.get("tool"))).toList();
  }

  List<String> productIds() {
    return products.stream().map(p -> (String) p.get("id")).toList();
  }

  List<String> productNames() {
    return products.stream().map(p -> (String) p.get("name")).toList();
  }
}
