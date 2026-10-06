package com.amazon.sample.assistant.products.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.amazon.sample.assistant.config.SearchProperties;
import com.amazon.sample.assistant.products.embedding.EmbeddingGateway;
import com.amazon.sample.assistant.products.embedding.ProductEmbedder;
import com.amazon.sample.assistant.products.vector.IndexedProduct;
import com.amazon.sample.assistant.products.vector.ProductVectorRepository;
import com.amazon.sample.assistant.products.vector.ScoredProduct;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.embedding.EmbeddingModel;

class ProductSearchServiceTest {

  static final SearchProperties PROPERTIES = new SearchProperties(5, 20, 4, 12, 256);

  private final EmbeddingGateway gateway = mock(EmbeddingGateway.class);
  private final ProductEmbedder embedder =
      new ProductEmbedder(gateway, "gemini-embedding-001", 768, 100, 16);
  private final ProductVectorRepository repository = mock(ProductVectorRepository.class);
  private final ProductSearchService search =
      new ProductSearchService(embedder, repository, PROPERTIES);
  private final SimilarProductsService similar =
      new SimilarProductsService(repository, PROPERTIES);

  @ParameterizedTest
  @CsvSource(nullValues = "null", value = {
      "null,  null, null, null, q",
      "'',    null, null, null, q",
      "'   ', null, null, null, q",
      "lamp,  0,    null, null, k",
      "lamp,  21,   null, null, k",
      "lamp,  null, -1,   null, minPrice",
      "lamp,  null, null, -5,   maxPrice",
      "table, null, 500,  100,  minPrice"
  })
  void invalidParametersAreRejectedBeforeCallingAnything(String q, Integer k, Integer minPrice,
      Integer maxPrice, String parameter) {
    assertThatThrownBy(() -> search.search(q, List.of(), minPrice, maxPrice, k))
        .isInstanceOfSatisfying(InvalidParameterException.class,
            e -> assertThat(e.parameter()).isEqualTo(parameter));
    verifyNoInteractions(gateway, repository);
  }

  @Test
  void emptyIndexDoesNotCallEmbedder() {
    when(repository.count()).thenReturn(0L);

    assertThatThrownBy(() -> search.search("lamp", List.of(), null, null, null))
        .isInstanceOf(IndexUnavailableException.class);
    verifyNoInteractions(gateway);
  }

  @Test
  void searchEmbedsQueryAndAppliesFiltersAndDefaultK() {
    when(repository.count()).thenReturn(80L);
    when(gateway.embed(any(), anyList(), any())).thenReturn(List.of(new float[768]));
    IndexedProduct lamp = new IndexedProduct("id-1", "Lamp", "A lamp", 80, List.of("lighting"),
        "h");
    when(repository.search(any(), eq(List.of("lighting")), eq(10), eq(100), eq(5)))
        .thenReturn(List.of(new ScoredProduct(lamp, 0.8f)));

    List<ProductResult> result = search.search("lamp", List.of("lighting"), 10, 100, null);

    assertThat(result).containsExactly(
        new ProductResult("id-1", "Lamp", "A lamp", 80, List.of("lighting"), 0.8f));
  }

  @Test
  void boundaryValuesAreAccepted() {
    when(repository.count()).thenReturn(80L);
    when(gateway.embed(any(), anyList(), any())).thenReturn(List.of(new float[768]));
    when(repository.search(any(), anyList(), any(), any(), anyInt())).thenReturn(List.of());

    search.search("lamp", null, 0, 0, 1);
    search.search("lamp", null, 100, 100, 20);

    verify(repository).search(any(), eq(List.of()), eq(0), eq(0), eq(1));
  }

  @ParameterizedTest
  @CsvSource({"0", "13"})
  void similarRejectsKOutOfRange(int k) {
    assertThatThrownBy(() -> similar.similar("3600929b-2826-5a98-908f-82a1d50bcf2b", k))
        .isInstanceOfSatisfying(InvalidParameterException.class,
            e -> assertThat(e.parameter()).isEqualTo("k"));
  }

  @Test
  void similarOfUnknownProductIsNotFound() {
    when(repository.count()).thenReturn(80L);
    when(repository.similar("00000000-0000-0000-0000-000000000000", 4))
        .thenReturn(java.util.Optional.empty());

    assertThatThrownBy(() -> similar.similar("00000000-0000-0000-0000-000000000000", null))
        .isInstanceOf(ProductNotFoundException.class);
    assertThatThrownBy(() -> similar.similar("not-a-uuid", null))
        .isInstanceOf(ProductNotFoundException.class);
  }

  @Test
  void similarWithEmptyIndexIsUnavailable() {
    when(repository.count()).thenReturn(0L);

    assertThatThrownBy(() -> similar.similar("00000000-0000-0000-0000-000000000000", null))
        .isInstanceOf(IndexUnavailableException.class);
  }

  @Test
  void similarServiceDoesNotDependOnEmbeddingProvider() {
    List<Class<?>> dependencies = Arrays.stream(SimilarProductsService.class.getConstructors())
        .map(Constructor::getParameterTypes)
        .flatMap(Arrays::stream)
        .toList();
    List<Class<?>> fields = Arrays.stream(SimilarProductsService.class.getDeclaredFields())
        .<Class<?>>map(java.lang.reflect.Field::getType)
        .toList();

    assertThat(dependencies).containsExactly(ProductVectorRepository.class,
        SearchProperties.class);
    assertThat(fields).doesNotContain(ProductEmbedder.class, EmbeddingGateway.class,
        EmbeddingModel.class);
  }
}
