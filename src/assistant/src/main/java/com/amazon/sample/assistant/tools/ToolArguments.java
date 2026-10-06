package com.amazon.sample.assistant.tools;

import com.amazon.sample.assistant.chat.rewrite.CatalogTagsCache;
import com.amazon.sample.assistant.config.ToolsProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validación de los argumentos de las tools en el servidor, antes de llamar a
 * cualquier servicio (D3 de {@code add-assistant-tools}). Cada método devuelve
 * el valor normalizado o lanza {@link ToolError.Failure} con el resultado de
 * error para el modelo.
 */
public class ToolArguments {

  /** Largo máximo de {@code query}: el mismo que la consulta reescrita del chat. */
  static final int MAX_QUERY_LENGTH = 200;

  static final Set<String> ORDERS = Set.of("relevance", "price_asc", "price_desc");

  private static final Pattern CANONICAL_UUID = Pattern.compile(
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private final CatalogTagsCache tags;
  private final ToolsProperties properties;

  public ToolArguments(CatalogTagsCache tags, ToolsProperties properties) {
    this.tags = tags;
    this.properties = properties;
  }

  /** {@code query} sin espacios de más, o {@code null} si vino vacía. */
  String query(String query) {
    if (query == null || query.isBlank()) {
      return null;
    }
    String trimmed = query.trim();
    if (trimmed.length() > MAX_QUERY_LENGTH) {
      throw new ToolError.Failure(ToolError.invalidArgument("query",
          "query must have at most " + MAX_QUERY_LENGTH + " characters"));
    }
    return trimmed;
  }

  /**
   * {@code tags} en minúsculas, sin repetidos y existentes en el catálogo. Si
   * la lista de tags no se puede leer, {@code catalog-unavailable}.
   */
  List<String> tags(List<String> requested) {
    if (requested == null || requested.isEmpty()) {
      return List.of();
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (String tag : requested) {
      if (tag != null && !tag.isBlank()) {
        normalized.add(tag.trim().toLowerCase(Locale.ROOT));
      }
    }
    if (normalized.isEmpty()) {
      return List.of();
    }
    List<String> valid = tags.tagNames();
    if (valid.isEmpty()) {
      throw new ToolError.Failure(ToolError.of(ToolError.CATALOG_UNAVAILABLE,
          "The catalog tags cannot be read right now"));
    }
    List<String> unknown = normalized.stream().filter(tag -> !valid.contains(tag)).toList();
    if (!unknown.isEmpty()) {
      throw new ToolError.Failure(ToolError.invalidTags(
          "Unknown tags: " + String.join(", ", unknown) + ". Use only tags from validTags",
          valid));
    }
    return new ArrayList<>(normalized);
  }

  /** Precio mínimo y máximo: enteros no negativos con {@code minPrice <= maxPrice}. */
  void prices(Integer minPrice, Integer maxPrice) {
    if (minPrice != null && minPrice < 0) {
      throw new ToolError.Failure(ToolError.invalidArgument("minPrice",
          "minPrice must be a non-negative integer"));
    }
    if (maxPrice != null && maxPrice < 0) {
      throw new ToolError.Failure(ToolError.invalidArgument("maxPrice",
          "maxPrice must be a non-negative integer"));
    }
    if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
      throw new ToolError.Failure(ToolError.invalidArgument("minPrice",
          "minPrice cannot be greater than maxPrice"));
    }
  }

  /** {@code order}: {@code relevance} (por defecto), {@code price_asc} o {@code price_desc}. */
  String order(String order) {
    if (order == null || order.isBlank()) {
      return "relevance";
    }
    String normalized = order.trim().toLowerCase(Locale.ROOT);
    if (!ORDERS.contains(normalized)) {
      throw new ToolError.Failure(ToolError.invalidArgument("order",
          "order must be one of relevance, price_asc, price_desc"));
    }
    return normalized;
  }

  /** {@code limit} entre 1 y {@code search-max-limit}; por defecto {@code search-default-limit}. */
  int limit(Integer limit) {
    if (limit == null) {
      return Math.min(properties.searchDefaultLimit(), properties.searchMaxLimit());
    }
    if (limit < 1 || limit > properties.searchMaxLimit()) {
      throw new ToolError.Failure(ToolError.invalidArgument("limit",
          "limit must be between 1 and " + properties.searchMaxLimit()));
    }
    return limit;
  }

  /** {@code productId} con formato de UUID canónico. */
  String productId(String productId) {
    if (productId == null || !CANONICAL_UUID.matcher(productId.trim()).matches()) {
      throw new ToolError.Failure(ToolError.invalidArgument("productId",
          "productId must be a product id (UUID) taken from the product context or a tool "
              + "result. If you do not have it, call searchProducts with the product name first"));
    }
    String trimmed = productId.trim().toLowerCase(Locale.ROOT);
    try {
      UUID.fromString(trimmed);
    } catch (IllegalArgumentException e) {
      throw new ToolError.Failure(ToolError.invalidArgument("productId",
          "productId must be a product id (UUID)"));
    }
    return trimmed;
  }

  /** {@code quantity} entre 1 y {@code max-quantity}; por defecto 1. */
  int quantity(Integer quantity) {
    if (quantity == null) {
      return 1;
    }
    if (quantity < 1 || quantity > properties.maxQuantity()) {
      throw new ToolError.Failure(ToolError.invalidArgument("quantity",
          "quantity must be between 1 and " + properties.maxQuantity()));
    }
    return quantity;
  }
}
