package com.amazon.sample.assistant.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.QdrantOuterClass.HealthCheckReply;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class QdrantHealthIndicatorTest {

  private final QdrantClient client = mock(QdrantClient.class);
  private final QdrantHealthIndicator indicator = new QdrantHealthIndicator(client);

  @Test
  void upWithServerVersion() {
    HealthCheckReply reply = HealthCheckReply.newBuilder()
        .setTitle("qdrant - vector search engine")
        .setVersion("1.19.2")
        .build();
    when(client.healthCheckAsync(any(Duration.class))).thenReturn(Futures.immediateFuture(reply));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("version", "1.19.2");
  }

  @Test
  void downWithError() {
    when(client.healthCheckAsync(any(Duration.class))).thenReturn(
        Futures.immediateFailedFuture(io.grpc.Status.UNAVAILABLE.asRuntimeException()));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails().get("error").toString()).contains("UNAVAILABLE");
  }
}
