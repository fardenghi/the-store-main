package com.amazon.sample.assistant.products.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazon.sample.assistant.products.embedding.EmbeddingProviderException.Reason;
import com.google.genai.errors.ClientException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.errors.ServerException;
import com.google.genai.types.EmbedContentConfig;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class ProductEmbedderTest {

  private final EmbeddingGateway gateway = mock(EmbeddingGateway.class);
  private final ProductEmbedder embedder =
      new ProductEmbedder(gateway, "gemini-embedding-001", 768, 100, 256);

  @Test
  void documentsUseRetrievalDocumentInBatchesOf100() {
    when(gateway.embed(any(), anyList(), any())).thenAnswer(invocation ->
        vectors(((List<?>) invocation.getArgument(1)).size()));
    List<String> texts = IntStream.range(0, 120).mapToObj(i -> "producto " + i).toList();

    List<float[]> vectors = embedder.embedDocuments(texts);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> batches = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<EmbedContentConfig> configs = ArgumentCaptor.forClass(EmbedContentConfig.class);
    verify(gateway, times(2)).embed(eq("gemini-embedding-001"), batches.capture(),
        configs.capture());
    assertThat(batches.getAllValues()).extracting(List::size).containsExactly(100, 20);
    assertThat(configs.getAllValues()).allSatisfy(config -> {
      assertThat(config.taskType()).contains("RETRIEVAL_DOCUMENT");
      assertThat(config.outputDimensionality()).contains(768);
      // Sin los reintentos propios del SDK: los decide la sincronización (D6).
      assertThat(config.httpOptions().orElseThrow().retryOptions().orElseThrow().attempts())
          .contains(1);
    });
    assertThat(vectors).hasSize(120);
    assertThat(embedder.requestCount()).isEqualTo(2);
  }

  @Test
  void queryUsesRetrievalQuery() {
    when(gateway.embed(any(), anyList(), any())).thenReturn(vectors(1));

    float[] vector = embedder.embedQuery("velvet armchair");

    ArgumentCaptor<EmbedContentConfig> config = ArgumentCaptor.forClass(EmbedContentConfig.class);
    verify(gateway).embed(eq("gemini-embedding-001"), eq(List.of("velvet armchair")),
        config.capture());
    assertThat(config.getValue().taskType()).contains("RETRIEVAL_QUERY");
    assertThat(config.getValue().outputDimensionality()).contains(768);
    assertThat(vector).hasSize(768);
  }

  @Test
  void sameQueryIgnoringCaseAndSpacesCallsProviderOnce() {
    when(gateway.embed(any(), anyList(), any())).thenReturn(vectors(1));

    float[] first = embedder.embedQuery("Mid Century  velvet armchair");
    float[] second = embedder.embedQuery("  mid century velvet ARMCHAIR ");

    verify(gateway, times(1)).embed(any(), anyList(), any());
    assertThat(second).isSameAs(first);
  }

  @Test
  void quotaErrorCarriesRetryDelay() {
    when(gateway.embed(any(), anyList(), any())).thenThrow(new ClientException(429,
        "RESOURCE_EXHAUSTED", "You exceeded your current quota. Please retry in 12.5s."));

    assertThatThrownBy(() -> embedder.embedQuery("lamp"))
        .isInstanceOfSatisfying(EmbeddingProviderException.class, e -> {
          assertThat(e.reason()).isEqualTo(Reason.QUOTA);
          assertThat(e.retryAfter()).contains(Duration.ofMillis(12500));
        });
  }

  @Test
  void quotaErrorWithoutRetryDelay() {
    when(gateway.embed(any(), anyList(), any()))
        .thenThrow(new ClientException(429, "RESOURCE_EXHAUSTED", "Quota exceeded"));

    assertThatThrownBy(() -> embedder.embedQuery("lamp"))
        .isInstanceOfSatisfying(EmbeddingProviderException.class, e -> {
          assertThat(e.reason()).isEqualTo(Reason.QUOTA);
          assertThat(e.retryAfter()).isEmpty();
        });
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403})
  void invalidKeyIsUnauthorized(int code) {
    when(gateway.embed(any(), anyList(), any()))
        .thenThrow(new ClientException(code, "INVALID_ARGUMENT", "API key not valid."));

    assertThatThrownBy(() -> embedder.embedDocuments(List.of("x")))
        .isInstanceOfSatisfying(EmbeddingProviderException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.UNAUTHORIZED));
  }

  @ParameterizedTest
  @ValueSource(ints = {500, 502, 503, 504})
  void serverErrorIsUnavailable(int code) {
    when(gateway.embed(any(), anyList(), any()))
        .thenThrow(new ServerException(code, "UNAVAILABLE", "The model is overloaded."));

    assertThatThrownBy(() -> embedder.embedDocuments(List.of("x")))
        .isInstanceOfSatisfying(EmbeddingProviderException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.UNAVAILABLE));
  }

  @Test
  void networkErrorIsUnavailable() {
    when(gateway.embed(any(), anyList(), any()))
        .thenThrow(new GenAiIOException("Failed to execute HTTP request.",
            new java.net.ConnectException("Connection refused")));

    assertThatThrownBy(() -> embedder.embedQuery("lamp"))
        .isInstanceOfSatisfying(EmbeddingProviderException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.UNAVAILABLE));
  }

  @Test
  void failedQueryIsNotCached() {
    when(gateway.embed(any(), anyList(), any()))
        .thenThrow(new ServerException(503, "UNAVAILABLE", "overloaded"))
        .thenReturn(vectors(1));

    assertThatThrownBy(() -> embedder.embedQuery("lamp"))
        .isInstanceOf(EmbeddingProviderException.class);
    assertThat(embedder.embedQuery("lamp")).hasSize(768);
    verify(gateway, times(2)).embed(any(), anyList(), any());
  }

  @Test
  void parsesRetryDelayFormats() {
    assertThat(GeminiErrors.retryDelay("Please retry in 41.270838488s."))
        .isEqualTo(Duration.ofMillis(41271));
    assertThat(GeminiErrors.retryDelay("\"retryDelay\": \"37s\"")).isEqualTo(Duration.ofSeconds(37));
    assertThat(GeminiErrors.retryDelay("Quota exceeded")).isNull();
    assertThat(GeminiErrors.retryDelay(null)).isNull();
  }

  static List<float[]> vectors(int count) {
    return IntStream.range(0, count).mapToObj(i -> new float[768]).toList();
  }
}
