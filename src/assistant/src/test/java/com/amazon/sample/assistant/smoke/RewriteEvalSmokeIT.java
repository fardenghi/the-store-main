package com.amazon.sample.assistant.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
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
 *
 * <p>Igual que los smoke de punta a punta, con {@code -Dsmoke.qdrant.host},
 * {@code -Dsmoke.qdrant.port} y {@code -Dsmoke.qdrant.collection} reutiliza una
 * colección ya indexada: la sincronización no vuelve a embeber los productos
 * sin cambios y la corrida gasta solo las búsquedas.
 *
 * <p>Para el benchmark de modelos ({@code select-assistant-models}) imprime una
 * línea {@code bench.rewrite} por consulta ({@code outcome} {@code ok},
 * {@code fallback} o {@code invalid}, latencia y aciertos del top-5 crudo y
 * reescrito) y una {@code bench.usage} con el consumo de la corrida.
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
    String host = System.getProperty("smoke.qdrant.host");
    if (host != null) {
      // Colección ya indexada: solo para no gastar cuota de Gemini en los smoke.
      registry.add("spring.ai.vectorstore.qdrant.host", () -> host);
      registry.add("spring.ai.vectorstore.qdrant.port",
          () -> System.getProperty("smoke.qdrant.port", "6334"));
      registry.add("spring.ai.vectorstore.qdrant.collection-name",
          () -> System.getProperty("smoke.qdrant.collection", "products"));
    } else {
      registry.add("spring.ai.vectorstore.qdrant.host", QdrantTestSupport::host);
      registry.add("spring.ai.vectorstore.qdrant.port", QdrantTestSupport::grpcPort);
      registry.add("spring.ai.vectorstore.qdrant.collection-name",
          QdrantTestSupport::uniqueCollection);
    }
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

  /** Avisos de {@link QueryRewriter}: distinguen un JSON inválido de un timeout o un error. */
  private final ListAppender<ILoggingEvent> rewriterLog = new ListAppender<>();

  @AfterAll
  void close() {
    ((Logger) LoggerFactory.getLogger(QueryRewriter.class)).detachAppender(rewriterLog);
    CATALOG.close();
  }

  @Test
  void rewrittenQueriesFindMoreExpectedProducts() throws Exception {
    SyncReport report = indexer.sync();
    assertThat(report).isNotNull();
    System.out.printf("eval reescritura: sincronización %s%n", report);
    rewriterLog.start();
    ((Logger) LoggerFactory.getLogger(QueryRewriter.class)).addAppender(rewriterLog);
    long rewriteRequests = 0;
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
      int warningsBefore = warnings();
      Rewrite rewrite = rewriter.rewrite(evalCase.message(), session);
      rewriteRequests += rewrite.providerRequests();
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
      System.out.printf("bench.rewrite smoke=RewriteEvalSmokeIT id=%s outcome=%s latencyMs=%d "
              + "rawHits=%d rewrittenHits=%d%n", evalCase.id(), outcome(rewrite, warningsBefore),
          rewrite.latencyMillis(), rawCount, rewrittenCount);
      System.out.printf("eval reescritura: %s%n  crudo:    %s%n  reescrito: %s%n", row,
          raw.stream().map(p -> p.name() + " " + p.tags()).toList(),
          rewritten.stream().map(p -> p.name() + " $" + p.price() + " " + p.tags()).toList());
    }
    System.out.printf("eval reescritura: total crudo %d, total reescrito %d, %d requests a "
        + "Gemini%n", rawHits, rewrittenHits, embedder.requestCount());
    System.out.println("| Consulta | Tipo | Mensaje | Reescrita | Crudo | Reescrito |");
    System.out.println("| --- | --- | --- | --- | --- | --- |");
    rows.forEach(System.out::println);
    BenchReporter.usage("RewriteEvalSmokeIT", rewriteRequests,
        BenchReporter.geminiRequests(embedder, report));

    assertThat(rewrittenHits).isGreaterThan(rawHits);
  }

  private int warnings() {
    synchronized (rewriterLog) {
      return rewriterLog.list.size();
    }
  }

  /**
   * {@code ok}, {@code invalid} (el modelo respondió con una salida que no
   * parsea o no valida) o {@code fallback} (timeout, error o limitador).
   */
  private String outcome(Rewrite rewrite, int warningsBefore) {
    if (!rewrite.fallback()) {
      return "ok";
    }
    synchronized (rewriterLog) {
      boolean invalid = rewriterLog.list.subList(warningsBefore, rewriterLog.list.size()).stream()
          .map(ILoggingEvent::getFormattedMessage)
          .anyMatch(message -> message.contains("inválid"));
      return invalid ? "invalid" : "fallback";
    }
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
