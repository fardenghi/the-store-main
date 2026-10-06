package com.amazon.sample.assistant.chat.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.assistant.chat.llm.ChatRateLimiter.Reservation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Ventana deslizante de 60 s y pausa global del limitador hacia NVIDIA (D8). */
class ChatRateLimiterTest {

  private static final Instant START = Instant.parse("2026-10-06T12:00:00Z");
  private static final Duration MAX_WAIT = Duration.ofSeconds(30);

  private final MutableClock clock = new MutableClock(START);
  private final ChatRateLimiter limiter = new ChatRateLimiter(36, Duration.ofSeconds(5), clock);

  @Test
  void thirtySixImmediateReservationsAndThe37thWaitsForTheFirstToLeaveTheWindow() {
    for (int i = 0; i < 36; i++) {
      assertThat(limiter.reserve(MAX_WAIT)).isEqualTo(new Reservation(true, Duration.ZERO));
      clock.advance(Duration.ofSeconds(1));
    }
    // A los 36 s: la primera reserva (t=0) sale de la ventana a los 60 s.
    Reservation reservation = limiter.reserve(MAX_WAIT);

    assertThat(reservation).isEqualTo(new Reservation(true, Duration.ofSeconds(24)));
    assertThat(limiter.windowCount()).isEqualTo(36);
  }

  @Test
  void waitLongerThanTheMaximumIsNotReservedAndReportsTheEstimate() {
    for (int i = 0; i < 36; i++) {
      limiter.reserve(MAX_WAIT);
    }
    clock.advance(Duration.ofSeconds(10));

    assertThat(limiter.reserve(Duration.ofSeconds(30)))
        .isEqualTo(new Reservation(false, Duration.ofSeconds(50)));
    // No reservó: con 50 s de espera aceptables, el lugar sigue siendo a los 60 s.
    assertThat(limiter.reserve(Duration.ofSeconds(50)))
        .isEqualTo(new Reservation(true, Duration.ofSeconds(50)));
  }

  @Test
  void reserveZeroWithoutRoomDoesNotReserve() {
    for (int i = 0; i < 36; i++) {
      limiter.reserve(MAX_WAIT);
    }

    Reservation reservation = limiter.reserve(Duration.ZERO);

    assertThat(reservation.granted()).isFalse();
    assertThat(reservation.delay()).isEqualTo(Duration.ofSeconds(60));
    clock.advance(Duration.ofSeconds(60));
    // A los 60 s salen las 36 y hay lugar para 36 más: la fallida no ocupó nada.
    for (int i = 0; i < 36; i++) {
      assertThat(limiter.reserve(Duration.ZERO).granted()).isTrue();
    }
  }

  @Test
  void pauseMakesEveryReservationWait() {
    limiter.pauseUntil(START.plusSeconds(5));

    assertThat(limiter.reserve(MAX_WAIT)).isEqualTo(new Reservation(true, Duration.ofSeconds(5)));
    assertThat(limiter.reserve(MAX_WAIT)).isEqualTo(new Reservation(true, Duration.ofSeconds(5)));
    assertThat(limiter.reserve(Duration.ZERO).granted()).isFalse();
    clock.advance(Duration.ofSeconds(5));
    assertThat(limiter.reserve(Duration.ZERO)).isEqualTo(new Reservation(true, Duration.ZERO));
  }

  @Test
  void quotaErrorPausesForItsRetryAfterOrTheDefault() {
    ChatProviderException withHeader = new ChatProviderException(
        ChatProviderException.Reason.QUOTA, Duration.ofSeconds(3), "429", null);
    ChatProviderException withoutHeader = new ChatProviderException(
        ChatProviderException.Reason.QUOTA, null, "429", null);

    assertThat(limiter.pause(withHeader)).isEqualTo(Duration.ofSeconds(3));
    assertThat(limiter.reserve(MAX_WAIT).delay()).isEqualTo(Duration.ofSeconds(3));
    assertThat(limiter.pause(withoutHeader)).isEqualTo(Duration.ofSeconds(5));
    assertThat(limiter.reserve(MAX_WAIT).delay()).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void pauseIsNeverShortened() {
    limiter.pauseUntil(START.plusSeconds(10));
    limiter.pauseUntil(START.plusSeconds(3));

    assertThat(limiter.reserve(MAX_WAIT).delay()).isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  void concurrentReservationsNeverExceedTheLimitInAnyWindow() throws Exception {
    List<Instant> granted = Collections.synchronizedList(new ArrayList<>());
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      // 8 rondas de 8 hilos con 6 reservas cada uno; el reloj avanza 7 s entre rondas.
      for (int round = 0; round < 8; round++) {
        Instant now = clock.instant();
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
        for (int thread = 0; thread < 8; thread++) {
          tasks.add(pool.submit(() -> {
            start.await();
            for (int i = 0; i < 6; i++) {
              Reservation reservation = limiter.reserve(Duration.ofMinutes(30));
              assertThat(reservation.granted()).isTrue();
              granted.add(now.plus(reservation.delay()));
            }
            return null;
          }));
        }
        start.countDown();
        for (var task : tasks) {
          task.get(10, TimeUnit.SECONDS);
        }
        clock.advance(Duration.ofSeconds(7));
      }
    } finally {
      pool.shutdownNow();
    }

    List<Instant> sorted = new ArrayList<>(granted);
    Collections.sort(sorted);
    assertThat(sorted).hasSize(384);
    for (int i = 36; i < sorted.size(); i++) {
      // La reserva i y la i-36 no pueden caer en la misma ventana de 60 s.
      assertThat(Duration.between(sorted.get(i - 36), sorted.get(i)))
          .isGreaterThanOrEqualTo(Duration.ofSeconds(60));
    }
  }
}
