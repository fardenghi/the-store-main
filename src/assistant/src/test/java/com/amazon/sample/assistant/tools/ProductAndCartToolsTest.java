package com.amazon.sample.assistant.tools;

import static com.amazon.sample.assistant.tools.ToolsFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.amazon.sample.assistant.carts.CartsClient;
import com.amazon.sample.assistant.carts.CartsUnavailableException;
import com.amazon.sample.assistant.products.catalog.CatalogProduct;
import com.amazon.sample.assistant.products.catalog.CatalogUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** {@code getProductDetails} y {@code addToCart} (D6): precio vivo y carrito de la sesión. */
class ProductAndCartToolsTest {

  private static final CatalogProduct ARMCHAIR = product("armchair",
      "Aiden Mid-Century Velvet Armchair", 139, "seating", "velvet");

  private final ToolsFixtures f = new ToolsFixtures();

  @Test
  void detailsAreReadFromTheCatalogOnEveryCall() {
    when(f.catalog.getProduct(ARMCHAIR.id())).thenReturn(Optional.of(ARMCHAIR));

    Map<String, Object> first = f.tools.getProductDetails(ARMCHAIR.id(), f.context);
    f.tools.getProductDetails(ARMCHAIR.id(), f.context);

    assertThat(first).containsEntry("id", ARMCHAIR.id())
        .containsEntry("name", ARMCHAIR.name())
        .containsEntry("price", 139)
        .containsEntry("tags", List.of("seating", "velvet"))
        .containsKey("description");
    verify(f.catalog, times(2)).getProduct(ARMCHAIR.id());
  }

  @Test
  void unknownProductIsProductNotFound() {
    when(f.catalog.getProduct(any())).thenReturn(Optional.empty());

    assertThat(f.tools.getProductDetails(ARMCHAIR.id(), f.context))
        .containsEntry("error", "product-not-found");
  }

  @Test
  void catalogDownIsCatalogUnavailable() {
    when(f.catalog.getProduct(any())).thenThrow(new CatalogUnavailableException("down", null));

    assertThat(f.tools.getProductDetails(ARMCHAIR.id(), f.context))
        .containsEntry("error", "catalog-unavailable");
  }

  @Test
  void addToCartPostsToTheSessionCartWithTheCatalogPrice() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://carts");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    StoreTools tools = new StoreTools(f.catalog, new CartsClient(builder.build()), f.search,
        new ToolArguments(f.tagsCache, ToolsFixtures.PROPERTIES), ToolsFixtures.PROPERTIES);
    // El catálogo dice $149, aunque el contexto del chat haya mostrado $139.
    CatalogProduct repriced = new CatalogProduct(ARMCHAIR.id(), ARMCHAIR.name(),
        ARMCHAIR.description(), 149, ARMCHAIR.tags());
    when(f.catalog.getProduct(ARMCHAIR.id())).thenReturn(Optional.of(repriced));
    server.expect(requestTo("http://carts/carts/session-0123456789/items"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().json("{\"itemId\":\"" + ARMCHAIR.id()
            + "\",\"quantity\":2,\"unitPrice\":149}", true))
        .andRespond(withStatus(HttpStatus.CREATED));
    server.expect(requestTo("http://carts/carts/session-0123456789"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"customerId\":\"session-0123456789\",\"items\":[{\"itemId\":\""
            + ARMCHAIR.id() + "\",\"quantity\":2,\"unitPrice\":149}]}",
            MediaType.APPLICATION_JSON));

    Map<String, Object> result = tools.addToCart(ARMCHAIR.id(), 2, f.context);

    server.verify();
    assertThat(result).containsEntry("added", Map.of("id", ARMCHAIR.id(), "name",
        ARMCHAIR.name(), "quantity", 2, "unitPrice", 149)).containsEntry("cartItemCount", 2);
  }

  @Test
  void secondAddOfTheSameProductInTheTurnDoesNotCallCarts() {
    when(f.catalog.getProduct(ARMCHAIR.id())).thenReturn(Optional.of(ARMCHAIR));
    when(f.carts.getCart(anyString())).thenReturn(new CartsClient.Cart("s", List.of()));

    f.tools.addToCart(ARMCHAIR.id(), 1, f.context);
    Map<String, Object> second = f.tools.addToCart(ARMCHAIR.id(), 1, f.context);

    assertThat(second).containsEntry("error", "already-added-this-turn");
    verify(f.carts, times(1)).addItem(anyString(), anyString(), anyInt(), anyInt());
  }

  @Test
  void cartsErrorIsCartUnavailable() {
    when(f.catalog.getProduct(ARMCHAIR.id())).thenReturn(Optional.of(ARMCHAIR));
    org.mockito.Mockito.doThrow(new CartsUnavailableException("500", null)).when(f.carts)
        .addItem(anyString(), anyString(), anyInt(), anyInt());

    Map<String, Object> result = f.tools.addToCart(ARMCHAIR.id(), null, f.context);

    assertThat(result).containsEntry("error", "cart-unavailable");
    verify(f.carts, never()).getCart(anyString());
    // Un agregado fallido no cuenta como agregado: se puede volver a intentar.
    assertThat(f.turn.alreadyAdded(ARMCHAIR.id())).isFalse();
  }

  @Test
  void unknownProductIsNotAddedToTheCart() {
    when(f.catalog.getProduct(any())).thenReturn(Optional.empty());

    assertThat(f.tools.addToCart(ARMCHAIR.id(), 1, f.context))
        .containsEntry("error", "product-not-found");
    verify(f.carts, never()).addItem(anyString(), anyString(), anyInt(), anyInt());
  }

  @Test
  void theCartIsAlwaysTheSessionOfTheContext() {
    when(f.catalog.getProduct(ARMCHAIR.id())).thenReturn(Optional.of(ARMCHAIR));
    when(f.carts.getCart(anyString())).thenReturn(new CartsClient.Cart("s", List.of()));
    TurnToolContext other = new TurnToolContext("other-session", f.sink, 6);

    f.tools.addToCart(ARMCHAIR.id(), 1, new ToolContext(other.asToolContext()));

    verify(f.carts).addItem("other-session", ARMCHAIR.id(), 1, 139);
  }

  @Test
  void toolSchemasHaveNoCustomerNorSessionParameter() throws Exception {
    ToolCallback[] callbacks = MethodToolCallbackProvider.builder().toolObjects(f.tools).build()
        .getToolCallbacks();
    ObjectMapper json = new ObjectMapper();

    assertThat(Arrays.stream(callbacks).map(c -> c.getToolDefinition().name()))
        .containsExactlyInAnyOrder("searchProducts", "getProductDetails", "addToCart");
    for (ToolCallback callback : callbacks) {
      String schema = callback.getToolDefinition().inputSchema();
      assertThat(schema.toLowerCase()).doesNotContain("customer").doesNotContain("session")
          .doesNotContain("toolcontext");
    }
    ToolCallback addToCart = Arrays.stream(callbacks)
        .filter(c -> c.getToolDefinition().name().equals("addToCart")).findFirst().orElseThrow();
    JsonNode schema = json.readTree(addToCart.getToolDefinition().inputSchema());
    assertThat(schema.path("properties").properties()).extracting(Map.Entry::getKey)
        .containsExactlyInAnyOrder("productId", "quantity");
    assertThat(schema.path("required")).extracting(JsonNode::asText)
        .containsExactly("productId");
  }
}
