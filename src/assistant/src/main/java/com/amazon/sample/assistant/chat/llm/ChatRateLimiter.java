package com.amazon.sample.assistant.chat.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Limitador de solicitudes hacia el proveedor de chat, compartido por todas las
 * sesiones (D8 de {@code add-assistant-tools}): ventana deslizante de 60 s con
 * a lo sumo {@code requestsPerMinute} reservas, y una pausa global que fija un
 * 429.
 *
 * <p>Las reservas se otorgan en orden de llegada: cada una cae en el primer
 * instante, no anterior a la última reserva ni al fin de la pausa, en que la
 * ventana de 60 s que termina ahí tiene lugar. Así ninguna ventana de 60 s
 * supera el límite, contando también las reservas que todavía están esperando
 * su instante. Una reserva que no se usa (por ejemplo, porque el cliente cortó
 * mientras esperaba) no se devuelve: el límite queda del lado seguro.
 */
public class ChatRateLimiter {

  static final Duration WINDOW = Duration.ofSeconds(60);

  /**
   * Resultado de {@link #reserve}.
   *
   * @param granted si se reservó un lugar
   * @param delay espera hasta el lugar reservado o, sin reserva, la espera
   *     estimada hasta que habría lugar (para {@code retryAfterSeconds})
   */
  public record Reservation(boolean granted, Duration delay) {
  }

  private final int requestsPerMinute;
  private final Duration defaultRetryAfter;
  private final Clock clock;
  private final ReentrantLock lock = new ReentrantLock();
  /** Instantes reservados, en orden; se descartan los que salieron de la ventana. */
  private final List<Instant> reserved = new ArrayList<>();
  private Instant pausedUntil = Instant.EPOCH;

  /**
   * @param requestsPerMinute reservas permitidas en cualquier ventana de 60 s
   * @param defaultRetryAfter pausa ante un 429 sin {@code Retry-After} (D9)
   * @param clock reloj; en los tests, uno falso
   */
  public ChatRateLimiter(int requestsPerMinute, Duration defaultRetryAfter, Clock clock) {
    if (requestsPerMinute < 1) {
      throw new IllegalArgumentException("requestsPerMinute tiene que ser al menos 1");
    }
    this.requestsPerMinute = requestsPerMinute;
    this.defaultRetryAfter = defaultRetryAfter;
    this.clock = clock;
  }

  /**
   * Reserva el primer lugar libre si la espera no supera {@code maxWait}. Con
   * {@code maxWait} cero, solo reserva si hay lugar inmediato.
   */
  public Reservation reserve(Duration maxWait) {
    lock.lock();
    try {
      Instant now = clock.instant();
      purge(now);
      Instant slot = now;
      if (pausedUntil.isAfter(slot)) {
        slot = pausedUntil;
      }
      if (!reserved.isEmpty() && reserved.get(reserved.size() - 1).isAfter(slot)) {
        slot = reserved.get(reserved.size() - 1);
      }
      if (reserved.size() >= requestsPerMinute) {
        Instant freed = reserved.get(reserved.size() - requestsPerMinute).plus(WINDOW);
        if (freed.isAfter(slot)) {
          slot = freed;
        }
      }
      Duration wait = Duration.between(now, slot);
      if (wait.compareTo(maxWait) > 0) {
        return new Reservation(false, wait);
      }
      reserved.add(slot);
      return new Reservation(true, wait);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Suspende todas las reservas hasta {@code until} (un 429 con
   * {@code Retry-After}). Nunca atrasa una pausa que ya termina más tarde.
   */
  public void pauseUntil(Instant until) {
    lock.lock();
    try {
      if (until.isAfter(pausedUntil)) {
        pausedUntil = until;
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Pausa todas las reservas por el {@code Retry-After} de un 429, o por
   * {@code defaultRetryAfter} si no vino (D9).
   *
   * @return la pausa aplicada
   */
  public Duration pause(ChatProviderException quota) {
    Duration pause = quota.retryAfter().orElse(defaultRetryAfter);
    pauseUntil(clock.instant().plus(pause));
    return pause;
  }

  /** Reservas cuyo instante cayó en los últimos 60 s (el gauge {@code assistant.ratelimit.window}). */
  public int windowCount() {
    lock.lock();
    try {
      Instant now = clock.instant();
      purge(now);
      return (int) reserved.stream().filter(instant -> !instant.isAfter(now)).count();
    } finally {
      lock.unlock();
    }
  }

  public int requestsPerMinute() {
    return requestsPerMinute;
  }

  public Instant now() {
    return clock.instant();
  }

  private void purge(Instant now) {
    Instant start = now.minus(WINDOW);
    while (!reserved.isEmpty() && !reserved.get(0).isAfter(start)) {
      reserved.remove(0);
    }
  }
}
