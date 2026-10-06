package com.amazon.sample.assistant.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amazon.sample.assistant.chat.session.SessionStore;
import com.amazon.sample.assistant.chat.session.ToolRound;
import com.amazon.sample.assistant.products.catalog.CatalogProduct;
import com.amazon.sample.assistant.products.index.ProductIndexer;
import com.amazon.sample.assistant.products.vector.QdrantTestSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Sesiones largas con pedidos de carrito al final (corrección posterior de
 * {@code add-assistant-tools}): las tres sesiones del reporte de
 * {@code integrate-ui-assistant} en las que, desde el segundo o tercer turno,
 * el modelo dejaba de llamar a las tools y confirmaba agregados que no hizo.
 *
 * <p>Por cada turno verifica que la respuesta no afirme un agregado sin un
 * {@code addToCart} correcto y que cada precio que nombra exista en el
 * catálogo (los productos inventados del reporte tenían precios que no son de
 * ningún producto). En el último turno de cada sesión exige el
 * {@code addToCart}, el {@code cart-updated} y el ítem en {@link FakeCarts}.
 *
 * <p>Gasta unas 35 requests a NVIDIA (14 turnos espaciados) y un embedding de
 * Gemini por turno con búsqueda. Igual que los demás smoke de punta a punta,
 * con {@code -Dsmoke.qdrant.*} reutiliza una colección ya indexada.
 */
@Tag("smoke")
@EnabledIfEnvironmentVariable(named = "NVIDIA_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "GOOGLE_API_KEY", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "spring.ai.retry.max-attempts=1",
    "retail.assistant.indexing.max-provider-retries=0",
    "retail.assistant.chat.compare-raw-retrieval=false",
    "spring.http.client.read-timeout=150s"
})
class MultiTurnCartSmokeIT {

  /** Espacio entre turnos: hasta 4 requests cada 7 s, por debajo de los 36 del limitador. */
  private static final Duration TURN_SPACING = Duration.ofSeconds(7);

  private static final Pattern DOLLARS = Pattern.compile("\\$\\s?([0-9][0-9,]*)");

  /** Un monto que es un presupuesto o un límite ("under $100"), no el precio de un producto. */
  private static final Pattern BUDGET = Pattern.compile("(?i)(under|below|less than|over|above"
      + "|more than|up to|at most|max(?:imum)?|budget(?: of)?|cheaper than|within|around|menos de"
      + "|más de|hasta)\\s+\\$\\s?([0-9][0-9,]*)");

  /**
   * Afirmaciones de un agregado al carrito, en inglés y en castellano. Es un
   * chequeo propio del test, independiente del filtro del {@code assistant}.
   */
  static final Pattern ADD_CLAIM = Pattern.compile("(?i)(\\badded\\b"
      + "|\\badding\\b.*\\b(cart|inventory)\\b|\\bin(to)? your (cart|mission inventory)\\b"
      + "|agregu[eé]|agregad[oa]s?|añad[ií]|sumad[oa]s? al carrito)");

  /** Oraciones negadas, condicionales, pedidos de confirmación o preguntas: no afirman. */
  static final Pattern NOT_A_CLAIM = Pattern.compile(
      "(?i)(\\bnot?\\b|n['’]t\\b|\\bnothing\\b|\\bnada\\b|\\bif\\b|\\bbefore\\b"
          + "|\\bneed\\b|\\bconfirm\\w*|\\bwant\\b|\\?\\s*$)");

