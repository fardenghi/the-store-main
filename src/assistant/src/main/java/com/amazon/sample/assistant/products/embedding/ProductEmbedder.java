package com.amazon.sample.assistant.products.embedding;

import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Embeddings de productos y de consultas con {@code gemini-embedding-001}
 * (D1): {@code RETRIEVAL_DOCUMENT} al indexar y {@code RETRIEVAL_QUERY} al
 * buscar, con las dimensiones configuradas. Los errores del proveedor salen
 * como {@link EmbeddingProviderException}.
 */
public class ProductEmbedder {

  static final String DOCUMENT_TASK = "RETRIEVAL_DOCUMENT";
  static final String QUERY_TASK = "RETRIEVAL_QUERY";

  /**
   * El cliente de Google GenAI reintenta por defecto hasta 5 veces ante 429 y
   * 5xx. Se apaga para que cada llamada sea una sola request y los reintentos
   * los decida la sincronización (D6).
   */
  private static final HttpOptions SINGLE_ATTEMPT = HttpOptions.builder()
      .retryOptions(HttpRetryOptions.builder().attempts(1).build())
      .build();

  private final EmbeddingGateway gateway;
  private final String model;
  private final int dimensions;
  private final int batchSize;
  private final QueryEmbeddingCache queryCache;
  private final AtomicLong requests = new AtomicLong();

  public ProductEmbedder(EmbeddingGateway gateway, String model, int dimensions, int batchSize,
      int queryCacheSize) {
    this.gateway = gateway;
    this.model = model;
    this.dimensions = dimensions;
    this.batchSize = batchSize;
    this.queryCache = new QueryEmbeddingCache(queryCacheSize);
  }

  /**
   * Embebe textos de productos en lotes de {@code batch-size} (una request por
   * lote).
   */
  public List<float[]> embedDocuments(List<String> texts) {
    List<float[]> vectors = new ArrayList<>(texts.size());
    for (int from = 0; from < texts.size(); from += batchSize) {
      List<String> batch = texts.subList(from, Math.min(from + batchSize, texts.size()));
      vectors.addAll(call(batch, DOCUMENT_TASK));
    }
    return vectors;
  }

  /**
   * Embebe una consulta. Las consultas iguales salvo mayúsculas y espacios
   * salen del caché sin llamar al proveedor (D9).
   */
  public float[] embedQuery(String query) {
    return queryCache.get(query, normalized -> call(List.of(normalized), QUERY_TASK).get(0));
  }

  /** Requests hechas al proveedor desde que arrancó el servicio. */
  public long requestCount() {
    return requests.get();
  }

  public String model() {
    return model;
  }

  public int dimensions() {
    return dimensions;
  }

  private List<float[]> call(List<String> texts, String taskType) {
    EmbedContentConfig config = EmbedContentConfig.builder()
        .taskType(taskType)
        .outputDimensionality(dimensions)
        .httpOptions(SINGLE_ATTEMPT)
        .build();
    requests.incrementAndGet();
    try {
      return gateway.embed(model, List.copyOf(texts), config);
    } catch (RuntimeException e) {
      throw GeminiErrors.translate(e);
    }
  }
}
