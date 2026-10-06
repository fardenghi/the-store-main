package com.amazon.sample.assistant.products.catalog;

import com.amazon.sample.assistant.products.Backoff;
import com.amazon.sample.assistant.products.Backoff.Sleeper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Lee el catálogo completo del servicio {@code catalog} paginando
 * {@code GET /catalog/products} (D4).
 *
 * <p>Ante errores de conexión, timeouts y 5xx (incluido el chaos middleware)
 * reintenta sin límite con backoff exponencial, porque al arrancar el cluster
 * {@code catalog} puede tardar (D6). Cada reintento vuelve a leer desde la
 * primera página: un error en cualquier página invalida la lectura completa y
 * nunca se devuelve un resultado parcial.
 *
 * <p>Las lecturas de las tools ({@link #getProduct} y {@link #listProducts}, D5
 * de {@code add-assistant-tools}) usan otro {@link RestClient}, con tiempos
 * límite cortos, y reintentan <b>una</b> vez ante 5xx, timeout o error de red:
 * un turno de chat no puede esperar indefinidamente.
 */
public class CatalogClient {

  private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);

  private static final ParameterizedTypeReference<List<CatalogProduct>> PRODUCTS =
      new ParameterizedTypeReference<>() { };

  private static final ParameterizedTypeReference<List<CatalogProduct.Tag>> TAGS =
      new ParameterizedTypeReference<>() { };

  private final RestClient restClient;
  private final RestClient toolsRestClient;
  private final int pageSize;
  private final Backoff backoff;
  private final Sleeper sleeper;

  public CatalogClient(RestClient restClient, int pageSize, Backoff backoff, Sleeper sleeper) {
    this(restClient, restClient, pageSize, backoff, sleeper);
  }

  /**
   * @param restClient cliente de la indexación y de los tags
   * @param toolsRestClient cliente de las lecturas de las tools, con sus tiempos límite
   */
  public CatalogClient(RestClient restClient, RestClient toolsRestClient, int pageSize,
      Backoff backoff, Sleeper sleeper) {
    this.restClient = restClient;
    this.toolsRestClient = toolsRestClient;
    this.pageSize = pageSize;
    this.backoff = backoff;
    this.sleeper = sleeper;
  }

  /**
   * Devuelve todos los productos del catálogo, sin repetidos.
   *
   * @throws CatalogReadException si {@code catalog} responde un error que no se reintenta
   * @throws InterruptedException si se interrumpe mientras espera para reintentar
   */
  public List<CatalogProduct> fetchAll() throws InterruptedException {
    for (int attempt = 1; ; attempt++) {
      try {
        return readAllPages();
      } catch (ResourceAccessException | HttpServerErrorException e) {
        Duration delay = backoff.delay(attempt);
        log.warn("No se pudo leer el catálogo (intento {}): {}. Reintento en {} s",
            attempt, e.getMessage(), delay.toSeconds());
        sleeper.sleep(delay);
      } catch (RestClientException e) {
        throw new CatalogReadException("Error al leer el catálogo: " + e.getMessage(), e);
      }
    }
  }

  /**
   * Devuelve los tags del catálogo ({@code GET /catalog/tags}) en una sola
   * llamada, sin reintentos: la usa la reescritura de consulta, que ante un
   * error sigue sin la lista y la vuelve a pedir en el turno siguiente (D4 de
   * {@code add-assistant-chat}).
   *
   * @throws RestClientException si {@code catalog} no responde o responde un error
   */
  public List<CatalogProduct.Tag> fetchTags() {
    List<CatalogProduct.Tag> tags = restClient.get()
        .uri("/catalog/tags")
        .retrieve()
        .body(TAGS);
    return tags == null ? List.of() : List.copyOf(tags);
  }

  /**
   * Un producto ({@code GET /catalog/products/{id}}), siempre leído del
   * catálogo y sin caché (D6 de {@code add-assistant-tools}).
   *
   * @return el producto, o vacío si {@code catalog} responde 404
   * @throws CatalogUnavailableException si falla también el reintento
   */
  public Optional<CatalogProduct> getProduct(String id) {
    return withOneRetry("GET /catalog/products/" + id, () -> {
      try {
        return Optional.ofNullable(toolsRestClient.get()
            .uri("/catalog/products/{id}", id)
            .retrieve()
            .body(CatalogProduct.class));
      } catch (HttpClientErrorException e) {
        if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
          return Optional.empty();
        }
        throw e;
      }
    });
  }

  /**
   * Una página de {@code GET /catalog/products} con los filtros del catálogo:
   * tags con semántica OR y orden por precio.
   *
   * @param tags nombres de tags; vacío para no filtrar
   * @param order {@code price_asc}, {@code price_desc} o {@code null} (orden por nombre)
   * @param page página, desde 1
   * @param size productos por página
   * @throws CatalogUnavailableException si falla también el reintento
   */
  public List<CatalogProduct> listProducts(List<String> tags, String order, int page, int size) {
    StringBuilder uri = new StringBuilder("/catalog/products?page={page}&size={size}");
    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("page", page);
    variables.put("size", size);
    if (tags != null && !tags.isEmpty()) {
      uri.append("&tags={tags}");
      variables.put("tags", String.join(",", tags));
    }
    if (order != null) {
      uri.append("&order={order}");
      variables.put("order", order);
    }
    return withOneRetry("GET /catalog/products", () -> {
      List<CatalogProduct> items = toolsRestClient.get()
          .uri(uri.toString(), variables)
          .retrieve()
          .body(PRODUCTS);
      return items == null ? List.of() : items;
    });
  }

  private <T> T withOneRetry(String operation, Supplier<T> call) {
    for (int attempt = 1; ; attempt++) {
      try {
        return call.get();
      } catch (ResourceAccessException | HttpServerErrorException | CancellationException e) {
        // El cliente HTTP del JDK a veces informa el read-timeout como CancellationException.
        if (attempt >= 2) {
          throw new CatalogUnavailableException(
              operation + " falló después del reintento: " + e.getMessage(), e);
        }
        log.warn("{} falló ({}); se reintenta una vez", operation, e.getMessage());
      } catch (RestClientException e) {
        throw new CatalogUnavailableException(operation + " falló: " + e.getMessage(), e);
      }
    }
  }

  private List<CatalogProduct> readAllPages() {
    Map<String, CatalogProduct> products = new LinkedHashMap<>();
    for (int page = 1; ; page++) {
      List<CatalogProduct> items = fetchPage(page);
      int before = products.size();
      for (CatalogProduct product : items) {
        products.putIfAbsent(product.id(), product);
      }
      // Una página incompleta es la última. Una página llena que no suma
      // productos nuevos también corta, para no paginar sin fin.
      if (items.size() < pageSize || products.size() == before) {
        return new ArrayList<>(products.values());
      }
    }
  }

  private List<CatalogProduct> fetchPage(int page) {
    List<CatalogProduct> items = restClient.get()
        .uri("/catalog/products?page={page}&size={size}", page, pageSize)
        .retrieve()
        .body(PRODUCTS);
    return items == null ? List.of() : items;
  }
}
