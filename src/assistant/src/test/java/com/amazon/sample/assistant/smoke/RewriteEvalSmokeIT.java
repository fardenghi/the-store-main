package com.amazon.sample.assistant.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.assistant.chat.context.ContextRetriever;
import com.amazon.sample.assistant.chat.rewrite.QueryRewriter;
import com.amazon.sample.assistant.chat.rewrite.Rewrite;
import com.amazon.sample.assistant.chat.session.SessionState;
import com.amazon.sample.assistant.chat.session.ShownProduct;
import com.amazon.sample.assistant.chat.session.Turn;
import com.amazon.sample.assistant.products.catalog.CatalogProduct;
import com.amazon.sample.assistant.products.embedding.ProductEmbedder;
import com.amazon.sample.assistant.products.index.ProductIndexer;
import com.amazon.sample.assistant.products.index.SyncReport;
import com.amazon.sample.assistant.products.vector.QdrantTestSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Evaluación de la reescritura (D9): cada consulta de
 * {@code rewrite-eval.json} se busca cruda y reescrita (con el turno previo
 * simulado cuando lo tiene) y se cuentan los productos esperados en el top-5.
 * La reescrita tiene que acertar más en total.
 *
 * <p>Gasta 10 requests a NVIDIA (una reescritura por consulta) y unas 21 a
 * Gemini (1 para indexar y 2 búsquedas por consulta, menos las que salen del
 * caché). Solo corre con {@code ./mvnw -Psmoke verify}.
 */
@Tag("smoke")
@EnabledIfEnvironmentVariable(named = "NVIDIA_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "GOOGLE_API_KEY", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = WebEnvironment.NONE, properties = {
    "spring.ai.retry.max-attempts=1",
    "retail.assistant.indexing.max-provider-retries=0"
})
class RewriteEvalSmokeIT {

  static final FakeCatalog CATALOG = new FakeCatalog();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("retail.assistant.endpoints.catalog", CATALOG::baseUrl);
    registry.add("spring.ai.vectorstore.qdrant.host", QdrantTestSupport::host);
    registry.add("spring.ai.vectorstore.qdrant.port", QdrantTestSupport::grpcPort);
    registry.add("spring.ai.vectorstore.qdrant.collection-name",
        QdrantTestSupport::uniqueCollection);
  }

  record Expected(List<String> all, List<String> any, List<String> none, Integer maxPrice) {

    boolean matches(ShownProduct product) {
      List<String> tags = product.tags();
      return (all == null || tags.containsAll(all))
          && (any == null || any.stream().anyMatch(tags::contains))
          && (none == null || none.stream().noneMatch(tags::contains))
          && (maxPrice == null || product.price() <= maxPrice);
    }
  }

  record History(String user, String assistant, List<String> products) {
  }

  record EvalCase(String id, String kind, String message, History history, Expected expected) {
  }

  @Autowired
  private ProductIndexer indexer;

  @Autowired
  private QueryRewriter rewriter;

  @Autowired
  private ContextRetriever retriever;

  @Autowired
  private ProductEmbedder embedder;

  @AfterAll
  void close() {
    CATALOG.close();
  }

  @Test
  void rewrittenQueriesFindMoreExpectedProducts() throws Exception {
    SyncReport report = indexer.sync();
    assertThat(report).isNotNull();
    List<EvalCase> cases;
    try (InputStream in = getClass().getResourceAsStream("/rewrite-eval.json")) {
      cases = new ObjectMapper().readValue(in, new TypeReference<>() { });
    }

    int rawHits = 0;
    int rewrittenHits = 0;
    List<String> rows = new ArrayList<>();
    for (EvalCase evalCase : cases) {
      SessionState session = new SessionState(10);
      if (evalCase.history() != null) {
        List<ShownProduct> shown = evalCase.history().products().stream()
            .map(name -> CATALOG.byName(name).orElseThrow(() ->
                new IllegalStateException("Producto inexistente en el eval: " + name)))
            .map(RewriteEvalSmokeIT::shown)
            .toList();
        session.commit(new Turn(evalCase.history().user(), evalCase.history().assistant()), shown);
      }
      List<ShownProduct> raw = retriever.search(evalCase.message(), null, null, List.of());
      Rewrite rewrite = rewriter.rewrite(evalCase.message(), session);
      List<ShownProduct> rewritten = retriever.search(rewrite.query(), rewrite.minPrice(),
          rewrite.maxPrice(), rewrite.excludeTags());
      long rawCount = raw.stream().filter(evalCase.expected()::matches).count();
      long rewrittenCount = rewritten.stream().filter(evalCase.expected()::matches).count();
      rawHits += rawCount;
      rewrittenHits += rewrittenCount;
      String row = String.format("| %s | %s | `%s` | `%s`%s | %d | %d |", evalCase.id(),
          evalCase.kind(), evalCase.message(), rewrite.query(), filters(rewrite), rawCount,
          rewrittenCount);
      rows.add(row);
      System.out.printf("eval reescritura: %s%n  crudo:    %s%n  reescrito: %s%n", row,
          raw.stream().map(p -> p.name() + " " + p.tags()).toList(),
          rewritten.stream().map(p -> p.name() + " $" + p.price() + " " + p.tags()).toList());
    }
    System.out.printf("eval reescritura: total crudo %d, total reescrito %d, %d requests a "
        + "Gemini%n", rawHits, rewrittenHits, embedder.requestCount());
    System.out.println("| Consulta | Tipo | Mensaje | Reescrita | Crudo | Reescrito |");
    System.out.println("| --- | --- | --- | --- | --- | --- |");
    rows.forEach(System.out::println);

    assertThat(rewrittenHits).isGreaterThan(rawHits);
  }

  private static String filters(Rewrite rewrite) {
    StringBuilder out = new StringBuilder();
    if (rewrite.fallback()) {
      out.append(" (fallback)");
    }
    if (rewrite.minPrice() != null) {
      out.append(" minPrice=").append(rewrite.minPrice());
    }
    if (rewrite.maxPrice() != null) {
      out.append(" maxPrice=").append(rewrite.maxPrice());
    }
    if (!rewrite.excludeTags().isEmpty()) {
      out.append(" excluye ").append(rewrite.excludeTags());
    }
    return out.toString();
  }

  private static ShownProduct shown(CatalogProduct product) {
    return new ShownProduct(product.id(), product.name(), product.description(), product.price(),
        product.tagNames());
  }
}
