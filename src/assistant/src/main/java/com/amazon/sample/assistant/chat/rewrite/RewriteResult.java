package com.amazon.sample.assistant.chat.rewrite;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * Salida estructurada del modelo de reescritura (D4), tal como la devuelve
 * el modelo. {@link QueryRewriter} la valida antes de usarla.
 */
public record RewriteResult(
    @JsonPropertyDescription("search, compare or other")
    String intent,
    @JsonPropertyDescription("Short self-contained English search query; empty when intent is other")
    String query,
    @JsonPropertyDescription("Minimum price in USD as an integer, or null")
    Integer minPrice,
    @JsonPropertyDescription("Maximum price in USD as an integer, or null")
    Integer maxPrice,
    @JsonPropertyDescription("Catalog tag names whose products must be excluded; empty if none")
    List<String> excludeTags) {
}
