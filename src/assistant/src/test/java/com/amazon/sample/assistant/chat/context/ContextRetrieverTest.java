package com.amazon.sample.assistant.chat.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.amazon.sample.assistant.chat.rewrite.Intent;
import com.amazon.sample.assistant.chat.rewrite.Rewrite;
import com.amazon.sample.assistant.chat.session.SessionState;
import com.amazon.sample.assistant.chat.session.ShownProduct;
import com.amazon.sample.assistant.chat.session.Turn;
import com.amazon.sample.assistant.products.embedding.EmbeddingProviderException;
import com.amazon.sample.assistant.products.embedding.EmbeddingProviderException.Reason;
import com.amazon.sample.assistant.products.search.IndexUnavailableException;
import com.amazon.sample.assistant.products.search.ProductResult;
import com.amazon.sample.assistant.products.search.ProductSearchService;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Retrieval del turno (D5). */
class ContextRetrieverTest {

  private final ProductSearchService search = mock(ProductSearchService.class);
  private final ContextRetriever retriever = new ContextRetriever(search, 5, 0);
  private final SessionState session = new SessionState(10);

  private static Rewrite rewrite(Intent intent, String query, Integer maxPrice,
      List<String> excludeTags) {
    return new Rewrite(intent, query, null, maxPrice, excludeTags, false, 1, 10);
  }

  private static ProductResult result(String id, long price, String... tags) {
    return new ProductResult(id, "Product " + id, "desc " + id, price, List.of(tags), 0.5f);
  }

  @Test
  void otherDoesNotSearch() {
    Retrieval retrieval = retriever.retrieve(rewrite(Intent.OTHER, "", null, List.of()), session);

    assertThat(retrieval.searched()).isFalse();
    assertThat(retrieval.products()).isEmpty();
    verifyNoInteractions(search);
  }

  @Test
  void searchUsesQueryPriceFiltersAndK() {
    when(search.search("velvet armchair", null, null, 138, 5))
        .thenReturn(List.of(result("a", 120, "seating")));

    Retrieval retrieval = retriever.retrieve(
        rewrite(Intent.SEARCH, "velvet armchair", 138, List.of()), session);

    assertThat(retrieval.searched()).isTrue();
    assertThat(retrieval.catalogUnavailable()).isFalse();
    assertThat(retrieval.products()).extracting(ShownProduct::id).containsExactly("a");
  }

  @Test
  void excludedTagsAskForMoreAndFilterThemOut() {
    List<ProductResult> results = IntStream.range(0, 15)
        .mapToObj(i -> i % 2 == 0 ? result("lamp" + i, 50, "lighting", "office")
            : result("chair" + i, 80, "seating", "office"))
        .toList();
    when(search.search(eq("reading nook"), isNull(), isNull(), isNull(), eq(15)))
        .thenReturn(results);

    Retrieval retrieval = retriever.retrieve(
        rewrite(Intent.SEARCH, "reading nook", null, List.of("lighting")), session);

    assertThat(retrieval.products()).hasSize(5)
        .allSatisfy(p -> assertThat(p.tags()).doesNotContain("lighting"));
    verify(search).search(eq("reading nook"), isNull(), isNull(), isNull(), eq(15));
  }

  @Test
  void excludedTagsNeverAskForMoreThanTheSearchMaximum() {
    ContextRetriever retrieverK10 = new ContextRetriever(search, 10, 0);
    when(search.search(anyString(), isNull(), isNull(), isNull(), anyInt())).thenReturn(List.of());

    retrieverK10.retrieve(rewrite(Intent.SEARCH, "rug", null, List.of("lighting")), session);

    verify(search).search("rug", null, null, null, 20);
  }

  @Test
  void compareIncludesPreviousProductsWithoutDuplicates() {
    session.commit(new Turn("armchairs", "here"), List.of(
        new ShownProduct("a", "Product a", "desc a", 139, List.of("seating")),
        new ShownProduct("b", "Product b", "desc b", 249, List.of("seating"))));
    when(search.search("armchairs", null, null, null, 5))
        .thenReturn(List.of(result("b", 249, "seating"), result("c", 300, "seating")));

    Retrieval retrieval = retriever.retrieve(
        rewrite(Intent.COMPARE, "armchairs", null, List.of()), session);

    assertThat(retrieval.products()).extracting(ShownProduct::id).containsExactly("a", "b", "c");
    assertThat(retrieval.previous()).extracting(ShownProduct::id).containsExactly("a", "b");
  }

  @Test
  void embeddingErrorIsNotPropagated() {
    when(search.search(any(), any(), any(), any(), any())).thenThrow(
        new EmbeddingProviderException(Reason.QUOTA, null, "429", null));

    Retrieval retrieval = retriever.retrieve(
        rewrite(Intent.SEARCH, "rug", null, List.of()), session);

    assertThat(retrieval.searched()).isTrue();
    assertThat(retrieval.catalogUnavailable()).isTrue();
    assertThat(retrieval.products()).isEmpty();
  }

  @Test
  void emptyIndexIsCatalogUnavailable() {
    session.commit(new Turn("armchairs", "here"),
        List.of(new ShownProduct("a", "Product a", "desc", 139, List.of("seating"))));
    when(search.search(any(), any(), any(), any(), any()))
        .thenThrow(new IndexUnavailableException());

    Retrieval retrieval = retriever.retrieve(
        rewrite(Intent.COMPARE, "armchairs", null, List.of()), session);

    assertThat(retrieval.catalogUnavailable()).isTrue();
    assertThat(retrieval.products()).isEmpty();
    assertThat(retrieval.previous()).isEmpty();
  }
}
