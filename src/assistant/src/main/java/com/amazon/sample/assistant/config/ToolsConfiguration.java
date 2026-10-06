package com.amazon.sample.assistant.config;

import com.amazon.sample.assistant.carts.CartsClient;
import com.amazon.sample.assistant.chat.llm.ChatRateLimiter;
import com.amazon.sample.assistant.chat.rewrite.CatalogTagsCache;
import com.amazon.sample.assistant.products.catalog.CatalogClient;
import com.amazon.sample.assistant.products.search.ProductSearchService;
import com.amazon.sample.assistant.tools.StoreTools;
import com.amazon.sample.assistant.tools.ToolArguments;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Tools del modelo principal y limitador de solicitudes hacia NVIDIA
 * (add-assistant-tools).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ToolsProperties.class, RateLimitProperties.class})
public class ToolsConfiguration {

  /**
   * {@link RestClient} de las tools hacia {@code catalog} o {@code carts}, con
   * los tiempos límite de {@code retail.assistant.tools.http} (D5). Parte de
   * una copia del builder para no cambiar los tiempos de los demás clientes.
   */
  public static RestClient toolsRestClient(RestClient.Builder builder, String baseUrl,
      ToolsProperties.Http http) {
    ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
        .withConnectTimeout(http.connectTimeout())
        .withReadTimeout(http.readTimeout());
    return builder.clone()
        .baseUrl(baseUrl)
        .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
        .build();
  }

  @Bean
  CartsClient cartsClient(RestClient.Builder builder,
      @Value("${retail.assistant.endpoints.carts}") String cartsEndpoint, ToolsProperties tools) {
    return new CartsClient(toolsRestClient(builder, cartsEndpoint, tools.http()));
  }

  @Bean
  ToolArguments toolArguments(CatalogTagsCache tags, ToolsProperties tools) {
    return new ToolArguments(tags, tools);
  }

  @Bean
  StoreTools storeTools(CatalogClient catalog, CartsClient carts, ProductSearchService search,
      ToolArguments arguments, ToolsProperties tools) {
    return new StoreTools(catalog, carts, search, arguments, tools);
  }

  /** Un único limitador para todas las sesiones: la cuota de NVIDIA es de la cuenta (D8). */
  @Bean
  ChatRateLimiter chatRateLimiter(RateLimitProperties properties) {
    return new ChatRateLimiter(properties.requestsPerMinute(), properties.defaultRetryAfter(),
        Clock.systemUTC());
  }

  /** Gauge {@code assistant.ratelimit.window}: reservas en la ventana de 60 s actual (D12). */
  @Bean
  MeterBinder chatRateLimiterMetrics(ChatRateLimiter limiter) {
    return registry -> Gauge.builder("assistant.ratelimit.window", limiter,
            ChatRateLimiter::windowCount)
        .description("Solicitudes al proveedor de chat reservadas en los últimos 60 s")
        .register(registry);
  }
}
