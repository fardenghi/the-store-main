package com.amazon.sample.assistant.products.index;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dispara la sincronización al quedar listo el servicio, en un hilo propio,
 * para no demorar el arranque ni la readiness (D5).
 */
public class ProductIndexStartup implements DisposableBean {

  private final ProductIndexer indexer;
  private final ThreadPoolTaskExecutor executor;

  public ProductIndexStartup(ProductIndexer indexer, ThreadPoolTaskExecutor executor) {
    this.indexer = indexer;
    this.executor = executor;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    indexer.syncAsync(executor);
  }

  /** Al apagar interrumpe la sincronización en curso (por ejemplo, esperando a catalog). */
  @Override
  public void destroy() {
    executor.shutdown();
  }
}
