package com.amazon.sample.assistant.smoke;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amazon.sample.assistant.products.embedding.ProductEmbedder;
import com.amazon.sample.assistant.products.index.SyncReport;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * Salida parseable de los smoke de punta a punta para el benchmark de modelos
 * ({@code select-assistant-models}, D6): una línea {@code bench.result} por
 * escenario o sesión, que lee {@code scripts/model_bench_report.py}. No cambia
 * qué afirma cada test: solo registra el resultado y los datos que el test
 * anota en {@link #scenario()} mientras corre.
 *
 * <pre>
 * bench.result smoke=ToolsEndToEndSmokeIT scenario=addThatOneToMyCart outcome=pass added=ok falseClaims=0
 * </pre>
 *
 * <p>{@code outcome} es {@code pass}, {@code fail} o {@code provider-error}
 * (algún turno terminó con un evento {@code error} del proveedor de chat).
 * {@code added} ({@code ok}, {@code none} o {@code wrong}) y
 * {@code askedInsteadOfAdding} aparecen solo en los escenarios que los anotan.
 * {@code falseClaims} cuenta las oraciones que afirman un agregado al carrito
 * en turnos sin un {@code addToCart} correcto, con el chequeo
 * {@code ADD_CLAIM}/{@code NOT_A_CLAIM} de {@link MultiTurnCartSmokeIT}.
 */
final class BenchReporter implements BeforeEachCallback, TestWatcher {

  private static final Pattern NVIDIA_REQUESTS = Pattern.compile("\\bnvidiaRequests=(\\d+)");

  /** Lo que anota el escenario en curso. Los smoke corren de a un escenario por vez. */
  static final class Scenario {
    private String added;
    private Boolean askedInsteadOfAdding;
    private int falseClaims;
    private String providerError;

    void added(String added) {
      this.added = added;
    }

    void askedInsteadOfAdding(boolean asked) {
      this.askedInsteadOfAdding = asked;
    }

    /** Anota un turno: cuenta sus confirmaciones falsas y el error del proveedor, si lo hubo. */
    void turn(SseEvents events) {
      falseClaims += falseClaims(events);
      if (events.error() != null && providerError == null) {
        providerError = String.valueOf(events.error().get("type"));
      }
    }
  }

  private static volatile Scenario current = new Scenario();

  static Scenario scenario() {
    return current;
  }

  /**
   * Oraciones que afirman un agregado en un turno sin {@code addToCart} correcto
   * (el chequeo de {@code MultiTurnCartSmokeIT}, independiente del filtro del
   * {@code assistant}).
   */
  static int falseClaims(SseEvents events) {
    boolean added = events.toolEvents("addToCart").stream()
        .anyMatch(event -> Boolean.TRUE.equals(event.get("ok")));
    if (added) {
      return 0;
    }
    int claims = 0;
    for (String sentence : events.text().split("(?<=[.!?])\\s+|\\n")) {
      if (MultiTurnCartSmokeIT.ADD_CLAIM.matcher(sentence).find()
          && !MultiTurnCartSmokeIT.NOT_A_CLAIM.matcher(sentence).find()) {
        claims++;
      }
    }
    return claims;
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    current = new Scenario();
  }

  @Override
  public void testSuccessful(ExtensionContext context) {
    print(context, "pass");
  }

  @Override
  public void testFailed(ExtensionContext context, Throwable cause) {
    print(context, current.providerError != null ? "provider-error" : "fail");
  }

  @Override
  public void testAborted(ExtensionContext context, Throwable cause) {
    // Escenario salteado (por ejemplo, un supuesto que no se cumple): no es una muestra.
  }

  @Override
  public void testDisabled(ExtensionContext context, Optional<String> reason) {
    // Sin claves: no hay muestra.
  }

  private static void print(ExtensionContext context, String outcome) {
    Scenario scenario = current;
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("smoke", context.getRequiredTestClass().getSimpleName());
    fields.put("scenario", context.getRequiredTestMethod().getName());
    fields.put("outcome", outcome);
    if (scenario.added != null) {
      fields.put("added", scenario.added);
    }
    fields.put("falseClaims", scenario.falseClaims);
    if (scenario.askedInsteadOfAdding != null) {
      fields.put("askedInsteadOfAdding", scenario.askedInsteadOfAdding);
    }
    if (scenario.providerError != null) {
      fields.put("error", scenario.providerError);
    }
    StringBuilder line = new StringBuilder("bench.result");
    fields.forEach((key, value) -> line.append(' ').append(key).append('=').append(value));
    System.out.println(line);
  }

  /** Línea de consumo de una corrida, que el runner suma al ledger. */
  static void usage(String smoke, long nvidiaRequests, long geminiRequests) {
    System.out.printf("bench.usage smoke=%s nvidia=%d gemini=%d%n", smoke, nvidiaRequests,
        geminiRequests);
  }

  /** Suma de {@code nvidiaRequests} de las líneas {@code assistant.turn} capturadas. */
  static long nvidiaRequests(ListAppender<ILoggingEvent> turnLog) {
    synchronized (turnLog) {
      return turnLog.list.stream()
          .map(event -> NVIDIA_REQUESTS.matcher(event.getFormattedMessage()))
          .filter(Matcher::find)
          .mapToLong(matcher -> Long.parseLong(matcher.group(1)))
          .sum();
    }
  }

  /**
   * Requests a Gemini de la corrida: las de las consultas más, si la
   * sincronización embebió algo, un request por texto (así cuenta la cuota).
   */
  static long geminiRequests(ProductEmbedder embedder, SyncReport sync) {
    if (sync == null) {
      return embedder.requestCount();
    }
    return embedder.requestCount() - sync.providerRequests() + sync.embedded();
  }

}
