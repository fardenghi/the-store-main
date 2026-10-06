package com.amazon.sample.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tool calls escritos como texto por el modelo (corrección posterior). */
class TextualToolCallsTest {

  private final TextualToolCalls textual = new TextualToolCalls(
      Set.of("searchProducts", "getProductDetails", "addToCart"));

  @Test
  void aSentenceThatIsOnlyAToolCallBecomesACall() {
    String rest = textual.extract("[addToCart: {\"productId\": \"7c931d3b\", \"quantity\": 2}]");

    assertThat(rest).isEmpty();
    assertThat(textual.calls()).singleElement().satisfies(call -> {
      assertThat(call.name()).isEqualTo("addToCart");
      assertThat(call.arguments()).isEqualTo("{\"productId\": \"7c931d3b\", \"quantity\": 2}");
      assertThat(call.id()).startsWith("text-call-");
    });
  }

  @Test
  void textAroundTheCallIsKept() {
    String rest = textual.extract("Scanning inventory: [searchProducts: {\"tags\": [\"lighting\","
        + " \"office\"], \"query\": \"desk lamp\"}]\n");

    assertThat(rest).isEqualTo("Scanning inventory: \n");
    assertThat(textual.calls()).extracting(call -> call.name()).containsExactly("searchProducts");
  }

  @Test
  void parenthesisFormAndNestedJsonAreAccepted() {
    textual.extract("[getProductDetails({\"productId\": \"a1\", \"extra\": {\"x\": 1}})]");

    assertThat(textual.calls()).singleElement()
        .satisfies(call -> assertThat(call.arguments())
            .isEqualTo("{\"productId\": \"a1\", \"extra\": {\"x\": 1}}"));
  }

  @Test
  void unknownToolsInvalidJsonAndOrdinaryBracketsAreLeftAlone() {
    assertThat(textual.extract("[deleteCart: {\"all\": true}]"))
        .isEqualTo("[deleteCart: {\"all\": true}]");
    assertThat(textual.extract("[addToCart: {not json}]")).isEqualTo("[addToCart: {not json}]");
    assertThat(textual.extract("Options [see below]: the Aiden ($139)."))
        .isEqualTo("Options [see below]: the Aiden ($139).");
    assertThat(textual.calls()).isEmpty();
  }
}
