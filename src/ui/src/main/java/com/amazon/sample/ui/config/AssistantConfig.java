package com.amazon.sample.ui.config;

import com.amazon.sample.ui.services.assistant.AssistantClient;
import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class AssistantConfig {

  /**
   * Cliente HTTP hacia el assistant, compartido por el chat y los similares.
   * Solo tiene timeout de conexión: un turno con razonamiento puede pasar más
   * de 60 s sin bytes, así que cada llamada aplica su propio límite total (D3).
   */
  @Bean
  public WebClient assistantWebClient(
    WebClient.Builder builder,
    AssistantProperties properties
  ) {
    HttpClient httpClient = HttpClient.create()
      .option(
        ChannelOption.CONNECT_TIMEOUT_MILLIS,
        (int) properties.getConnectTimeout().toMillis()
      );

    return builder
      .clone()
      .clientConnector(new ReactorClientHttpConnector(httpClient))
      .build();
  }

  @Bean
  public AssistantClient assistantClient(
    WebClient assistantWebClient,
    EndpointProperties endpoints,
    AssistantProperties properties
  ) {
    return new AssistantClient(
      assistantWebClient,
      endpoints.getAssistant(),
      properties.getSimilarTimeout()
    );
  }
}
