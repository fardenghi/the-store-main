package com.amazon.sample.assistant.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Sincronización del catálogo con Qdrant ({@code retail.assistant.indexing}).
 *
 * @param syncOnStartup si se sincroniza al arrancar (los tests lo apagan)
 * @param pageSize productos por página al leer {@code GET /catalog/products}
 * @param batchSize textos por request a Gemini (el máximo de la API es 100)
 * @param maxProviderRetries reintentos por lote ante 429 o 5xx de Gemini (D6)
 */
@Validated
@ConfigurationProperties("retail.assistant.indexing")
public record IndexingProperties(
    boolean syncOnStartup,
    @Min(1) int pageSize,
    @Min(1) @Max(100) int batchSize,
    @Min(0) int maxProviderRetries) {
}
