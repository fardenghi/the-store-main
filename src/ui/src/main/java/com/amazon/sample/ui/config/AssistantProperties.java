package com.amazon.sample.ui.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Tiempos y parámetros de las llamadas de la ui al assistant. El endpoint está
 * en {@link EndpointProperties#getAssistant()}, junto al de los otros servicios.
 */
@Configuration
@ConfigurationProperties(AssistantProperties.PREFIX)
@Data
public class AssistantProperties {

  public static final String PREFIX = "retail.ui.assistant";

  /** Tiempo máximo para abrir la conexión con el assistant. */
  private Duration connectTimeout = Duration.ofSeconds(2);

  /** Tiempo total de un turno de chat visto desde la ui. */
  private Duration chatTimeout = Duration.ofSeconds(160);

  /** Cada cuánto se manda un comentario SSE al navegador durante el chat. */
  private Duration keepaliveInterval = Duration.ofSeconds(10);

  /** Cantidad de productos similares que muestra la ficha. */
  private int similarK = 4;

  /** Tiempo máximo de espera de los similares de la ficha. */
  private Duration similarTimeout = Duration.ofSeconds(2);
}
