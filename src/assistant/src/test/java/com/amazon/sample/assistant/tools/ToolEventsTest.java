package com.amazon.sample.assistant.tools;

import static com.amazon.sample.assistant.tools.ToolsFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amazon.sample.assistant.carts.CartsClient;
import com.amazon.sample.assistant.carts.CartsUnavailableException;
import com.amazon.sample.assistant.chat.session.ShownProduct;
import com.amazon.sample.assistant.products.catalog.CatalogProduct;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import reactor.test.StepVerifier;

/** Eventos {@code tool} y {@code cart-updated} (D7) y línea {@code assistant.tool} (D12). */
class ToolEventsTest {

  private static final CatalogProduct ARMCHAIR = product("armchair",
      "Aiden Mid-Century Velvet Armchair", 139, "seating", "velvet");
  private static final CatalogProduct LAMP = product("lamp", "Ceramic Table Lamp", 45,
      "lighting");

  private final ToolsFixtures f = new ToolsFixtures();
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(ToolLogger.LOGGER_NAME);

  @BeforeEach
  void setUp() {
    appender.start();
    logger.addAppender(appender);
    when(f.catalog.getProduct(ARMCHAIR.id())).thenReturn(Optional.of(ARMCHAIR));
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
  }

  private static boolean isEvent(ServerSentEvent<?> event, String name, Map<String, Object> data) {
    return name.equals(event.event()) && data.equals(event.data());
  }

  @Test
  void addToCartEmitsToolThenCartUpdated() {
    when(f.carts.getCart("session-0123456789")).thenReturn(new CartsClient.Cart(
        "session-0123456789", List.of(new CartsClient.Item("x", 2, 10),
            new CartsClient.Item(ARMCHAIR.id(), 1, 139))));

    f.tools.addToCart(ARMCHAIR.id(), 1, f.context);
    f.sink.tryEmitComplete();

    StepVerifier.create(f.sink.asFlux())
        .expectNextMatches(event -> isEvent(event, "tool",
            Map.of("tool", "addToCart", "ok", true)))
        .expectNextMatches(event -> isEvent(event, "cart-updated", Map.of("itemId", ARMCHAIR.id(),
            "name", ARMCHAIR.name(), "quantity", 1, "unitPrice", 139, "cartItemCount", 3)))
        .expectComplete()
        .verify(Duration.ofSeconds(1));
  }

  @Test
  void failedCartReadOmitsTheItemCount() {
    when(f.carts.getCart(anyString())).thenThrow(new CartsUnavailableException("down", null));

    Map<String, Object> result = f.tools.addToCart(ARMCHAIR.id(), 1, f.context);
    f.sink.tryEmitComplete();

    assertThat(result).containsKey("added").doesNotContainKey("cartItemCount");
    StepVerifier.create(f.sink.asFlux())
        .expectNextMatches(event -> isEvent(event, "tool",
            Map.of("tool", "addToCart", "ok", true)))
        .expectNextMatches(event -> isEvent(event, "cart-updated", Map.of("itemId", ARMCHAIR.id(),
            "name", ARMCHAIR.name(), "quantity", 1, "unitPrice", 139)))
        .expectComplete()
        .verify(Duration.ofSeconds(1));
  }

  @Test
  void failedAddEmitsAFailedToolEventAndNoCartUpdated() {
    org.mockito.Mockito.doThrow(new CartsUnavailableException("500", null)).when(f.carts)
        .addItem(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt());

    f.tools.addToCart(ARMCHAIR.id(), 1, f.context);
    f.sink.tryEmitComplete();

    StepVerifier.create(f.sink.asFlux())
        .expectNextMatches(event -> isEvent(event, "tool",
            Map.of("tool", "addToCart", "ok", false, "error", "cart-unavailable")))
        .expectComplete()
        .verify(Duration.ofSeconds(1));
  }

  @Test
  void searchAndDetailsEventsCarryTheirProducts() {
    when(f.catalog.listProducts(List.of("lighting"), null, 1, 50)).thenReturn(List.of(LAMP));

    f.tools.searchProducts(null, List.of("lighting"), null, null, null, null, f.context);
    f.tools.getProductDetails(ARMCHAIR.id(), f.context);
    f.sink.tryEmitComplete();

    StepVerifier.create(f.sink.asFlux())
        .expectNextMatches(event -> isEvent(event, "tool", Map.of("tool", "searchProducts",
            "ok", true, "products", List.of(Map.of("id", LAMP.id(), "name", LAMP.name(),
                "price", 45L)))))
        .expectNextMatches(event -> isEvent(event, "tool", Map.of("tool", "getProductDetails",
            "ok", true, "products", List.of(Map.of("id", ARMCHAIR.id(), "name", ARMCHAIR.name(),
                "price", 139L)))))
        .expectComplete()
        .verify(Duration.ofSeconds(1));
    assertThat(f.turn.shownProducts()).extracting(ShownProduct::id)
        .containsExactly(LAMP.id(), ARMCHAIR.id());
    assertThat(f.turn.outcomes()).containsExactly("searchProducts:ok", "getProductDetails:ok");
  }

  @Test
  void logLineHasAllFieldsAndNotTheFullSession() {
    when(f.carts.getCart(anyString())).thenReturn(new CartsClient.Cart("s", List.of()));

    f.tools.addToCart(ARMCHAIR.id(), 2, f.context);

    assertThat(appender.list).hasSize(1);
    String line = appender.list.get(0).getFormattedMessage();
    assertThat(line).startsWith("session=session- tool=addToCart ")
        .contains("args={productId=\"" + ARMCHAIR.id() + "\", quantity=2}")
        .contains("outcome=ok", "products=0", "catalogCalls=1", "cartsCalls=2")
        .containsPattern("latencyMs=\\d+$")
        .doesNotContain("session-0123456789");
  }

  @Test
  void toolBudgetIsEnforcedPerTurn() {
    ToolsFixtures small = new ToolsFixtures(new com.amazon.sample.assistant.config.ToolsProperties(
        4, 2, 10, 5, 10, 300, ToolsFixtures.PROPERTIES.http(), null));
    when(small.catalog.getProduct(ARMCHAIR.id())).thenReturn(Optional.of(ARMCHAIR));

    small.tools.getProductDetails(ARMCHAIR.id(), small.context);
    small.tools.getProductDetails(ARMCHAIR.id(), small.context);
    Map<String, Object> third = small.tools.getProductDetails(ARMCHAIR.id(), small.context);

    assertThat(third).containsEntry("error", "tool-budget-exhausted");
    org.mockito.Mockito.verify(small.catalog, org.mockito.Mockito.times(2))
        .getProduct(ARMCHAIR.id());
    assertThat(small.turn.outcomes()).containsExactly("getProductDetails:ok",
        "getProductDetails:ok", "getProductDetails:tool-budget-exhausted");
  }
}
