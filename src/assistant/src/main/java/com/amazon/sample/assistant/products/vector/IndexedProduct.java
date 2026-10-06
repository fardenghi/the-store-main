package com.amazon.sample.assistant.products.vector;

import java.util.List;

/**
 * Payload de un punto de la colección {@code products} (D2). El id del punto
 * es el {@code id} (UUID) del producto.
 */
public record IndexedProduct(
    String id,
    String name,
    String description,
    long price,
    List<String> tags,
    String contentHash) {

  public IndexedProduct {
    tags = tags == null ? List.of() : List.copyOf(tags);
  }

  /** Igualdad de los datos de catálogo, sin mirar el hash. */
  public boolean sameCatalogData(IndexedProduct other) {
    return name.equals(other.name) && description.equals(other.description)
        && price == other.price && tags.equals(other.tags);
  }
}
