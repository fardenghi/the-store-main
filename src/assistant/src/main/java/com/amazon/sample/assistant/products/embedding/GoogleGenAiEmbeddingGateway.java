package com.amazon.sample.assistant.products.embedding;

import com.google.genai.Client;
import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import java.util.List;
import org.springframework.ai.google.genai.GoogleGenAiEmbeddingConnectionDetails;

/**
 * Llama a la Gemini API con el {@link Client} que autoconfigura el starter
 * {@code spring-ai-starter-model-google-genai-embedding} (misma clave y misma
 * conexión que el {@code EmbeddingModel}).
 *
 * <p>No se usa el {@code EmbeddingModel} porque en Spring AI 1.1.8
 * {@code GoogleGenAiTextEmbeddingModel.call()} no envía el {@code taskType} a
 * Gemini (ver el desvío en el design de add-product-indexing).
 */
public class GoogleGenAiEmbeddingGateway implements EmbeddingGateway {

  private final GoogleGenAiEmbeddingConnectionDetails connectionDetails;

  public GoogleGenAiEmbeddingGateway(GoogleGenAiEmbeddingConnectionDetails connectionDetails) {
    this.connectionDetails = connectionDetails;
  }

  @Override
  public List<float[]> embed(String model, List<String> texts, EmbedContentConfig config) {
    Client client = connectionDetails.getGenAiClient();
    EmbedContentResponse response = client.models.embedContent(
        connectionDetails.getModelEndpointName(model), texts, config);
    List<ContentEmbedding> embeddings = response.embeddings().orElse(List.of());
    if (embeddings.size() != texts.size()) {
      throw new IllegalStateException("Gemini devolvió " + embeddings.size()
          + " embeddings para " + texts.size() + " textos");
    }
    return embeddings.stream().map(GoogleGenAiEmbeddingGateway::toArray).toList();
  }

  private static float[] toArray(ContentEmbedding embedding) {
    List<Float> values = embedding.values().orElse(List.of());
    float[] vector = new float[values.size()];
    for (int i = 0; i < vector.length; i++) {
      vector[i] = values.get(i);
    }
    return vector;
  }
}
