package com.amazon.sample.assistant.tools;

import static com.amazon.sample.assistant.tools.ToolsFixtures.indexed;
import static com.amazon.sample.assistant.tools.ToolsFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.amazon.sample.assistant.products.catalog.CatalogProduct;
import com.amazon.sample.assistant.products.catalog.CatalogUnavailableException;
import com.amazon.sample.assistant.products.embedding.EmbeddingProviderException;
import com.amazon.sample.assistant.products.search.IndexUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** {@code searchProducts}: los dos caminos de D4, el precio vivo y la degradación. */
class SearchProductsToolTest {

  private static final CatalogProduct DESK_LAMP = product("lamp1", "Brass Desk Lamp", 150,
      "lighting");
  private static final CatalogProduct FLOOR_LAMP = product("lamp2", "Arc Floor Lamp", 89,
      "lighting");
  private static final CatalogProduct TABLE_LAMP = product("lamp3", "Ceramic Table Lamp", 45,
      "lighting", "decor");
  private static final CatalogProduct PENDANT = product("lamp4", "Rattan Pendant", 120,
      "lighting");

  private final ToolsFixtures f = new ToolsFixtures();

  private void live(CatalogProduct... products) {
    for (CatalogProduct product : products) {
      when(f.catalog.getProduct(product.id())).thenReturn(Optional.of(product));
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> products(Map<String, Object> result) {
    return (List<Map<String, Object>>) result.get("products");
  }

  private static List<Object> field(Map<String, Object> result, String field) {
    return products(result).stream().map(p -> p.get(field)).toList();
  }

  @Test
  void livePriceReplacesThePayloadPrice() {
    // En el índice, la lámpara figura a $99; el catálogo dice $150.
    when(f.search.search("desk lamp", List.of(), null, null, 5))
        .thenReturn(List.of(indexed(DESK_LAMP, 99, 0.9f)));
    live(DESK_LAMP);

    Map<String, Object> result = f.tools.searchProducts("desk lamp", null, null, null, null, null,
        f.context);

    assertThat(result).containsEntry("source", "semantic").doesNotContainKey("error");
    assertThat(field(result, "price")).containsExactly(150);
    assertThat(products(result).get(0)).containsKeys("id", "name", "price", "tags",
        "description");
  }

  @Test
  void priceAscOrdersByTheLivePrice() {
    // Por score: escritorio, pie, mesa. Por precio vivo: mesa (45), pie (89), escritorio (150).
    when(f.search.search("lamp", List.of(), null, null, 5)).thenReturn(List.of(
        indexed(DESK_LAMP, 10, 0.9f), indexed(FLOOR_LAMP, 500, 0.8f),
        indexed(TABLE_LAMP, 300, 0.7f)));
    live(DESK_LAMP, FLOOR_LAMP, TABLE_LAMP);

    Map<String, Object> asc = f.tools.searchProducts("lamp", null, null, null, "price_asc", null,
        f.context);
    Map<String, Object> relevance = f.tools.searchProducts("lamp", null, null, null, null, null,
        f.context);

    assertThat(field(asc, "price")).containsExactly(45, 89, 150);
    assertThat(field(relevance, "name")).containsExactly(DESK_LAMP.name(), FLOOR_LAMP.name(),
        TABLE_LAMP.name());
  }

  @Test
  void priceRangeIsAppliedAgainOnTheLivePrice() {
    // El payload decía $90 (dentro de maxPrice 100), pero el precio vivo es $150.
    when(f.search.search("lamp", List.of("lighting"), null, 100, 5)).thenReturn(List.of(
        indexed(DESK_LAMP, 90, 0.9f), indexed(FLOOR_LAMP, 89, 0.8f)));
    live(DESK_LAMP, FLOOR_LAMP);

    Map<String, Object> result = f.tools.searchProducts("lamp", List.of("lighting"), null, 100,
        null, null, f.context);

    assertThat(field(result, "name")).containsExactly(FLOOR_LAMP.name());
  }

  @Test
  void productRemovedFromTheCatalogIsSkipped() {
    when(f.search.search("lamp", List.of(), null, null, 5)).thenReturn(List.of(
        indexed(DESK_LAMP, 150, 0.9f), indexed(FLOOR_LAMP, 89, 0.8f)));
    when(f.catalog.getProduct(DESK_LAMP.id())).thenReturn(Optional.empty());
    live(FLOOR_LAMP);

    assertThat(field(f.tools.searchProducts("lamp", null, null, null, null, null, f.context),
        "name")).containsExactly(FLOOR_LAMP.name());
  }

  @Test
  void catalogDownWhileHydratingIsCatalogUnavailable() {
    when(f.search.search("lamp", List.of(), null, null, 5))
        .thenReturn(List.of(indexed(DESK_LAMP, 150, 0.9f)));
    when(f.catalog.getProduct(DESK_LAMP.id()))
        .thenThrow(new CatalogUnavailableException("down", null));

    assertThat(f.tools.searchProducts("lamp", null, null, null, null, null, f.context))
        .containsEntry("error", "catalog-unavailable");
  }

  @Test
  void withoutQueryTheSemanticSearchIsNotUsed() {
    when(f.catalog.listProducts(List.of("seating"), null, 1, 50)).thenReturn(List.of());

    f.tools.searchProducts(null, List.of("seating"), null, null, null, null, f.context);

    verifyNoInteractions(f.search);
  }

  @Test
  @SuppressWarnings("unchecked")
  void lightingUnder100CheapestFirstWithoutQuery() {
    // El catálogo filtra por tag y ordena por precio; el precio máximo lo aplica el assistant.
    when(f.catalog.listProducts(List.of("lighting"), "price_asc", 1, 50))
        .thenReturn(List.of(TABLE_LAMP, FLOOR_LAMP, PENDANT, DESK_LAMP));

    Map<String, Object> result = f.tools.searchProducts(null, List.of("lighting"), null, 100,
        "price_asc", null, f.context);

    assertThat(result).containsEntry("source", "catalog");
    assertThat(field(result, "name")).containsExactly(TABLE_LAMP.name(), FLOOR_LAMP.name());
    assertThat(field(result, "price")).containsExactly(45, 89);
    assertThat(products(result)).allSatisfy(p ->
        assertThat((List<Object>) p.get("tags")).contains("lighting"));
    verifyNoInteractions(f.search);
  }

  @Test
  void relevanceOrderWithoutQueryUsesTheCatalogDefaultOrder() {
    when(f.catalog.listProducts(List.of("lighting"), null, 1, 50))
        .thenReturn(List.of(DESK_LAMP, TABLE_LAMP));

    Map<String, Object> result = f.tools.searchProducts(null, List.of("lighting"), null, null,
        "relevance", 1, f.context);

    assertThat(field(result, "name")).containsExactly(DESK_LAMP.name());
  }

  @Test
  void walksPagesUntilAnIncompleteOne() {
    List<CatalogProduct> full = IntStream.range(0, 50)
        .mapToObj(i -> product("seat" + i, "Chair " + i, 500 + i, "seating")).toList();
    CatalogProduct cheap = product("cheap", "Cheap Stool", 30, "seating");
    when(f.catalog.listProducts(List.of("seating"), null, 1, 50)).thenReturn(full);
    when(f.catalog.listProducts(List.of("seating"), null, 2, 50)).thenReturn(List.of(cheap));

    Map<String, Object> result = f.tools.searchProducts(null, List.of("seating"), null, 40, null,
        null, f.context);

    assertThat(field(result, "name")).containsExactly("Cheap Stool");
    verify(f.catalog, times(2)).listProducts(anyList(), isNull(), anyInt(), eq(50));
  }

  @Test
  void stopsAfterTenPages() {
    List<CatalogProduct> page = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      page.add(product("same" + i, "Chair " + i, 500, "seating"));
    }
    when(f.catalog.listProducts(anyList(), any(), anyInt(), anyInt())).thenReturn(page);

    f.tools.searchProducts(null, List.of("seating"), 0, 10, null, null, f.context);

    verify(f.catalog, times(10)).listProducts(anyList(), any(), anyInt(), anyInt());
  }

  @Test
  void indexUnavailableWithTagsDegradesToTheCatalog() {
    when(f.search.search("lamp", List.of("lighting"), null, 100, 5))
        .thenThrow(new IndexUnavailableException());
    when(f.catalog.listProducts(List.of("lighting"), null, 1, 50))
        .thenReturn(List.of(TABLE_LAMP, DESK_LAMP));

    Map<String, Object> result = f.tools.searchProducts("lamp", List.of("lighting"), null, 100,
        null, null, f.context);

    assertThat(result).containsEntry("source", "catalog")
        .containsEntry("degraded", "semantic-search-unavailable");
    assertThat(field(result, "name")).containsExactly(TABLE_LAMP.name());
  }

  @Test
  void embeddingErrorWithAPriceDegradesToTheCatalog() {
    when(f.search.search(any(), anyList(), any(), any(), any())).thenThrow(
        new EmbeddingProviderException(EmbeddingProviderException.Reason.QUOTA, null, "429",
            null));
    when(f.catalog.listProducts(List.of(), "price_asc", 1, 50))
        .thenReturn(List.of(TABLE_LAMP, FLOOR_LAMP));

    Map<String, Object> result = f.tools.searchProducts("lamp", null, null, 50, "price_asc", null,
        f.context);

    assertThat(result).containsEntry("degraded", "semantic-search-unavailable");
    assertThat(field(result, "name")).containsExactly(TABLE_LAMP.name());
  }

  @Test
  void semanticSearchDownWithoutOtherCriteriaIsSearchUnavailable() {
    when(f.search.search(any(), anyList(), any(), any(), any())).thenThrow(
        new EmbeddingProviderException(EmbeddingProviderException.Reason.UNAVAILABLE, null,
            "503", null));

    Map<String, Object> result = f.tools.searchProducts("lamp", null, null, null, null, null,
        f.context);

    assertThat(result).containsEntry("error", "search-unavailable");
    assertThat((String) result.get("message")).contains("embedding-provider-unavailable");
    verifyNoInteractions(f.catalog);
  }

  @Test
  void descriptionsAreTruncated() {
    CatalogProduct verbose = new CatalogProduct(ToolsFixtures.id("verbose"), "Verbose Lamp",
        "d".repeat(400), 50, List.of(new CatalogProduct.Tag("lighting", "Lighting")));
    when(f.catalog.listProducts(List.of("lighting"), null, 1, 50)).thenReturn(List.of(verbose));

    Map<String, Object> result = f.tools.searchProducts(null, List.of("lighting"), null, null,
        null, null, f.context);

    assertThat((String) products(result).get(0).get("description")).hasSize(301).endsWith("…");
  }
}
