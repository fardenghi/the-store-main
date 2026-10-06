package com.amazon.sample.assistant.products.vector;

import static io.qdrant.client.ConditionFactory.hasId;
import static io.qdrant.client.ConditionFactory.matchKeywords;
import static io.qdrant.client.ConditionFactory.range;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.QueryFactory.nearest;
import static io.qdrant.client.ValueFactory.list;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
import static io.qdrant.client.WithPayloadSelectorFactory.enable;

import com.google.common.util.concurrent.ListenableFuture;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.IntegerIndexParams;
import io.qdrant.client.grpc.Collections.PayloadIndexParams;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.PointId;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.Range;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.WithVectorsSelector;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Acceso a la colección {@code products} de Qdrant sobre el
 * {@link QdrantClient} gRPC que autoconfigura el starter (D1). No usa el
 * {@code QdrantVectorStore} de Spring AI.
 */
public class ProductVectorRepository {

  private static final Logger log = LoggerFactory.getLogger(ProductVectorRepository.class);

  static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final int SCROLL_PAGE = 256;

  private final QdrantClient client;
  private final String collection;
  private final int dimensions;

  public ProductVectorRepository(QdrantClient client, String collection, int dimensions) {
    this.client = client;
    this.collection = collection;
    this.dimensions = dimensions;
  }

  public String collection() {
    return collection;
  }

  /**
   * Crea la colección (vector de {@code dimensions}, coseno) y sus índices de
   * payload si no existe. Si existe con otra dimensión o distancia, la borra y
   * la recrea, lo que obliga a reindexar todo (D2).
   *
   * @return {@code true} si la colección se creó o se recreó
   */
  public boolean ensureCollection() {
    boolean created = false;
    if (!await(client.collectionExistsAsync(collection, TIMEOUT))) {
      create();
      created = true;
    } else {
      VectorParams current = vectorParams(await(client.getCollectionInfoAsync(collection, TIMEOUT)));
      if (current == null || current.getSize() != dimensions
          || current.getDistance() != Distance.Cosine) {
        log.warn("La colección '{}' existe con {} dimensiones y distancia {}; se recrea con {} y "
            + "Cosine y se reindexa todo el catálogo", collection,
            current == null ? "?" : current.getSize(),
            current == null ? "?" : current.getDistance(), dimensions);
        await(client.deleteCollectionAsync(collection, TIMEOUT));
        create();
        created = true;
      }
    }
    return created;
  }

  private void create() {
    await(client.createCollectionAsync(collection, VectorParams.newBuilder()
        .setSize(dimensions)
        .setDistance(Distance.Cosine)
        .build(), TIMEOUT));
    await(client.createPayloadIndexAsync(collection, "tags", PayloadSchemaType.Keyword,
        null, true, null, TIMEOUT));
    await(client.createPayloadIndexAsync(collection, "price", PayloadSchemaType.Integer,
        PayloadIndexParams.newBuilder()
            .setIntegerIndexParams(IntegerIndexParams.newBuilder().setLookup(true).setRange(true))
            .build(),
        true, null, TIMEOUT));
    log.info("Colección '{}' creada: {} dimensiones, distancia Cosine, índices tags y price",
        collection, dimensions);
  }

  private static VectorParams vectorParams(CollectionInfo info) {
    var vectorsConfig = info.getConfig().getParams().getVectorsConfig();
    return vectorsConfig.hasParams() ? vectorsConfig.getParams() : null;
  }

  /** Vector y distancia actuales de la colección, para los tests. */
  VectorParams currentVectorParams() {
    return vectorParams(await(client.getCollectionInfoAsync(collection, TIMEOUT)));
  }

  /** Payload de todos los puntos, por id, sin vectores. */
  public Map<String, IndexedProduct> scrollAllPayloads() {
    Map<String, IndexedProduct> result = new LinkedHashMap<>();
    PointId offset = null;
    do {
      ScrollPoints.Builder request = ScrollPoints.newBuilder()
          .setCollectionName(collection)
          .setLimit(SCROLL_PAGE)
          .setWithPayload(enable(true))
          .setWithVectors(WithVectorsSelector.newBuilder().setEnable(false));
      if (offset != null) {
        request.setOffset(offset);
      }
      ScrollResponse response = await(client.scrollAsync(request.build(), TIMEOUT));
      for (RetrievedPoint point : response.getResultList()) {
        IndexedProduct product = fromPayload(point.getPayloadMap());
        result.put(product.id(), product);
      }
      offset = response.hasNextPageOffset() ? response.getNextPageOffset() : null;
    } while (offset != null);
    return result;
  }

  /** Inserta o reemplaza los puntos (vector y payload). El id del punto es el del producto. */
  public void upsert(List<ProductPoint> points) {
    if (points.isEmpty()) {
      return;
    }
    List<PointStruct> structs = points.stream()
        .map(point -> PointStruct.newBuilder()
            .setId(id(UUID.fromString(point.product().id())))
            .setVectors(vectors(point.vector()))
            .putAllPayload(toPayload(point.product()))
            .build())
        .toList();
    await(client.upsertAsync(collection, structs, TIMEOUT));
  }

