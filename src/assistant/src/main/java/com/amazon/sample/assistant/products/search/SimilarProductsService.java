package com.amazon.sample.assistant.products.search;

import com.amazon.sample.assistant.config.SearchProperties;
import com.amazon.sample.assistant.products.vector.ProductVectorRepository;
import java.util.List;
import java.util.UUID;

/**
 * Productos similares a uno dado (D8), calculados en Qdrant con el vector ya
 * guardado del producto. No depende del proveedor de embeddings: funciona
 * aunque Gemini no esté disponible o no haya clave.
 */
public class SimilarProductsService {

  private final ProductVectorRepository repository;
  private final SearchProperties properties;

  public SimilarProductsService(ProductVectorRepository repository, SearchProperties properties) {
    this.repository = repository;
    this.properties = properties;
  }

  /**
   * Los {@code k} productos más parecidos, sin incluir al propio producto.
   *
   * @throws InvalidParameterException si {@code k} está fuera de rango
   * @throws IndexUnavailableException si la colección no tiene puntos
   * @throws ProductNotFoundException si el id no está indexado
   */
  public List<ProductResult> similar(String id, Integer k) {
    int limit = k == null ? properties.similarDefaultK() : k;
    if (limit < 1 || limit > properties.similarMaxK()) {
      throw new InvalidParameterException("k",
          "k tiene que estar entre 1 y " + properties.similarMaxK());
    }
    if (repository.count() == 0) {
      throw new IndexUnavailableException();
    }
    if (!isUuid(id)) {
      throw new ProductNotFoundException(id);
    }
    return repository.similar(id, limit)
        .orElseThrow(() -> new ProductNotFoundException(id))
        .stream()
        .map(ProductResult::from)
        .toList();
  }

  private static boolean isUuid(String id) {
    try {
      UUID.fromString(id);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
