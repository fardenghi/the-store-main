package com.amazon.sample.assistant.smoke;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amazon.sample.assistant.products.catalog.CatalogProduct;
import com.amazon.sample.assistant.products.index.ProductIndexer;
import com.amazon.sample.assistant.products.vector.QdrantTestSupport;
import io.qdrant.client.QdrantClient;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.beans.factory.annotation.Value;
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
 * De punta a punta en el proceso (task 9.1 de {@code add-assistant-tools}): el
 * {@code assistant} completo con NVIDIA y Gemini reales, Qdrant, el catálogo
 * real servido por {@link FakeCatalog} y un {@code carts} en memoria
 * ({@link FakeCarts}). Recorre los escenarios de la spec {@code assistant-tools}
 * por {@code POST /assistant/chat}.
 *
 * <p>Gasta unas 40 requests a NVIDIA (12 turnos de 3 a 4, espaciados; el
 * limitador del {@code assistant} también las acota) y, en Gemini, un embedding
 * por turno con búsqueda más uno por cada consulta de {@code searchProducts}.
 * Igual que {@code ChatEndToEndSmokeIT}, con {@code -Dsmoke.qdrant.host},
 * {@code -Dsmoke.qdrant.port} y {@code -Dsmoke.qdrant.collection} reutiliza una
 * colección ya indexada y no gasta las 80 requests de la indexación.
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
class ToolsEndToEndSmokeIT {

  /** Espacio entre turnos: hasta 4 requests cada 7 s, por debajo de los 36 del limitador. */
  private static final Duration TURN_SPACING = Duration.ofSeconds(7);

  private static final String AIDEN = "Aiden Mid-Century Velvet Armchair";
  private static final String DESK_LAMP = "Curved Brass and Walnut Desk Lamp";

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
  private QdrantClient qdrant;

  @Value("${spring.ai.vectorstore.qdrant.collection-name}")
  private String collection;

  private final ListAppender<ILoggingEvent> toolLog = new ListAppender<>();
  private final ListAppender<ILoggingEvent> turnLog = new ListAppender<>();
  private long lastTurnStart;

  @BeforeAll
  void index() throws Exception {
    System.out.printf("smoke tools: sincronización %s%n", indexer.sync());
    toolLog.start();
    turnLog.start();
    ((Logger) LoggerFactory.getLogger("assistant.tool")).addAppender(toolLog);
    ((Logger) LoggerFactory.getLogger("assistant.turn")).addAppender(turnLog);
  }

  @AfterAll
  void close() {
    ((Logger) LoggerFactory.getLogger("assistant.tool")).detachAppender(toolLog);
    ((Logger) LoggerFactory.getLogger("assistant.turn")).detachAppender(turnLog);
    CATALOG.close();
    CARTS.close();
  }

  @Test
  @Order(1)
  void priceRightNowComesFromGetProductDetails() {
    CatalogProduct aiden = CATALOG.byName(AIDEN).orElseThrow();
    CATALOG.requests().clear();

    SseEvents response = turn("price-tl", "how much is the " + AIDEN + " right now?");

    assertThat(response.toolEvents("getProductDetails")).as("tool getProductDetails")
        .anySatisfy(event -> assertThat(event.get("ok")).isEqualTo(true));
    assertThat(CATALOG.requests()).contains("/catalog/products/" + aiden.id());
    assertThat(response.text()).contains("$" + aiden.price());
  }

  @Test
  @Order(2)
  void lampsUnder100CheapestFirst() {
    SseEvents response = turn("lamps-tl", "show me lamps under $100, cheapest first");

    String line = toolLine("lamps-tl", "searchProducts");
    assertThat(line).containsPattern("tags=\\[[^\\]]*lighting").contains("maxPrice=100")
        .contains("order=\"price_asc\"");
    List<CatalogProduct> named = named(response.text());
    assertThat(named).as("productos presentados").isNotEmpty().allSatisfy(product -> {
      assertThat(product.tagNames()).contains("lighting");
      assertThat(product.price()).isLessThanOrEqualTo(100);
    });
    assertThat(named).isSortedAccordingTo(Comparator.comparingInt(CatalogProduct::price));
    assertNamedProductsAreInTheEvents(response);
  }

  @Test
  @Order(3)
  void diningTableUnder300InSpanish() {
    SseEvents response = turn("spanish-tl", "busco una mesa de comedor de menos de 300 dólares");

    String line = toolLine("spanish-tl", "searchProducts");
    assertThat(line).containsPattern("tags=\\[[^\\]]*(tables|dining)").contains("maxPrice=300");
    List<CatalogProduct> named = named(response.text());
    assertThat(named).as("productos presentados").isNotEmpty().allSatisfy(product -> {
      assertThat(product.tagNames()).containsAnyOf("tables", "dining");
      assertThat(product.price()).isLessThanOrEqualTo(300);
    });
    long spanishWords = Pattern.compile("\\b(el|la|los|las|para|tu|una|un|que|con|es|de)\\b")
        .matcher(response.text().toLowerCase(Locale.ROOT)).results().count();
    assertThat(spanishWords).isGreaterThanOrEqualTo(5);
    assertNamedProductsAreInTheEvents(response);
  }

