package com.amazon.sample.ui.services.assistant;

import com.amazon.sample.ui.services.catalog.model.Product;
import com.amazon.sample.ui.services.catalog.model.ProductTag;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Llamadas REST de la ui al assistant. Hoy solo los productos similares de la
 * ficha (D9): cualquier falla devuelve una lista vacía para que la ficha se
 * muestre igual, sin la sección.
 */
@Slf4j
public class AssistantClient {

  private static final ParameterizedTypeReference<
    List<SimilarProduct>
  > SIMILAR_TYPE = new ParameterizedTypeReference<>() {};

  private final WebClient webClient;

  private final String endpoint;

  private final Duration similarTimeout;

  public AssistantClient(
    WebClient webClient,
    String endpoint,
    Duration similarTimeout
  ) {
    this.webClient = webClient;
    this.endpoint = endpoint;
    this.similarTimeout = similarTimeout;
  }

  public Mono<List<Product>> similar(String productId, int k) {
    if (!StringUtils.hasText(this.endpoint)) {
      return Mono.just(List.of());
    }

    var base = this.endpoint.endsWith("/")
      ? this.endpoint.substring(0, this.endpoint.length() - 1)
      : this.endpoint;

    return this.webClient.get()
      .uri(base + "/assistant/products/{id}/similar?k={k}", productId, k)
      .accept(MediaType.APPLICATION_JSON)
      .retrieve()
      .bodyToMono(SIMILAR_TYPE)
      .timeout(this.similarTimeout)
      .map(similar ->
        similar
          .stream()
          .filter(p -> p.id() != null && !p.id().equals(productId))
          .map(AssistantClient::toProduct)
          .toList()
      )
      .defaultIfEmpty(List.of())
      .onErrorResume(e -> {
        logFailure(productId, e);
        return Mono.just(List.of());
      });
  }

  private static Product toProduct(SimilarProduct similar) {
    List<ProductTag> tags = similar.tags() == null
      ? List.of()
      : similar.tags().stream().map(t -> new ProductTag(t, t)).toList();

    int price = similar.price() == null ? 0 : similar.price().intValue();

    return new Product(
      similar.id(),
      similar.name(),
      similar.description(),
      price,
      tags
    );
  }

  private static void logFailure(String productId, Throwable e) {
    if (e instanceof WebClientResponseException response) {
      int status = response.getStatusCode().value();
      if (
        status == HttpStatus.NOT_FOUND.value() ||
        status == HttpStatus.SERVICE_UNAVAILABLE.value()
      ) {
        log.debug("No similar products for {}: HTTP {}", productId, status);
        return;
      }
    }
    log.warn("Could not fetch similar products for {}: {}", productId, e.toString());
  }

  /** Elemento de {@code GET /assistant/products/{id}/similar}. */
  public record SimilarProduct(
    String id,
    String name,
    String description,
    BigDecimal price,
    List<String> tags,
    Double score
  ) {}
}
