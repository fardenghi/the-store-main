package com.amazon.sample.assistant.chat.session;

import com.amazon.sample.assistant.products.search.ProductResult;
import java.util.List;

/** Snapshot de un producto que el asistente mostró en un turno (D6). */
public record ShownProduct(
    String id,
    String name,
    String description,
    long price,
    List<String> tags) {

  public ShownProduct {
    tags = tags == null ? List.of() : List.copyOf(tags);
  }

  public static ShownProduct from(ProductResult result) {
    return new ShownProduct(result.id(), result.name(), result.description(), result.price(),
        result.tags());
  }
}
