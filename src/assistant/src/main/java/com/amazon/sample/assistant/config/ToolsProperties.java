package com.amazon.sample.assistant.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tools del modelo principal ({@code retail.assistant.tools}, D11 de
 * {@code add-assistant-tools}).
 *
 * @param maxModelCalls solicitudes al modelo principal por turno, incluidas las
 *     que siguen a las tools; la última va con {@code tool_choice: "none"} (D1)
 * @param maxToolCalls tools ejecutadas por turno; las que exceden reciben
 *     {@code tool-budget-exhausted} (D1)
 * @param maxQuantity cantidad máxima de {@code addToCart} (D3)
 * @param searchDefaultLimit resultados de {@code searchProducts} sin {@code limit}
 * @param searchMaxLimit máximo de {@code limit} en {@code searchProducts} (D3)
 * @param descriptionMaxChars largo máximo de las descripciones en los resultados (D2)
 * @param http tiempos límite de las llamadas a {@code catalog} y {@code carts} (D5)
 */
@Validated
@ConfigurationProperties("retail.assistant.tools")
public record ToolsProperties(
    @Min(1) int maxModelCalls,
    @Min(1) int maxToolCalls,
    @Min(1) int maxQuantity,
    @Min(1) int searchDefaultLimit,
    @Min(1) @Max(20) int searchMaxLimit,
    @Min(1) int descriptionMaxChars,
    @NotNull @Valid Http http) {

  /**
   * @param connectTimeout tiempo límite de conexión
   * @param readTimeout tiempo límite de lectura
   */
  public record Http(@NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
  }
}
