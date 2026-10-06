package com.amazon.sample.assistant.products.index;

import com.amazon.sample.assistant.products.Backoff;
import com.amazon.sample.assistant.products.Backoff.Sleeper;
import com.amazon.sample.assistant.products.catalog.CatalogClient;
import com.amazon.sample.assistant.products.catalog.CatalogProduct;
import com.amazon.sample.assistant.products.catalog.CatalogReadException;
import com.amazon.sample.assistant.products.embedding.EmbeddingProviderException;
import com.amazon.sample.assistant.products.embedding.EmbeddingProviderException.Reason;
import com.amazon.sample.assistant.products.embedding.ProductEmbedder;
import com.amazon.sample.assistant.products.vector.IndexedProduct;
import com.amazon.sample.assistant.products.vector.ProductPoint;
import com.amazon.sample.assistant.products.vector.ProductVectorRepository;
import com.amazon.sample.assistant.products.vector.VectorStoreException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;

/**
 * Sincroniza la colección {@code products} de Qdrant con el catálogo (D4):
 * asegura la colección, lee el catálogo completo, compara contra el payload
 * de los puntos y embebe solo los productos nuevos o con texto cambiado. Es
 * idempotente: el id del punto es el del producto.
 */
public class ProductIndexer {

  private static final Logger log = LoggerFactory.getLogger(ProductIndexer.class);

  /** Intentos contra Qdrant cuando no responde (D6). */
  static final int QDRANT_MAX_ATTEMPTS = 10;

  private final CatalogClient catalog;
  private final ProductEmbedder embedder;
  private final ProductVectorRepository repository;
  private final ProductIndexState state;
  private final int batchSize;
  private final int maxProviderRetries;
  private final Backoff backoff;
  private final Sleeper sleeper;
  private final Clock clock;
  private final AtomicBoolean running = new AtomicBoolean();

  public ProductIndexer(CatalogClient catalog, ProductEmbedder embedder,
      ProductVectorRepository repository, ProductIndexState state, int batchSize,
      int maxProviderRetries, Backoff backoff, Sleeper sleeper, Clock clock) {
    this.catalog = catalog;
    this.embedder = embedder;
    this.repository = repository;
    this.state = state;
    this.batchSize = batchSize;
    this.maxProviderRetries = maxProviderRetries;
    this.backoff = backoff;
    this.sleeper = sleeper;
    this.clock = clock;
  }

  /**
   * Lanza la sincronización en {@code executor} (D5). Si ya hay una en curso,
   * ignora el disparo.
   *
   * @return {@code false} si se ignoró
   */
  public boolean syncAsync(TaskExecutor executor) {
    if (!running.compareAndSet(false, true)) {
      log.info("Ya hay una sincronización del catálogo en curso; se ignora el disparo");
      return false;
    }
    try {
      executor.execute(() -> {
        try {
          doSync();
        } finally {
          running.set(false);
        }
      });
    } catch (RuntimeException e) {
      running.set(false);
      throw e;
    }
    return true;
  }

  /**
   * Sincroniza en el hilo actual. Si ya hay una en curso, no hace nada.
   *
   * @return el reporte, o {@code null} si falló o se ignoró
   */
  public SyncReport sync() {
    if (!running.compareAndSet(false, true)) {
      log.info("Ya hay una sincronización del catálogo en curso; se ignora el disparo");
      return null;
    }
    try {
      return doSync();
    } finally {
      running.set(false);
    }
  }

