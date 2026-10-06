package com.amazon.sample.assistant.smoke;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Eventos SSE de una respuesta completa de {@code POST /assistant/chat}. */
record SseEvents(List<Map<String, Object>> products, String text, boolean done,
    Map<String, Object> error, String raw) {

  private static final ObjectMapper JSON = new ObjectMapper();

  static SseEvents parse(String body) {
    List<Map<String, Object>> products = null;
    StringBuilder text = new StringBuilder();
    boolean done = false;
    Map<String, Object> error = null;
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
        error, body);
  }

  List<String> productIds() {
    return products.stream().map(p -> (String) p.get("id")).toList();
  }

  List<String> productNames() {
    return products.stream().map(p -> (String) p.get("name")).toList();
  }
}