  /** Reemplaza el payload de un punto sin tocar su vector. */
  public void setPayload(IndexedProduct product) {
    await(client.setPayloadAsync(collection, toPayload(product),
        id(UUID.fromString(product.id())), true, null, TIMEOUT));
  }

  public void deleteByIds(Collection<String> ids) {
    if (ids.isEmpty()) {
      return;
    }
    List<PointId> pointIds = ids.stream().map(id -> id(UUID.fromString(id))).toList();
    await(client.deleteAsync(collection, pointIds, TIMEOUT));
  }

  /** Cantidad de puntos; 0 si la colección no existe. */
  public long count() {
    try {
      return await(client.countAsync(collection, TIMEOUT));
    } catch (VectorStoreException e) {
      if (e.isNotFound()) {
        return 0;
      }
      throw e;
    }
  }

  /**
   * Los {@code k} puntos más cercanos al vector que cumplen los filtros (D8):
   * {@code tags} con semántica OR (match any) y {@code price} en el rango
   * inclusivo. Los filtros ausentes no se aplican.
   */
  public List<ScoredProduct> search(float[] vector, List<String> tags, Integer minPrice,
      Integer maxPrice, int k) {
    SearchPoints.Builder request = SearchPoints.newBuilder()
        .setCollectionName(collection)
        .addAllVector(toList(vector))
        .setLimit(k)
        .setWithPayload(enable(true));
    Filter filter = filter(tags, minPrice, maxPrice);
    if (filter != null) {
      request.setFilter(filter);
    }
    return await(client.searchAsync(request.build(), TIMEOUT)).stream()
        .map(ProductVectorRepository::toScored)
        .toList();
  }

  static Filter filter(List<String> tags, Integer minPrice, Integer maxPrice) {
    Filter.Builder filter = Filter.newBuilder();
    if (tags != null && !tags.isEmpty()) {
      filter.addMust(matchKeywords("tags", tags));
    }
    if (minPrice != null || maxPrice != null) {
      Range.Builder range = Range.newBuilder();
      if (minPrice != null) {
        range.setGte(minPrice);
      }
      if (maxPrice != null) {
        range.setLte(maxPrice);
      }
      filter.addMust(range("price", range.build()));
    }
    return filter.getMustCount() == 0 ? null : filter.build();
  }

  /**
   * Los {@code k} puntos más parecidos al punto {@code id}, usando su vector
   * guardado (Query API con el id del punto como consulta) y excluyéndolo. No
   * trae vectores ni llama al proveedor de embeddings.
   *
   * @return vacío si el punto no existe
   */
  public Optional<List<ScoredProduct>> similar(String id, int k) {
    PointId pointId = id(UUID.fromString(id));
    List<RetrievedPoint> existing = await(client.retrieveAsync(collection, List.of(pointId),
        enable(false), WithVectorsSelector.newBuilder().setEnable(false).build(), null, TIMEOUT));
    if (existing.isEmpty()) {
      return Optional.empty();
    }
    QueryPoints request = QueryPoints.newBuilder()
        .setCollectionName(collection)
        .setQuery(nearest(pointId))
        .setFilter(Filter.newBuilder().addMustNot(hasId(pointId)))
        .setLimit(k)
        .setWithPayload(enable(true))
        .setWithVectors(WithVectorsSelector.newBuilder().setEnable(false))
        .build();
    return Optional.of(await(client.queryAsync(request, TIMEOUT)).stream()
        .map(ProductVectorRepository::toScored)
        .toList());
  }

  private static ScoredProduct toScored(ScoredPoint point) {
    return new ScoredProduct(fromPayload(point.getPayloadMap()), point.getScore());
  }

  static Map<String, Value> toPayload(IndexedProduct product) {
    Map<String, Value> payload = new HashMap<>();
    payload.put("id", value(product.id()));
    payload.put("name", value(product.name()));
    payload.put("description", value(product.description()));
    payload.put("price", value(product.price()));
    payload.put("tags", list(product.tags().stream().map(tag -> value(tag)).toList()));
    payload.put("contentHash", value(product.contentHash()));
    return payload;
  }

  static IndexedProduct fromPayload(Map<String, Value> payload) {
    List<String> tags = new ArrayList<>();
    Value tagsValue = payload.get("tags");
    if (tagsValue != null && tagsValue.hasListValue()) {
      tagsValue.getListValue().getValuesList().forEach(tag -> tags.add(tag.getStringValue()));
    }
    return new IndexedProduct(
        string(payload, "id"),
        string(payload, "name"),
        string(payload, "description"),
        payload.containsKey("price") ? payload.get("price").getIntegerValue() : 0,
        tags,
        string(payload, "contentHash"));
  }

  private static String string(Map<String, Value> payload, String key) {
    Value value = payload.get(key);
    return value == null ? "" : value.getStringValue();
  }

  private static List<Float> toList(float[] vector) {
    List<Float> list = new ArrayList<>(vector.length);
    for (float v : vector) {
      list.add(v);
    }
    return list;
  }

  private static <T> T await(ListenableFuture<T> future) {
    try {
      return future.get(TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new VectorStoreException("Interrumpido esperando a Qdrant", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new VectorStoreException("Error de Qdrant: " + cause.getMessage(), cause);
    } catch (TimeoutException e) {
      throw new VectorStoreException("Timeout esperando a Qdrant", e);
    }
  }
}
