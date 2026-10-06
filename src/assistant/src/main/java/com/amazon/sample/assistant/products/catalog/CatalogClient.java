package com.amazon.sample.assistant.products.catalog;

import com.amazon.sample.assistant.products.Backoff;
import com.amazon.sample.assistant.products.Backoff.Sleeper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
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
 */
public class CatalogClient {

  private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);

  private static final ParameterizedTypeReference<List<CatalogProduct>> PRODUCTS =
      new ParameterizedTypeReference<>() { };

  private final RestClient restClient;
  private final int pageSize;
  private final Backoff backoff;
  private final Sleeper sleeper;

  public CatalogClient(RestClient restClient, int pageSize, Backoff backoff, Sleeper sleeper) {
    this.restClient = restClient;
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
