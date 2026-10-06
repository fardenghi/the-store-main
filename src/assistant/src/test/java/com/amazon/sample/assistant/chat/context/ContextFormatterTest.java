package com.amazon.sample.assistant.chat.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.assistant.chat.session.ShownProduct;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Sección de contexto del system prompt (D5). */
class ContextFormatterTest {

  private static final ShownProduct ARMCHAIR = new ShownProduct("a1",
      "Aiden Mid-Century Velvet Armchair", "Plush velvet armchair\nwith tapered legs.", 139,
      List.of("living-room", "seating", "velvet"));
  private static final ShownProduct SOFA = new ShownProduct("s1", "Eva Tufted Velvet Sofa",
      "Three-seat sofa.", 1769, List.of("seating", "velvet"));

  @Test
  void withProductsAndPrevious() {
    String context = ContextFormatter.format(
        new Retrieval(true, false, List.of(ARMCHAIR), List.of(SOFA), false, 5));

    assertThat(context).contains("Products found for this message")
        .contains("- [a1] Aiden Mid-Century Velvet Armchair | $139 | tags: living-room, seating, "
            + "velvet | Plush velvet armchair with tapered legs.")
        .contains("Previously shown products")
        .contains("- [s1] Eva Tufted Velvet Sofa | $1769 | tags: seating, velvet | Three-seat sofa.")
        .doesNotContain("CATALOG UNAVAILABLE");
    assertThat(context.indexOf("[a1]")).isLessThan(context.indexOf("Previously shown"));
  }

  @Test
  void onlyPreviousProducts() {
    String context = ContextFormatter.format(
        new Retrieval(false, false, List.of(), List.of(ARMCHAIR, SOFA), false, 0));

    assertThat(context).contains("No catalog search was made for this message.")
        .contains("Previously shown products")
        .contains("[a1] Aiden Mid-Century Velvet Armchair | $139")
        .contains("[s1] Eva Tufted Velvet Sofa | $1769");
    assertThat(context.indexOf("[a1]")).isLessThan(context.indexOf("[s1]"));
  }

  @Test
  void searchWithoutResults() {
    String context = ContextFormatter.format(
        new Retrieval(true, false, List.of(), List.of(), false, 3));

    assertThat(context).contains("Products found for this message: none")
        .contains("Previously shown products: none.");
  }

  @Test
  void catalogUnavailable() {
    String context = ContextFormatter.format(
        new Retrieval(true, true, List.of(), List.of(), false, 3));

    assertThat(context).contains("CATALOG UNAVAILABLE")
        .contains("Do not name, describe or recommend any product")
        .doesNotContain("[");
  }

  @Test
  void longDescriptionsAreTruncated() {
    ShownProduct verbose = new ShownProduct("v", "Verbose", "d".repeat(1000), 1, List.of());

    assertThat(ContextFormatter.line(verbose)).hasSizeLessThan(450).endsWith("…");
  }
}
