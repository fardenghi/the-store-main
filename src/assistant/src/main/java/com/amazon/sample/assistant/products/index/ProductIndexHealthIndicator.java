package com.amazon.sample.assistant.products.index;

import com.amazon.sample.assistant.products.index.ProductIndexState.Snapshot;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Componente {@code productIndex} de {@code /actuator/health} (D7): {@code UP}
 * en {@code READY}, {@code DOWN} en {@code FAILED} y {@code UNKNOWN} mientras
 * no empezó o está sincronizando. Igual que {@code qdrant}, no forma parte del
 * grupo de readiness.
 */
public class ProductIndexHealthIndicator implements HealthIndicator {

  private final ProductIndexState state;

  public ProductIndexHealthIndicator(ProductIndexState state) {
    this.state = state;
  }

  @Override
  public Health health() {
    Snapshot snapshot = state.snapshot();
    Health.Builder builder = switch (snapshot.phase()) {
      case READY -> Health.up();
      case FAILED -> Health.down().withDetail("error", snapshot.error());
      case NOT_STARTED, SYNCING -> Health.unknown();
    };
    builder.withDetail("phase", snapshot.phase().name())
        .withDetail("points", snapshot.points());
    if (snapshot.lastSync() != null) {
      builder.withDetail("lastSync", snapshot.lastSync().toString());
    }
    if (snapshot.lastReport() != null) {
      SyncReport report = snapshot.lastReport();
      builder.withDetail("lastRun", Map.of(
          "embedded", report.embedded(),
          "payloadUpdated", report.payloadUpdated(),
          "deleted", report.deleted(),
          "unchanged", report.unchanged(),
          "providerRequests", report.providerRequests(),
          "durationMs", report.duration().toMillis()));
    }
    return builder.build();
  }
}
