package com.amazon.sample.assistant.products.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.assistant.products.catalog.CatalogProduct;
import com.amazon.sample.assistant.products.catalog.CatalogProduct.Tag;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmbeddingTextTest {

  private static final String MODEL = "gemini-embedding-001";

  private final CatalogProduct chair = new CatalogProduct("3600929b-2826-5a98-908f-82a1d50bcf2b",
      "Velvet Accent Chair", "A plush velvet chair with brass legs for a reading corner.", 349,
      List.of(new Tag("seating", "Seating"), new Tag("velvet", "Velvet")));

  @Test
  void textHasNameDescriptionAndDisplayNames() {
    String text = EmbeddingText.of(chair);

    assertThat(text).isEqualTo("Velvet Accent Chair\n"
        + "Tags: Seating, Velvet\n"
        + "A plush velvet chair with brass legs for a reading corner.");
  }

  @Test
  void hashChangesWithDescriptionModelOrTemplateVersion() {
    String base = EmbeddingText.contentHash(MODEL, 768, EmbeddingText.of(chair));
    CatalogProduct otherDescription = new CatalogProduct(chair.id(), chair.name(),
        "Another description", chair.price(), chair.tags());

    assertThat(base).hasSize(64);
    assertThat(EmbeddingText.contentHash(MODEL, 768, EmbeddingText.of(otherDescription)))
        .isNotEqualTo(base);
    assertThat(EmbeddingText.contentHash("text-embedding-004", 768, EmbeddingText.of(chair)))
        .isNotEqualTo(base);
    assertThat(EmbeddingText.contentHash(MODEL, 512, EmbeddingText.of(chair)))
        .isNotEqualTo(base);
    assertThat(EmbeddingText.contentHash(MODEL, 768, "1", EmbeddingText.of(chair)))
        .isNotEqualTo(base);
  }

  @Test
  void hashDoesNotChangeWithPrice() {
    CatalogProduct cheaper = new CatalogProduct(chair.id(), chair.name(), chair.description(),
        199, chair.tags());

    assertThat(EmbeddingText.contentHash(MODEL, 768, EmbeddingText.of(cheaper)))
        .isEqualTo(EmbeddingText.contentHash(MODEL, 768, EmbeddingText.of(chair)));
  }
}
