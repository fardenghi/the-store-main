package com.amazon.sample.assistant.products.embedding;

import com.google.genai.types.EmbedContentConfig;
import java.util.List;

/**
 * Una request de embeddings al proveedor. Existe para que
 * {@link ProductEmbedder} se pueda probar sin red.
 */
public interface EmbeddingGateway {

  /**
   * Embebe los textos en una sola request.
   *
   * @return un vector por texto, en el mismo orden
   */
  List<float[]> embed(String model, List<String> texts, EmbedContentConfig config);
}
