package com.amazon.sample.assistant.products.search;

import com.amazon.sample.assistant.config.SearchProperties;
import com.amazon.sample.assistant.products.embedding.ProductEmbedder;
import com.amazon.sample.assistant.products.vector.ProductVectorRepository;
import java.util.List;

/**
 * Búsqueda semántica de productos (D8): embebe la consulta en modo
 * {@code RETRIEVAL_QUERY} y busca en Qdrant con los filtros de tags (OR) y
 * precio (rango inclusivo), que se combinan con AND.
 */
public class ProductSearchService {

  private final ProductEmbedder embedder;
  private final ProductVectorRepository repository;
  private final SearchProperties properties;

  public ProductSearchService(ProductEmbedder embedder, ProductVectorRepository repository,
      SearchProperties properties) {
    this.embedder = embedder;
    this.repository = repository;
    this.properties = properties;
  }

  /**
   * Busca productos por significado.
   *
   * @param query texto de la consulta (obligatorio)
   * @param tags nombres de tags; vacío o {@code null} para no filtrar
   * @param minPrice precio mínimo inclusivo, o {@code null}
   * @param maxPrice precio máximo inclusivo, o {@code null}
   * @param k cantidad máxima de resultados, o {@code null} para el default
   * @throws InvalidParameterException si algún parámetro es inválido
   * @throws IndexUnavailableException si la colección no tiene puntos
   */
  public List<ProductResult> search(String query, List<String> tags, Integer minPrice,
      Integer maxPrice, Integer k) {
    if (query == null || query.isBlank()) {
      throw new InvalidParameterException("q", "El parámetro q es obligatorio");
    }
    int limit = k == null ? properties.defaultK() : k;
    if (limit < 1 || limit > properties.maxK()) {
      throw new InvalidParameterException("k",
          "k tiene que estar entre 1 y " + properties.maxK());
    }
    if (minPrice != null && minPrice < 0) {
      throw new InvalidParameterException("minPrice", "minPrice tiene que ser un entero no negativo");
    }
    if (maxPrice != null && maxPrice < 0) {
      throw new InvalidParameterException("maxPrice", "maxPrice tiene que ser un entero no negativo");
    }
    if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
      throw new InvalidParameterException("minPrice", "minPrice no puede superar a maxPrice");
    }
    if (repository.count() == 0) {
      throw new IndexUnavailableException();
    }
    float[] vector = embedder.embedQuery(query);
    return repository.search(vector, tags == null ? List.of() : tags, minPrice, maxPrice, limit)
        .stream()
        .map(ProductResult::from)
        .toList();
  }
}
