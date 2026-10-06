package com.amazon.sample.assistant.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Resultado de una tool reducido para la memoria de la sesión (corrección
 * posterior de D7): lo justo para que el modelo vea en los turnos siguientes
 * qué hizo cada tool, sin las descripciones ni los tags que ya usó para
 * responder.
 *
 * <ul>
 *   <li>{@code searchProducts}: {@code {"products": [{id, name, price}]}};</li>
 *   <li>{@code getProductDetails}: {@code {id, name, price}};</li>
 *   <li>{@code addToCart}: {@code {"added": {id, name, quantity, unitPrice}}}, con
 *       {@code cartItemCount} si vino;</li>
 *   <li>un error: {@code {"error": "<tipo>"}}.</li>
 * </ul>
 */
public final class CompactToolResult {

  /** Tope de un resultado que no tiene ninguna de las formas conocidas. */
  static final int MAX_UNKNOWN_CHARS = 200;

  private static final ObjectMapper JSON = new ObjectMapper();

  private CompactToolResult() {
  }

  public static String of(String result) {
    JsonNode node;
    try {
      node = JSON.readTree(result == null ? "" : result);
    } catch (JsonProcessingException e) {
      node = null;
    }
    if (node == null || !node.isObject()) {
      return truncate(result);
    }
    ObjectNode compact = JSON.createObjectNode();
    if (node.hasNonNull("error")) {
      compact.set("error", node.get("error"));
    } else if (node.has("products")) {
      ArrayNode products = compact.putArray("products");
      node.path("products").forEach(product -> products.add(product(product)));
    } else if (node.has("added")) {
      JsonNode added = node.get("added");
      ObjectNode item = compact.putObject("added");
      copy(added, item, "id", "name", "quantity", "unitPrice");
      copy(node, compact, "cartItemCount");
    } else if (node.has("id")) {
      compact = product(node);
    } else {
      return truncate(result);
    }
    return compact.toString();
  }

  private static ObjectNode product(JsonNode product) {
    ObjectNode compact = JSON.createObjectNode();
    copy(product, compact, "id", "name", "price");
    return compact;
  }

  private static void copy(JsonNode from, ObjectNode to, String... fields) {
    for (String field : fields) {
      if (from.has(field)) {
        to.set(field, from.get(field));
      }
    }
  }

  private static String truncate(String result) {
    if (result == null) {
      return "";
    }
    return result.length() <= MAX_UNKNOWN_CHARS ? result
        : result.substring(0, MAX_UNKNOWN_CHARS) + "…";
  }
}
