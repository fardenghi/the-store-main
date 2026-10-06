package com.amazon.sample.assistant.products.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryEmbeddingCacheTest {

  private final List<String> calls = new ArrayList<>();

  private float[] embed(String query) {
    calls.add(query);
    return new float[] {calls.size()};
  }

  @Test
  void normalizesCaseAndSpaces() {
    assertThat(QueryEmbeddingCache.normalize("  Mid   Century\tChair ")).isEqualTo("mid century chair");
  }

  @Test
  void equalQueriesHitTheCache() {
    QueryEmbeddingCache cache = new QueryEmbeddingCache(2);

    cache.get("Couch", this::embed);
    cache.get(" couch ", this::embed);

    assertThat(calls).containsExactly("couch");
  }

  @Test
  void evictsLeastRecentlyUsed() {
    QueryEmbeddingCache cache = new QueryEmbeddingCache(2);

    cache.get("a", this::embed);
    cache.get("b", this::embed);
    cache.get("a", this::embed); // "a" pasa a ser el más reciente
    cache.get("c", this::embed); // desaloja "b"
    cache.get("a", this::embed);
    cache.get("b", this::embed);

    assertThat(calls).containsExactly("a", "b", "c", "b");
    assertThat(cache.size()).isEqualTo(2);
  }
}