  static final FakeCatalog CATALOG = new FakeCatalog();
  static final FakeCarts CARTS = new FakeCarts();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("retail.assistant.endpoints.catalog", CATALOG::baseUrl);
    registry.add("retail.assistant.endpoints.carts", CARTS::baseUrl);
    String host = System.getProperty("smoke.qdrant.host");
    if (host != null) {
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

  @Autowired
  private TestRestTemplate rest;

  @Autowired
  private ProductIndexer indexer;

  @Autowired
  private SessionStore sessions;

  private final ListAppender<ILoggingEvent> turnLog = new ListAppender<>();
  private long lastTurnStart;

  @BeforeAll
  void index() {
    System.out.printf("smoke multi-turno: sincronización %s%n", indexer.sync());
    turnLog.start();
    ((Logger) LoggerFactory.getLogger("assistant.turn")).addAppender(turnLog);
  }

  @AfterAll
  void close() {
    ((Logger) LoggerFactory.getLogger("assistant.turn")).detachAppender(turnLog);
    synchronized (turnLog) {
      turnLog.list.forEach(event -> System.out.println("assistant.turn " + event
          .getFormattedMessage()));
    }
    CATALOG.close();
    CARTS.close();
  }

  /** Sesión {@code 76afbc10} del reporte: siete turnos y el agregado al final. */
  @Test
  @Order(1)
  void reportSessionAddsTwoOfTheFirstOne() {
    String session = "mt-report";
    List<String> problems = new ArrayList<>();
    for (String message : List.of(
        "I need a lamp for my desk",
        "lookin for a mid sentury velvit armchiar",
        "cheaper",
        "nah, not a lamp. what else could make my reading corner cozier?",
        "compare the Eva Tufted Velvet Sofa and the Frederick Channel-Tufted Velvet Sofa",
        "how much is the Aiden Mid-Century Velvet Armchair right now?")) {
      check(turn(session, message), message, problems);
    }

    String message = "add two of the first one to my cart";
    SseEvents last = turn(session, message);
    check(last, message, problems);
    printMemory(session);

    assertThat(problems).as("turnos con alucinaciones").isEmpty();
    assertAdded(session, last, 2);
  }

  /** Sesión {@code 3a7e9304} del reporte. */
  @Test
  @Order(2)
  void namedLampAfterADetourIsAdded() {
    String session = "mt-detour";
    List<String> problems = new ArrayList<>();
    for (String message : List.of(
        "I need a lamp for my desk",
        "cheaper",
        "nah, not a lamp. what else could make my desk area cozier?")) {
      check(turn(session, message), message, problems);
    }

    String message = "add two of the first lamp you showed me (the Curved Brass and Walnut "
        + "Desk Lamp) to my cart";
    SseEvents last = turn(session, message);
    check(last, message, problems);
    printMemory(session);

    assertThat(problems).as("turnos con alucinaciones").isEmpty();
    assertAdded(session, last, 2);
    assertThat(CARTS.items(session)).singleElement().satisfies(item -> assertThat(item.itemId())
        .isEqualTo(CATALOG.byName("Curved Brass and Walnut Desk Lamp").orElseThrow().id()));
  }

  /** Sesión {@code 9952aae8} del reporte: un segundo agregado después de uno correcto. */
  @Test
  @Order(3)
  void secondAddAfterASuccessfulOneIsExecuted() {
    String session = "mt-second";
    List<String> problems = new ArrayList<>();
    String message = "I need a lamp for my desk";
    check(turn(session, message), message, problems);
    message = "add two of the first one to my cart";
    SseEvents first = turn(session, message);
    check(first, message, problems);
    assertThat(first.cartUpdates()).as("primer agregado").hasSize(1);

    message = "add one Adjustable Pharmacy Desk Lamp to my cart too";
    SseEvents second = turn(session, message);
    check(second, message, problems);
    printMemory(session);

    assertThat(problems).as("turnos con alucinaciones").isEmpty();
    CatalogProduct pharmacy = CATALOG.byName("Adjustable Pharmacy Desk Lamp").orElseThrow();
    assertThat(second.toolEvents("addToCart")).as("addToCart del segundo agregado")
        .anySatisfy(event -> assertThat(event.get("ok")).isEqualTo(true));
    assertThat(second.cartUpdates()).singleElement().satisfies(event -> {
      assertThat(event.get("itemId")).isEqualTo(pharmacy.id());
      assertThat(event.get("quantity")).isEqualTo(1);
    });
    assertThat(CARTS.items(session)).hasSize(2)
        .anySatisfy(item -> assertThat(item.itemId()).isEqualTo(pharmacy.id()));
  }

  private void assertAdded(String session, SseEvents last, int quantity) {
    assertThat(last.toolEvents("addToCart")).as("addToCart en el último turno")
        .anySatisfy(event -> assertThat(event.get("ok")).isEqualTo(true));
    assertThat(last.cartUpdates()).as("cart-updated").singleElement()
        .satisfies(event -> assertThat(event.get("quantity")).isEqualTo(quantity));
    assertThat(CARTS.items(session)).singleElement().satisfies(item -> {
      assertThat(item.quantity()).isEqualTo(quantity);
      assertThat(item.unitPrice())
          .isEqualTo(CATALOG.byId(item.itemId()).orElseThrow().price());
    });
  }

  /**
   * Junta los problemas de un turno: un agregado afirmado sin {@code addToCart}
   * correcto, o un precio que no es de ningún producto del catálogo (ni una
   * diferencia o un total de los productos nombrados, ni un monto del usuario,
   * ni un presupuesto como "under $100").
   */
  private void check(SseEvents events, String message, List<String> problems) {
    boolean added = events.toolEvents("addToCart").stream()
        .anyMatch(event -> Boolean.TRUE.equals(event.get("ok")));
    if (!added) {
      for (String sentence : events.text().split("(?<=[.!?])\\s+|\\n")) {
        if (ADD_CLAIM.matcher(sentence).find() && !NOT_A_CLAIM.matcher(sentence).find()) {
          problems.add("\"" + message + "\": afirma un agregado sin addToCart: " + sentence);
        }
      }
    }
    Set<Long> invented = new HashSet<>(dollars(events.text()));
    invented.removeAll(allowedAmounts(events.text(), message));
    if (!invented.isEmpty()) {
      problems.add("\"" + message + "\": precios que no son del catálogo " + invented + ": "
          + events.text());
    }
  }

  private static Set<Long> allowedAmounts(String text, String message) {
    Set<Long> allowed = new HashSet<>(dollars(message));
    Matcher budget = BUDGET.matcher(text);
    while (budget.find()) {
      allowed.add(Long.parseLong(budget.group(2).replace(",", "")));
    }
    CATALOG.products().forEach(p -> allowed.add((long) p.price()));
    String lower = text.toLowerCase(Locale.ROOT);
    List<Long> named = CATALOG.products().stream()
        .filter(p -> lower.contains(p.name().toLowerCase(Locale.ROOT)))
        .map(p -> (long) p.price()).toList();
    // Diferencias entre precios del catálogo que aparecen en el texto ("saves you $10").
    Set<Long> catalogPrices = new HashSet<>();
    CATALOG.products().forEach(p -> catalogPrices.add((long) p.price()));
    List<Long> mentioned = dollars(text).stream().filter(catalogPrices::contains).toList();
    for (long a : named) {
      for (long b : named) {
        allowed.add(Math.abs(a - b));
      }
    }
    for (long a : mentioned) {
      for (long b : mentioned) {
        allowed.add(Math.abs(a - b));
      }
    }
    // Totales: cada producto nombrado con 0 a 10 unidades (por ejemplo, el total del carrito).
    Set<Long> totals = new HashSet<>(Set.of(0L));
    for (long price : named.stream().distinct().limit(6).toList()) {
      Set<Long> next = new HashSet<>();
      for (long total : totals) {
        for (int quantity = 0; quantity <= 10; quantity++) {
          next.add(total + quantity * price);
        }
      }
      totals = next;
    }
    allowed.addAll(totals);
    return allowed;
  }

  private static List<Long> dollars(String text) {
    List<Long> amounts = new ArrayList<>();
    Matcher matcher = DOLLARS.matcher(text);
    while (matcher.find()) {
      amounts.add(Long.parseLong(matcher.group(1).replace(",", "")));
    }
    return amounts;
  }

  /**
   * Imprime la memoria de la sesión y lo que suman las vueltas con tools: los
   * caracteres de los tool calls y sus resultados compactos, y una estimación de
   * tokens (4 caracteres por token).
   */
  private void printMemory(String session) {
    sessions.get(session).turns().forEach(turn -> {
      int toolChars = turn.toolRounds().stream().mapToInt(ToolRound::toolChars).sum();
      System.out.printf("smoke multi-turno: memoria [%s] user=\"%s\" toolRounds=%s "
              + "toolChars=%d (~%d tokens) assistant=\"%s\"%n", session, turn.user(),
          turn.toolRounds().stream().map(round -> round.calls().stream()
              .map(call -> call.name() + call.arguments() + "->" + call.result()).toList())
              .toList(),
          toolChars, Math.round(toolChars / 4.0), turn.assistant().replace('\n', ' '));
    });
  }

  private SseEvents turn(String session, String message) {
    pace();
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
    headers.add("X-Session-ID", session);
    long start = System.nanoTime();
    ResponseEntity<String> response = rest.postForEntity("/assistant/chat",
        new HttpEntity<>(Map.of("message", message), headers), String.class);
    long millis = (System.nanoTime() - start) / 1_000_000;
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    SseEvents events = SseEvents.parse(response.getBody());
    System.out.printf("smoke multi-turno [%s] %d ms \"%s\"%n  tools: %s%n  carrito: %s%n"
            + "  respuesta: %s%n", session, millis, message, events.tools(),
        events.cartUpdates(), events.text().replace('\n', ' '));
    assertThat(events.error()).as("evento error: %s", events.error()).isNull();
    assertThat(events.done()).as("evento done").isTrue();
    return events;
  }

  private void pace() {
    long wait = lastTurnStart + TURN_SPACING.toMillis() - System.currentTimeMillis();
    if (wait > 0) {
      try {
        Thread.sleep(wait);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    lastTurnStart = System.currentTimeMillis();
  }
}
