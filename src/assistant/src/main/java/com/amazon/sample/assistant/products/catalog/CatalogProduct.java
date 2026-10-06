package com.amazon.sample.assistant.products.catalog;

import java.util.List;

/** Producto tal como lo devuelve {@code GET /catalog/products}. */
public record CatalogProduct(
    String id,
    String name,
    String description,
    int price,
    List<Tag> tags) {

  public CatalogProduct {
    tags = tags == null ? List.of() : List.copyOf(tags);
  }

  /** Tag del producto: {@code name} es el identificador y {@code displayName} el texto legible. */
  public record Tag(String name, String displayName) {
  }

  public List<String> tagNames() {
    return tags.stream().map(Tag::name).toList();
  }
}
