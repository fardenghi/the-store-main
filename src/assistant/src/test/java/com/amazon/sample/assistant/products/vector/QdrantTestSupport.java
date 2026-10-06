package com.amazon.sample.assistant.products.vector;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.util.UUID;
import org.testcontainers.qdrant.QdrantContainer;

/**
 * Qdrant real para los tests de integración: un contenedor compartido por
 * toda la corrida (lo limpia Ryuk al terminar) y una colección distinta por
 * test para que no se pisen.
 */
public final class QdrantTestSupport {

  public static final String IMAGE = "qdrant/qdrant:v1.19.2";

  private static final QdrantContainer CONTAINER = new QdrantContainer(IMAGE);

  static {
    CONTAINER.start();
  }

  private QdrantTestSupport() {
  }

  public static String host() {
    return CONTAINER.getHost();
  }

  public static int grpcPort() {
    return CONTAINER.getMappedPort(6334);
  }

  public static QdrantClient newClient() {
    return new QdrantClient(QdrantGrpcClient.newBuilder(host(), grpcPort(), false).build());
  }

  public static String uniqueCollection() {
    return "products_" + UUID.randomUUID().toString().replace("-", "");
  }

  /** Vector sintético de {@code dimensions} con peso en las posiciones indicadas. */
  public static float[] vector(int dimensions, float... firstComponents) {
    float[] vector = new float[dimensions];
    System.arraycopy(firstComponents, 0, vector, 0, firstComponents.length);
    return vector;
  }
}
