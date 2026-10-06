package com.amazon.sample.assistant.health;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.QdrantOuterClass.HealthCheckReply;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Componente {@code qdrant} de {@code /actuator/health}. Es informativo: no
 * forma parte del grupo de readiness (D4).
 */
@Component("qdrant")
public class QdrantHealthIndicator implements HealthIndicator {

  static final Duration TIMEOUT = Duration.ofSeconds(3);

  private final QdrantClient client;

  public QdrantHealthIndicator(QdrantClient client) {
    this.client = client;
  }

  @Override
  public Health health() {
    try {
      HealthCheckReply reply = client.healthCheckAsync(TIMEOUT)
          .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      return Health.up()
          .withDetail("version", reply.getVersion())
          .build();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Health.down(e).build();
    } catch (ExecutionException e) {
      return Health.down(e.getCause() != null ? e.getCause() : e).build();
    } catch (Exception e) {
      return Health.down(e).build();
    }
  }
}
