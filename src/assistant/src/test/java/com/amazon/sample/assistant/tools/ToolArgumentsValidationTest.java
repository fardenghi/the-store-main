package com.amazon.sample.assistant.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Validación de los argumentos de las tools en el servidor (D3, spec
 * "Validación de los argumentos de las tools"): cada caso inválido devuelve un
 * error que nombra el argumento y no llama a ningún servicio.
 */
class ToolArgumentsValidationTest {

  private static final String VALID_ID = ToolsFixtures.id("p1");

  private final ToolsFixtures f = new ToolsFixtures();

  @AfterEach
  void noServiceWasCalled() {
    verifyNoInteractions(f.catalog, f.carts, f.search);
  }

  private static void assertInvalid(Map<String, Object> result, String argument) {
    assertThat(result).containsEntry("error", "invalid-argument")
        .containsEntry("argument", argument)
        .containsKey("message");
  }

  @Test
  void unknownTagIsRejectedWithTheValidTags() {
    Map<String, Object> result = f.tools.searchProducts("lamp", List.of("lamps"), null, null, null,
        null, f.context);

    assertInvalid(result, "tags");
    assertThat((String) result.get("message")).contains("lamps");
    assertThat(result.get("validTags")).isEqualTo(ToolsFixtures.TAGS);
  }

  @Test
  void tagsAreNormalizedBeforeValidating() {
    // " Lighting " y "lighting" son el mismo tag; "Lamps" no existe.
    Map<String, Object> result = f.tools.searchProducts(null, List.of(" Lighting ", "Lamps"),
        null, null, null, null, f.context);

    assertInvalid(result, "tags");
    assertThat((String) result.get("message")).contains("lamps").doesNotContain("lighting,");
  }

  @Test
  void unavailableTagListIsCatalogUnavailable() {
    when(f.tagsCache.tagNames()).thenReturn(List.of());

    Map<String, Object> result = f.tools.searchProducts(null, List.of("lighting"), null, null,
        null, null, f.context);

    assertThat(result).containsEntry("error", "catalog-unavailable");
  }

  @Test
  void minPriceGreaterThanMaxPriceIsRejected() {
    assertInvalid(f.tools.searchProducts("table", null, 500, 100, null, null, f.context),
        "minPrice");
  }

  @Test
  void negativePricesAreRejected() {
    assertInvalid(f.tools.searchProducts("table", null, -1, null, null, null, f.context),
        "minPrice");
    assertInvalid(f.tools.searchProducts("table", null, null, -5, null, null, f.context),
        "maxPrice");
  }

  @Test
  void limitOutOfRangeIsRejected() {
    assertInvalid(f.tools.searchProducts("table", null, null, null, null, 0, f.context), "limit");
    assertInvalid(f.tools.searchProducts("table", null, null, null, null, 11, f.context),
        "limit");
  }

  @Test
  void unknownOrderIsRejected() {
    assertInvalid(f.tools.searchProducts("table", null, null, null, "cheapest", null, f.context),
        "order");
  }

  @Test
  void queryLongerThan200CharactersIsRejected() {
    assertInvalid(f.tools.searchProducts("x".repeat(201), null, null, null, null, null,
        f.context), "query");
  }

  @Test
  void searchWithoutAnyCriterionIsMissingCriteria() {
    Map<String, Object> result = f.tools.searchProducts("  ", List.of(), null, null, "price_asc",
        null, f.context);

    assertThat(result).containsEntry("error", "missing-criteria");
  }

  @Test
  void productIdThatIsNotAUuidIsRejected() {
    assertInvalid(f.tools.getProductDetails("aiden-armchair", f.context), "productId");
    assertInvalid(f.tools.addToCart("12345", 1, f.context), "productId");
    assertInvalid(f.tools.addToCart(null, 1, f.context), "productId");
  }

  @Test
  void quantityOutOfRangeIsRejected() {
    assertInvalid(f.tools.addToCart(VALID_ID, 500, f.context), "quantity");
    assertInvalid(f.tools.addToCart(VALID_ID, 0, f.context), "quantity");
    assertInvalid(f.tools.addToCart(VALID_ID, 11, f.context), "quantity");
  }

  @Test
  void invalidCallsEmitAFailedToolEvent() {
    f.tools.addToCart(VALID_ID, 500, f.context);

    assertThat(f.turn.outcomes()).containsExactly("addToCart:invalid-argument");
  }
}
