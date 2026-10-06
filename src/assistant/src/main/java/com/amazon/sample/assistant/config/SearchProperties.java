package com.amazon.sample.assistant.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Búsqueda semántica y similares ({@code retail.assistant.search}).
 *
 * @param defaultK resultados de la búsqueda si no se indica {@code k}
 * @param maxK máximo de {@code k} en la búsqueda
 * @param similarDefaultK similares si no se indica {@code k}
 * @param similarMaxK máximo de {@code k} en los similares
 * @param queryCacheSize entradas del caché LRU de embeddings de consultas (D9)
 */
@Validated
@ConfigurationProperties("retail.assistant.search")
public record SearchProperties(
    @Min(1) int defaultK,
    @Min(1) int maxK,
    @Min(1) int similarDefaultK,
    @Min(1) int similarMaxK,
    @Min(1) int queryCacheSize) {
}
