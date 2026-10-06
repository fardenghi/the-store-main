package com.amazon.sample.assistant.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

/**
 * Limitador de solicitudes hacia el proveedor de chat y reintento ante 429
 * ({@code retail.assistant.rate-limit}, D8, D9 y D11 de
 * {@code add-assistant-tools}).
 *
 * @param requestsPerMinute solicitudes permitidas en cualquier ventana de 60 s,
 *     sumando todas las sesiones (por debajo de los 40 RPM de NVIDIA)
 * @param maxWait espera máxima del modelo principal por un lugar en el limitador
 * @param max429Retries reintentos de una vuelta del modelo principal ante un 429
 * @param defaultRetryAfter pausa ante un 429 sin {@code Retry-After} válido
 *
 * <p>{@code max429Retries} lleva {@link Name} porque su nombre canónico sería
 * {@code max429-retries}, y la variable de entorno
 * {@code RETAIL_ASSISTANT_RATE_LIMIT_MAX_429_RETRIES} no se le asociaría.
 */
@Validated
@ConfigurationProperties("retail.assistant.rate-limit")
public record RateLimitProperties(
    @Min(1) int requestsPerMinute,
    @NotNull Duration maxWait,
    @Name("max-429-retries") @Min(0) int max429Retries,
    @NotNull Duration defaultRetryAfter) {
}
