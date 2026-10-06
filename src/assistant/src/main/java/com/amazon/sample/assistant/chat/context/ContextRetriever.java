package com.amazon.sample.assistant.chat.context;

import com.amazon.sample.assistant.chat.rewrite.Intent;
import com.amazon.sample.assistant.chat.rewrite.Rewrite;
import com.amazon.sample.assistant.chat.session.SessionState;
import com.amazon.sample.assistant.chat.session.ShownProduct;
import com.amazon.sample.assistant.products.embedding.EmbeddingProviderException;
import com.amazon.sample.assistant.products.search.IndexUnavailableException;
import com.amazon.sample.assistant.products.search.ProductResult;
import com.amazon.sample.assistant.products.search.ProductSearchService;
import com.amazon.sample.assistant.products.vector.VectorStoreException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Busca los productos del turno con la consulta reescrita y sus filtros (D5).
 *
 * <p>La búsqueda semántica no sabe excluir tags: con {@code excludeTags} se
 * piden {@code min(3·k, 20)} resultados y se filtran acá hasta quedarse con
 * {@code k}. Si el índice o los embeddings no están disponibles, el turno
 * sigue sin productos y marcado como "catálogo no disponible".
 */
public class ContextRetriever {

  private static final Logger log = LoggerFactory.getLogger(ContextRetriever.class);

  /** Máximo de resultados de la búsqueda ({@code retail.assistant.search.max-k}). */
  static final int SEARCH_MAX_K = 20;

  private final ProductSearchService search;
  private final int k;
  private final double minScore;

  public ContextRetriever(ProductSearchService search, int k, double minScore) {
    this.search = search;
    this.k = k;
    this.minScore = minScore;
  }

  public Retrieval retrieve(Rewrite rewrite, SessionState session) {
    List<ShownProduct> previous = session.lastProducts();
    boolean compare = rewrite.intent() == Intent.COMPARE;
    if (rewrite.intent() == Intent.OTHER) {
      return new Retrieval(false, false, List.of(), previous, false, 0);
    }
    long start = System.nanoTime();
    try {
      List<ShownProduct> found = search(rewrite.query(), rewrite.minPrice(), rewrite.maxPrice(),
          rewrite.excludeTags());
      return new Retrieval(true, false, found, previous, compare, elapsed(start));
    } catch (EmbeddingProviderException e) {
      log.warn("Catálogo no disponible en el turno: embeddings {} ({})", e.reason().type(),
          e.getMessage());
    } catch (IndexUnavailableException | VectorStoreException e) {
      log.warn("Catálogo no disponible en el turno: índice ({})", e.getMessage());
    }
    // Sin catálogo no se nombran productos, tampoco los del turno anterior (D5).
    return new Retrieval(true, true, List.of(), List.of(), false, elapsed(start));
  }

  /**
   * Busca con la consulta y los filtros, y descarta los productos con tags
   * excluidos. La usa también el log comparativo con la consulta cruda (D9).
   */
  public List<ShownProduct> search(String query, Integer minPrice, Integer maxPrice,
      List<String> excludeTags) {
    int limit = excludeTags.isEmpty() ? k : Math.min(3 * k, SEARCH_MAX_K);
    List<ProductResult> results = search.search(query, null, minPrice, maxPrice, limit);
    return results.stream()
        .filter(r -> minScore <= 0 || r.score() >= minScore)
        .filter(r -> r.tags().stream().noneMatch(excludeTags::contains))
        .limit(k)
        .map(ShownProduct::from)
        .toList();
  }

  private static long elapsed(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }
}
