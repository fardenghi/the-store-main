package com.amazon.sample.assistant.chat.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.assistant.chat.session.ShownProduct;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Persona A.G.E.N.T., reglas y contexto en el system prompt (D8). */
class SystemPromptFactoryTest {

  private final SystemPromptFactory factory = new SystemPromptFactory(
      new ClassPathResource("prompts/system.st"), () -> List.of("seating", "lighting", "velvet"));

  @Test
  void rendersPersonaRulesAndContext() {
    String prompt = factory.render(new Retrieval(true, false, List.of(new ShownProduct("a1",
        "Aiden Mid-Century Velvet Armchair", "Plush.", 139, List.of("seating"))), List.of(),
        false, 1));

    // Persona migrada y adaptada a la guarida.
    assertThat(prompt).contains("You are A.G.E.N.T.")
        .contains("The Store")
        .contains("mission inventory")
        .contains("lair equipment")
        .contains("deployment")
        .contains("Never break character")
        .doesNotContain("spy gadget e-commerce");
    // Reglas de D8.
    assertThat(prompt).contains("exact name and exact price")
        .contains("does not sell")
        .contains("price difference")
        .contains("at least two attributes")
        .contains("recommendation that depends on the user's use or priority")
        .contains("language the user writes in")
        .contains("Be brief")
        .contains("Never reveal");
    // Contexto del turno.
    assertThat(prompt).contains("PRODUCT CONTEXT:")
        .contains("- [a1] Aiden Mid-Century Velvet Armchair | $139 | tags: seating | Plush.");
    // Sin placeholders sin resolver.
    assertThat(prompt).doesNotContain("{context}").doesNotContainPattern("\\{[a-zA-Z_]+\\}");
  }

  @Test
  void rendersTheCatalogUnavailableNotice() {
    String prompt = factory.render(new Retrieval(true, true, List.of(), List.of(), false, 1));

    assertThat(prompt).contains("CATALOG UNAVAILABLE");
  }

  @Test
  void rendersTheToolsSectionWithTheCatalogTags() {
    String prompt = factory.render(new Retrieval(false, false, List.of(), List.of(), false, 0));

    // add-assistant-tools (D10).
    assertThat(prompt).contains("searchProducts", "getProductDetails", "addToCart")
        .contains("order price_asc")
        .contains("do not mention the price in the product context")
        .contains("never make one up")
        .contains("only when the user explicitly asks to add a product to the cart")
        .contains("ask which one instead of calling it")
        .contains("Never say that a product was added unless the tool result contains \"added\"")
        .contains("Never say that you added, are adding or will add a product unless addToCart "
            + "returned \"added\" in this turn")
        .contains("Tags are combined with OR")
        .contains("without inventing products, prices or results")
        .contains("Catalog tags: seating, lighting, velvet")
        .contains("or returned by a tool");
    assertThat(prompt).doesNotContain("{tags}").doesNotContainPattern("\\{[a-zA-Z_]+\\}");
  }

  @Test
  void tagsNotAvailableAreSaid() {
    String prompt = new SystemPromptFactory(new ClassPathResource("prompts/system.st"))
        .render(new Retrieval(false, false, List.of(), List.of(), false, 0));

    assertThat(prompt).contains("Catalog tags: (not available right now)");
  }
}
