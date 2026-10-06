package com.amazon.sample.assistant.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazon.sample.assistant.products.embedding.EmbeddingProviderException;
import com.amazon.sample.assistant.products.embedding.EmbeddingProviderException.Reason;
import com.amazon.sample.assistant.products.embedding.GoogleGenAiEmbeddingGateway;
import com.amazon.sample.assistant.products.embedding.ProductEmbedder;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.google.genai.GoogleGenAiEmbeddingConnectionDetails;

/**
 * El {@code task-type} por llamada llega a Gemini: el mismo texto embebido
 * como documento y como consulta da vectores distintos (2 requests). Con la
 * clave placeholder el error se traduce a {@code UNAUTHORIZED} (1 request
 * rechazada). Solo corre con {@code ./mvnw -Psmoke verify}.
 */
@Tag("smoke")
@EnabledIfEnvironmentVariable(named = "GOOGLE_API_KEY", matches = ".+")
class EmbeddingTaskTypeSmokeIT {

  private static final String TEXT = "mid century velvet armchair for a reading corner";

  private static ProductEmbedder embedder(String apiKey) {
    GoogleGenAiEmbeddingConnectionDetails details =
        GoogleGenAiEmbeddingConnectionDetails.builder().apiKey(apiKey).build();
    return new ProductEmbedder(new GoogleGenAiEmbeddingGateway(details), "gemini-embedding-001",
        768, 100, 16);
  }

  @Test
  void documentAndQueryVectorsDiffer() {
    ProductEmbedder embedder = embedder(System.getenv("GOOGLE_API_KEY"));

    float[] document = embedder.embedDocuments(List.of(TEXT)).get(0);
    float[] query = embedder.embedQuery(TEXT);

    double cosine = cosine(document, query);
    System.out.printf("smoke task-type: dims=%d/%d coseno(documento, consulta)=%.6f%n",
        document.length, query.length, cosine);
    assertThat(document).hasSize(768);
    assertThat(query).hasSize(768);
    assertThat(query).isNotEqualTo(document);
    assertThat(cosine).isLessThan(0.9999);
    assertThat(embedder.requestCount()).isEqualTo(2);
  }

  @Test
  void placeholderKeyIsUnauthorized() {
    ProductEmbedder embedder = embedder("not-configured");

    assertThatThrownBy(() -> embedder.embedQuery("lamp"))
        .isInstanceOfSatisfying(EmbeddingProviderException.class, e -> {
          System.out.printf("smoke clave inválida: %s (%s)%n", e.reason(), e.getMessage());
          assertThat(e.reason()).isEqualTo(Reason.UNAUTHORIZED);
        });
  }

  static double cosine(float[] a, float[] b) {
    double dot = 0;
    double na = 0;
    double nb = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      na += a[i] * a[i];
      nb += b[i] * b[i];
    }
    return dot / (Math.sqrt(na) * Math.sqrt(nb));
  }
}