  private SyncReport doSync() {
    Instant start = clock.instant();
    state.syncing();
    log.info("Sincronización del catálogo con la colección '{}': inicio", repository.collection());
    try {
      SyncReport report = run(start);
      long points = withQdrantRetry(repository::count);
      state.ready(points, clock.instant(), report);
      log.info("Sincronización del catálogo terminada en {} ms: {} productos en el catálogo, "
          + "{} embebidos, {} con payload actualizado, {} borrados, {} sin cambios, "
          + "{} requests al proveedor de embeddings, {} puntos en la colección",
          report.duration().toMillis(), report.catalogProducts(), report.embedded(),
          report.payloadUpdated(), report.deleted(), report.unchanged(),
          report.providerRequests(), points);
      return report;
    } catch (EmbeddingProviderException e) {
      fail(e.reason().type(), e);
    } catch (VectorStoreException e) {
      fail("vector-store-unavailable", e);
    } catch (CatalogReadException e) {
      fail("catalog-unavailable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("interrupted", e);
    } catch (RuntimeException e) {
      fail("unexpected-error", e);
    }
    return null;
  }

  private void fail(String reason, Exception e) {
    state.failed(reason + ": " + e.getMessage());
    log.error("Sincronización del catálogo FALLIDA ({}): {}. No se reintenta sola: corregir la "
        + "causa y reiniciar el assistant", reason, e.getMessage());
  }

  private SyncReport run(Instant start) throws InterruptedException {
    // 1. Asegurar la colección.
    withQdrantRetry(repository::ensureCollection);

    // 2. Leer el catálogo completo (reintenta sin límite si catalog no responde).
    List<CatalogProduct> products = catalog.fetchAll();

    // 3. Payload actual de todos los puntos.
    Map<String, IndexedProduct> existing = withQdrantRetry(repository::scrollAllPayloads);

    // 4. Clasificar.
    List<IndexedProduct> toEmbed = new ArrayList<>();
    List<String> texts = new ArrayList<>();
    List<IndexedProduct> payloadOnly = new ArrayList<>();
    int unchanged = 0;
    for (CatalogProduct product : products) {
      String text = EmbeddingText.of(product);
      IndexedProduct indexed = new IndexedProduct(product.id(), product.name(),
          product.description(), product.price(), product.tagNames(),
          EmbeddingText.contentHash(embedder.model(), embedder.dimensions(), text));
      IndexedProduct current = existing.get(product.id());
      if (current == null || !current.contentHash().equals(indexed.contentHash())) {
        toEmbed.add(indexed);
        texts.add(text);
      } else if (!current.sameCatalogData(indexed)) {
        payloadOnly.add(indexed);
      } else {
        unchanged++;
      }
    }

    // 5. Embeber y hacer upsert en lotes.
    int requests = 0;
    for (int from = 0; from < toEmbed.size(); from += batchSize) {
      int to = Math.min(from + batchSize, toEmbed.size());
      List<IndexedProduct> batch = toEmbed.subList(from, to);
      BatchResult result = embedWithRetry(texts.subList(from, to));
      requests += result.requests();
      List<ProductPoint> points = new ArrayList<>(batch.size());
      for (int i = 0; i < batch.size(); i++) {
        points.add(new ProductPoint(batch.get(i), result.vectors().get(i)));
      }
      withQdrantRetry(() -> {
        repository.upsert(points);
        return null;
      });
    }
    for (IndexedProduct product : payloadOnly) {
      withQdrantRetry(() -> {
        repository.setPayload(product);
        return null;
      });
    }

    // 6. Borrar los puntos que ya no están en el catálogo. Solo se llega acá
    // si la lectura del paso 2 fue completa.
    Set<String> catalogIds = new HashSet<>();
    products.forEach(product -> catalogIds.add(product.id()));
    List<String> toDelete = existing.keySet().stream()
        .filter(id -> !catalogIds.contains(id))
        .toList();
    withQdrantRetry(() -> {
      repository.deleteByIds(toDelete);
      return null;
    });

    return new SyncReport(products.size(), toEmbed.size(), payloadOnly.size(), toDelete.size(),
        unchanged, requests, Duration.between(start, clock.instant()));
  }

  private record BatchResult(List<float[]> vectors, int requests) {
  }

  /**
   * Embebe un lote (una request) con hasta {@code max-provider-retries}
   * reintentos ante {@code QUOTA} y {@code UNAVAILABLE}, esperando el
   * {@code retryDelay} del proveedor o el backoff. {@code UNAUTHORIZED} no se
   * reintenta (D6).
   */
  private BatchResult embedWithRetry(List<String> texts) throws InterruptedException {
    for (int attempt = 1; ; attempt++) {
      try {
        return new BatchResult(embedder.embedDocuments(texts), attempt);
      } catch (EmbeddingProviderException e) {
        if (e.reason() == Reason.UNAUTHORIZED || attempt > maxProviderRetries) {
          throw e;
        }
        Duration delay = e.retryAfter().orElse(backoff.delay(attempt));
        log.warn("Gemini rechazó un lote de {} textos ({}, intento {}). Reintento en {} ms",
            texts.size(), e.reason(), attempt, delay.toMillis());
        sleeper.sleep(delay);
      }
    }
  }

  private <T> T withQdrantRetry(Supplier<T> operation) throws InterruptedException {
    for (int attempt = 1; ; attempt++) {
      try {
        return operation.get();
      } catch (VectorStoreException e) {
        if (!e.isUnavailable() || attempt >= QDRANT_MAX_ATTEMPTS) {
          throw e;
        }
        Duration delay = backoff.delay(attempt);
        log.warn("Qdrant no respondió (intento {} de {}): {}. Reintento en {} s", attempt,
            QDRANT_MAX_ATTEMPTS, e.getMessage(), delay.toSeconds());
        sleeper.sleep(delay);
      }
    }
  }
}
