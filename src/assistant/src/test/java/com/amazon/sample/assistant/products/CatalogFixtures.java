package com.amazon.sample.assistant.products;

import com.amazon.sample.assistant.products.catalog.CatalogProduct;
import com.amazon.sample.assistant.products.catalog.CatalogProduct.Tag;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * El catálogo real ({@code src/catalog/repository}) en el formato que devuelve
 * {@code GET /catalog/products}, para los tests de sincronización. Como la
 * API, devuelve los tags de cada producto ordenados por nombre.
 */
public final class CatalogFixtures {

  private static final Path REPOSITORY = Path.of("..", "catalog", "repository");

  private record RawProduct(String id, String name, String description, int price,
      List<String> tags) {
  }

  private CatalogFixtures() {
  }

  public static List<CatalogProduct> realCatalog() {
    try {
      ObjectMapper mapper = new ObjectMapper();
      List<Tag> tags = mapper.readValue(REPOSITORY.resolve("tags.json").toFile(),
          new TypeReference<List<Tag>>() { });
      Map<String, Tag> byName = tags.stream()
          .collect(Collectors.toMap(Tag::name, Function.identity()));
      List<RawProduct> raw = mapper.readValue(REPOSITORY.resolve("products.json").toFile(),
          new TypeReference<List<RawProduct>>() { });
      return raw.stream()
          .map(p -> new CatalogProduct(p.id(), p.name(), p.description(), p.price(),
              p.tags().stream().sorted().map(byName::get).toList()))
          .toList();
    } catch (IOException e) {
      throw new IllegalStateException("No se pudo leer el catálogo de src/catalog/repository", e);
    }
  }

  /**
   * Vector determinista de 768 dimensiones para un texto: depende solo del
   * texto, así que el mismo producto da siempre el mismo vector.
   */
  public static float[] deterministicVector(String text) {
    float[] vector = new float[768];
    java.util.Random random = new java.util.Random(text.hashCode());
    for (int i = 0; i < vector.length; i++) {
      vector[i] = (float) random.nextGaussian();
    }
    return vector;
  }
}