  @Test
  @Order(4)
  void addThatOneToMyCart() {
    CatalogProduct aiden = CATALOG.byName(AIDEN).orElseThrow();
    turn("addone-tl", "tell me about the " + AIDEN);

    SseEvents response = turn("addone-tl", "add that one to my cart");

    assertThat(CARTS.items("addone-tl"))
        .containsExactly(new FakeCarts.Item(aiden.id(), 1, aiden.price()));
    assertThat(response.cartUpdates()).singleElement().satisfies(event -> {
      assertThat(event.get("itemId")).isEqualTo(aiden.id());
      assertThat(event.get("quantity")).isEqualTo(1);
      assertThat(event.get("unitPrice")).isEqualTo(aiden.price());
      assertThat(event.get("cartItemCount")).isEqualTo(1);
    });
    assertThat(response.toolEvents("addToCart")).singleElement()
        .satisfies(event -> assertThat(event.get("ok")).isEqualTo(true));
    // Solo cambió el carrito de la sesión.
    assertThat(CARTS.items("price-tl")).isEmpty();
    assertThat(CARTS.items("lamps-tl")).isEmpty();
  }

  @Test
  @Order(5)
  void addTwoOfTheFirstOne() {
    SseEvents first = turn("addtwo-tl", "show me some dining chairs");
    List<String> shown = first.allProducts().stream().map(p -> (String) p.get("id")).toList();

    SseEvents response = turn("addtwo-tl", "add two of the first one to my cart");

    assertThat(CARTS.items("addtwo-tl")).singleElement().satisfies(item -> {
      assertThat(item.quantity()).isEqualTo(2);
      assertThat(shown).contains(item.itemId());
      assertThat(item.unitPrice()).isEqualTo(CATALOG.byId(item.itemId()).orElseThrow().price());
    });
    assertThat(response.cartUpdates()).singleElement()
        .satisfies(event -> assertThat(event.get("quantity")).isEqualTo(2));
  }

  @Test
  @Order(6)
  void ambiguousRequestAsksInsteadOfAdding() {
    SseEvents first = turn("ambig-tl", "show me three lamps");
    assertThat(named(first.text()).stream().filter(p -> p.tagNames().contains("lighting")))
        .as("el primer turno muestra varias lámparas").hasSizeGreaterThanOrEqualTo(2);

    SseEvents response = turn("ambig-tl", "add the lamp to my cart");

    assertThat(CARTS.items("ambig-tl")).isEmpty();
    assertThat(response.cartUpdates()).isEmpty();
    assertThat(response.text()).contains("?");
  }

  @Test
  @Order(7)
  void cartsDownIsNotConfirmed() {
    CARTS.down(true);
    try {
      SseEvents response = turn("down-tl", "add the " + AIDEN + " to my cart");

      assertThat(response.cartUpdates()).isEmpty();
      assertThat(response.toolEvents("addToCart")).isNotEmpty().allSatisfy(event -> {
        assertThat(event.get("ok")).isEqualTo(false);
        assertThat(event.get("error")).isEqualTo("cart-unavailable");
      });
      assertThat(response.text().toLowerCase(Locale.ROOT)).containsPattern(
          "(couldn't|could not|unable|wasn't|was not|not been added|failed|can't|cannot|"
              + "not added|didn't|did not|unavailable|down|offline|retry|try again)");
    } finally {
      CARTS.down(false);
    }
  }

  @Test
  @Order(8)
  void stalePayloadPriceIsNotShown() throws Exception {
    CatalogProduct lamp = CATALOG.byName(DESK_LAMP).orElseThrow();
    setPayloadPrice(lamp, 7);
    try {
      SseEvents response = turn("payload-tl", "how much is the " + DESK_LAMP + " right now?");

      assertThat(response.text()).doesNotContainPattern("\\$\\s?7(?![0-9])")
          .contains("$" + lamp.price());
    } finally {
      setPayloadPrice(lamp, lamp.price());
    }
  }

  private void setPayloadPrice(CatalogProduct product, int price) throws Exception {
    qdrant.setPayloadAsync(collection, Map.of("price", value(price)),
        List.of(id(UUID.fromString(product.id()))), true, null, null).get();
    System.out.printf("smoke tools: precio del payload de %s -> %d%n", product.name(), price);
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
    System.out.printf("smoke tools [%s] %d ms \"%s\"%n  tools: %s%n  carrito: %s%n"
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

  /**
   * Línea {@code assistant.tool} de la tool en la sesión. El log trunca la
   * sesión a 8 caracteres, así que las sesiones de este test difieren en ellos.
   */
  private String toolLine(String session, String tool) {
    String prefix = "session=" + session.substring(0, Math.min(8, session.length())) + " tool="
        + tool;
    synchronized (toolLog) {
      for (ILoggingEvent event : toolLog.list) {
        if (event.getFormattedMessage().startsWith(prefix)) {
          System.out.println("smoke tools: " + event.getFormattedMessage());
          return event.getFormattedMessage();
        }
      }
    }
    throw new AssertionError("No hubo " + tool + " en la sesión " + session);
  }

  /** Productos del catálogo que el texto nombra, en el orden en que aparecen. */
  private static List<CatalogProduct> named(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    return CATALOG.products().stream()
        .filter(p -> lower.contains(p.name().toLowerCase(Locale.ROOT)))
        .sorted(Comparator.comparingInt(p -> lower.indexOf(p.name().toLowerCase(Locale.ROOT))))
        .toList();
  }

  /** Spec "Nombres verificables": cada producto nombrado está en un evento, con su precio. */
  private static void assertNamedProductsAreInTheEvents(SseEvents events) {
    List<Map<String, Object>> all = events.allProducts();
    for (CatalogProduct product : named(events.text())) {
      assertThat(all).as("%s en products o en un evento tool", product.name())
          .anySatisfy(p -> {
            assertThat(p.get("id")).isEqualTo(product.id());
            assertThat(((Number) p.get("price")).longValue()).isEqualTo(product.price());
          });
    }
  }
}
