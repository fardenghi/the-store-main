package com.amazon.sample.assistant.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resultado de error de una tool, dirigido al modelo (D2 de
 * {@code add-assistant-tools}): {@code {"error": "<tipo>", "message": "...", ...}}.
 * Se devuelve como resultado y no como excepción, para que el modelo lo
 * explique y el turno siga.
 */
public final class ToolError {

  public static final String INVALID_ARGUMENT = "invalid-argument";
  public static final String MISSING_CRITERIA = "missing-criteria";
  public static final String PRODUCT_NOT_FOUND = "product-not-found";
  public static final String ALREADY_ADDED = "already-added-this-turn";
  public static final String BUDGET_EXHAUSTED = "tool-budget-exhausted";
  public static final String CATALOG_UNAVAILABLE = "catalog-unavailable";
  public static final String CART_UNAVAILABLE = "cart-unavailable";
  public static final String SEARCH_UNAVAILABLE = "search-unavailable";

  private ToolError() {
  }

  public static Map<String, Object> of(String type, String message) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("error", type);
    error.put("message", message);
    return error;
  }

  /** {@code invalid-argument} que nombra el argumento. */
  public static Map<String, Object> invalidArgument(String argument, String message) {
    Map<String, Object> error = of(INVALID_ARGUMENT, message);
    error.put("argument", argument);
    return error;
  }

  /** {@code invalid-argument} de {@code tags}, con la lista de tags válidos. */
  public static Map<String, Object> invalidTags(String message, List<String> validTags) {
    Map<String, Object> error = invalidArgument("tags", message);
    error.put("validTags", validTags);
    return error;
  }

  /** El tipo de error de un resultado, o {@code null} si el resultado es correcto. */
  public static String typeOf(Map<String, Object> result) {
    Object type = result.get("error");
    return type == null ? null : type.toString();
  }

  /** Error de una tool como excepción interna, que la tool convierte en resultado. */
  static final class Failure extends RuntimeException {

    private final transient Map<String, Object> result;

    Failure(Map<String, Object> result) {
      super(String.valueOf(result.get("message")), null, false, false);
      this.result = result;
    }

    Map<String, Object> result() {
      return result;
    }
  }
}
