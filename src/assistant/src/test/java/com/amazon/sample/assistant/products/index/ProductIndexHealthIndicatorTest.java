package com.amazon.sample.assistant.products.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class ProductIndexHealthIndicatorTest {

  private final ProductIndexState state = new ProductIndexState();
  private final ProductIndexHealthIndicator indicator = new ProductIndexHealthIndicator(state);

  @Test
  void unknownBeforeStarting() {
    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
    assertThat(health.getDetails()).containsEntry("phase", "NOT_STARTED");
  }

  @Test
  void unknownWhileSyncing() {
    state.syncing();

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
    assertThat(health.getDetails()).containsEntry("phase", "SYNCING");
  }

  @Test
  void upWhenReadyWithPointsAndLastSync() {
    Instant at = Instant.parse("2026-10-06T12:00:00Z");
    state.ready(80, at, new SyncReport(80, 80, 0, 0, 0, 1, Duration.ofMillis(900)));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("phase", "READY")
        .containsEntry("points", 80L)
        .containsEntry("lastSync", "2026-10-06T12:00:00Z")
        .containsKey("lastRun");
  }

  @Test
  void downWhenFailedWithReason() {
    state.syncing();
    state.failed("embedding-provider-unauthorized: Gemini respondió 400");

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("phase", "FAILED")
        .containsEntry("error", "embedding-provider-unauthorized: Gemini respondió 400");
  }
}
