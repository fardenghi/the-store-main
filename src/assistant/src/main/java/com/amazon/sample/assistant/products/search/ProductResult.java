package com.amazon.sample.assistant.products.search;

import com.amazon.sample.assistant.products.vector.ScoredProduct;
import java.util.List;

/**
 * Producto devuelto por la búsqueda y por los similares, armado desde el
 * payload de Qdrant (D8).
 */
public record ProductResult(
    String id,
    String name,
    String description,
    long price,
    List<String> tags,
    float score) {

  static ProductResult from(ScoredProduct scored) {
    var product = scored.product();
    return new ProductResult(product.id(), product.name(), product.description(),
        product.price(), product.tags(), scored.score());
  }
}
