package com.amazon.sample.assistant.chat.rewrite;

import com.amazon.sample.assistant.products.catalog.CatalogClient;
import com.amazon.sample.assistant.products.catalog.CatalogProduct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tags del catálogo para la reescritura (D4). Se leen de {@code catalog} la
 * primera vez que hacen falta y quedan cacheados: el catálogo es estático en
 * runtime. Si la lectura falla, devuelve una lista vacía (la reescritura sigue
 * sin tags) y vuelve a intentar en el turno siguiente.
 */
public class CatalogTagsCache {

  private static final Logger log = LoggerFactory.getLogger(CatalogTagsCache.class);

  private final CatalogClient catalog;
  private volatile List<CatalogProduct.Tag> tags;

  public CatalogTagsCache(CatalogClient catalog) {
    this.catalog = catalog;
  }

  /** Los tags del catálogo, o una lista vacía si todavía no se pudieron leer. */
  public List<CatalogProduct.Tag> tags() {
    List<CatalogProduct.Tag> cached = tags;
    if (cached != null) {
      return cached;
    }
    synchronized (this) {
      if (tags != null) {
        return tags;
      }
      try {
        List<CatalogProduct.Tag> fetched = catalog.fetchTags();
        if (!fetched.isEmpty()) {
          tags = fetched;
        }
        return fetched;
      } catch (RuntimeException e) {
        log.warn("No se pudieron leer los tags del catálogo; la reescritura sigue sin ellos y "
            + "se reintenta en el próximo turno: {}", e.getMessage());
        return List.of();
      }
    }
  }

  /** Los nombres de los tags (los identificadores que usan los filtros). */
  public List<String> tagNames() {
    return tags().stream().map(CatalogProduct.Tag::name).toList();
  }
}
