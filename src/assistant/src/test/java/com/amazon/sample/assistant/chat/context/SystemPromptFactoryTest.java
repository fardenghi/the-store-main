package com.amazon.sample.assistant.chat.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.assistant.chat.session.ShownProduct;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Persona A.G.E.N.T., reglas y contexto en el system prompt (D8). */
class SystemPromptFactoryTest {

  private final SystemPromptFactory factory =
      new SystemPromptFactory(new ClassPathResource("prompts/system.st"));

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
}
