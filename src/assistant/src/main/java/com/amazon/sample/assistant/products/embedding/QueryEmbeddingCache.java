package com.amazon.sample.assistant.products.embedding;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Caché LRU en memoria de los embeddings de consultas (D9). La clave es la
 * consulta normalizada: sin espacios en los extremos, en minúsculas y con los
 * espacios internos colapsados.
 */
class QueryEmbeddingCache {

  private final Map<String, float[]> entries;

  QueryEmbeddingCache(int maxSize) {
    this.entries = new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
        return size() > maxSize;
      }
    };
  }

  static String normalize(String query) {
    return query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }

  /**
   * Devuelve el vector cacheado de la consulta o lo calcula con
   * {@code embed}, que recibe la consulta normalizada. Si {@code embed} falla,
   * no se guarda nada.
   */
  float[] get(String query, Function<String, float[]> embed) {
    String key = normalize(query);
    synchronized (entries) {
      float[] cached = entries.get(key);
      if (cached != null) {
        return cached;
      }
    }
    float[] vector = embed.apply(key);
    synchronized (entries) {
      entries.put(key, vector);
    }
    return vector;
  }

  int size() {
    synchronized (entries) {
      return entries.size();
    }
  }
}
