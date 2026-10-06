package com.amazon.sample.assistant.products;

import java.time.Duration;

/**
 * Backoff exponencial con tope (D6) y la forma de esperar, inyectable para que
 * los tests no esperen de verdad.
 */
public final class Backoff {

  /** Espera de un intervalo. En los tests se reemplaza por una que no espera. */
  @FunctionalInterface
  public interface Sleeper {
    Sleeper THREAD = duration -> Thread.sleep(duration.toMillis());

    void sleep(Duration duration) throws InterruptedException;
  }

  /** El de D6 para {@code catalog} y Qdrant: de 2 s a 30 s. */
  public static final Backoff DEFAULT = new Backoff(Duration.ofSeconds(2), Duration.ofSeconds(30));

  private final Duration initial;
  private final Duration max;

  public Backoff(Duration initial, Duration max) {
    this.initial = initial;
    this.max = max;
  }

  /** Espera antes del reintento número {@code attempt} (desde 1). */
  public Duration delay(int attempt) {
    int doublings = Math.min(Math.max(attempt - 1, 0), 30);
    long millis = initial.toMillis() << doublings;
    return millis <= 0 || millis > max.toMillis() ? max : Duration.ofMillis(millis);
  }
}
