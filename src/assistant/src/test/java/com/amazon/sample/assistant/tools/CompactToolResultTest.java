package com.amazon.sample.assistant.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Resultado compacto de las tools para la memoria (corrección posterior de D7). */
class CompactToolResultTest {

  @Test
  void searchKeepsIdNameAndPriceOfEachProduct() {
    String result = "{\"products\":[{\"id\":\"a\",\"name\":\"Aiden\",\"price\":139,"
        + "\"tags\":[\"velvet\"],\"description\":\"A long description\"}],\"source\":\"semantic\"}";

    assertThat(CompactToolResult.of(result))
        .isEqualTo("{\"products\":[{\"id\":\"a\",\"name\":\"Aiden\",\"price\":139}]}");
  }

  @Test
  void detailsKeepIdNameAndPrice() {
    String result = "{\"id\":\"a\",\"name\":\"Aiden\",\"price\":139,\"tags\":[\"velvet\"],"
        + "\"description\":\"A long description\"}";

    assertThat(CompactToolResult.of(result))
        .isEqualTo("{\"id\":\"a\",\"name\":\"Aiden\",\"price\":139}");
  }

  @Test
  void addToCartKeepsTheAddedItemAndTheCount() {
    String result = "{\"added\":{\"id\":\"a\",\"name\":\"Aiden\",\"quantity\":2,"
        + "\"unitPrice\":139},\"cartItemCount\":3}";

    assertThat(CompactToolResult.of(result)).isEqualTo(result);
  }

  @Test
  void errorsKeepOnlyTheType() {
    String result = "{\"error\":\"cart-unavailable\",\"message\":\"The cart service is not "
        + "available; the product was NOT added to the cart\"}";

    assertThat(CompactToolResult.of(result)).isEqualTo("{\"error\":\"cart-unavailable\"}");
  }

  @Test
  void unknownOrInvalidResultsAreTruncated() {
    String longText = "x".repeat(500);

    assertThat(CompactToolResult.of(longText))
        .hasSize(CompactToolResult.MAX_UNKNOWN_CHARS + 1).endsWith("…");
    assertThat(CompactToolResult.of(null)).isEmpty();
    assertThat(CompactToolResult.of("{\"other\":1}")).isEqualTo("{\"other\":1}");
  }
}
