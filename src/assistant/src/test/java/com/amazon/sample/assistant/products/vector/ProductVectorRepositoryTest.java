package com.amazon.sample.assistant.products.vector;

import static com.amazon.sample.assistant.products.vector.QdrantTestSupport.vector;
import static org.assertj.core.api.Assertions.assertThat;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.WithVectorsSelectorFactory;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests contra un Qdrant real (Testcontainers, requiere Docker). */
class ProductVectorRepositoryTest {

  private static final int DIMS = 768;

  private QdrantClient client;
  private String collection;
  private ProductVectorRepository repository;

  @BeforeEach
  void setUp() {
    client = QdrantTestSupport.newClient();
    collection = QdrantTestSupport.uniqueCollection();
    repository = new ProductVectorRepository(client, collection, DIMS);
  }

  @AfterEach
  void tearDown() {
    client.close();
  }

  // 4.1

  @Test
  void createsCollectionWhenMissing() throws Exception {
    assertThat(repository.count()).isZero();

    assertThat(repository.ensureCollection()).isTrue();

    VectorParams params = repository.currentVectorParams();
    assertThat(params.getSize()).isEqualTo(768);
    assertThat(params.getDistance()).isEqualTo(Distance.Cosine);
    var schema = client.getCollectionInfoAsync(collection).get().getPayloadSchemaMap();
    assertThat(schema.get("tags").getDataType()).isEqualTo(PayloadSchemaType.Keyword);
    assertThat(schema.get("price").getDataType()).isEqualTo(PayloadSchemaType.Integer);
    assertThat(schema.get("price").getParams().getIntegerIndexParams().getRange()).isTrue();
  }

  @Test
  void keepsMatchingCollection() {
    repository.ensureCollection();
    repository.upsert(List.of(point("Chair", 100, List.of("seating"), 1f)));

    assertThat(repository.ensureCollection()).isFalse();
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  void recreatesCollectionWithOtherDimensions() throws Exception {
    client.createCollectionAsync(collection, VectorParams.newBuilder()
        .setSize(384).setDistance(Distance.Cosine).build()).get();

    assertThat(repository.ensureCollection()).isTrue();

    assertThat(repository.currentVectorParams().getSize()).isEqualTo(768);
    assertThat(repository.currentVectorParams().getDistance()).isEqualTo(Distance.Cosine);
  }

  @Test
  void recreatesCollectionWithOtherDistance() throws Exception {
    client.createCollectionAsync(collection, VectorParams.newBuilder()
        .setSize(768).setDistance(Distance.Dot).build()).get();

    assertThat(repository.ensureCollection()).isTrue();

    assertThat(repository.currentVectorParams().getDistance()).isEqualTo(Distance.Cosine);
  }

  // 4.2

  @Test
  void repeatedUpsertKeepsOnePoint() {
    repository.ensureCollection();
    ProductPoint chair = point("Chair", 100, List.of("seating"), 1f);

    repository.upsert(List.of(chair));
    repository.upsert(List.of(chair));

    assertThat(repository.count()).isEqualTo(1);
    assertThat(repository.scrollAllPayloads().get(chair.product().id())).isEqualTo(chair.product());
  }

  @Test
  void setPayloadChangesPriceWithoutTouchingVector() throws Exception {
    repository.ensureCollection();
    ProductPoint chair = point("Chair", 100, List.of("seating"), 1f, 2f);
    repository.upsert(List.of(chair));
    List<Float> before = storedVector(chair.product().id());

    IndexedProduct cheaper = new IndexedProduct(chair.product().id(), "Chair",
        chair.product().description(), 80, List.of("seating"), chair.product().contentHash());
    repository.setPayload(cheaper);

    assertThat(repository.scrollAllPayloads().get(cheaper.id()).price()).isEqualTo(80);
    assertThat(storedVector(cheaper.id())).isEqualTo(before);
  }

  @Test
  void deleteByIdsDeletesOnlyThoseIds() {
    repository.ensureCollection();
    ProductPoint a = point("A", 1, List.of("seating"), 1f);
    ProductPoint b = point("B", 2, List.of("seating"), 0f, 1f);
    ProductPoint c = point("C", 3, List.of("seating"), 0f, 0f, 1f);
    repository.upsert(List.of(a, b, c));

    repository.deleteByIds(List.of(a.product().id(), c.product().id()));

    assertThat(repository.scrollAllPayloads()).containsOnlyKeys(b.product().id());
  }

  @Test
  void scrollReadsAllPagesOfPayloads() {
    repository.ensureCollection();
    List<ProductPoint> points = java.util.stream.IntStream.range(0, 300)
        .mapToObj(i -> point("P" + i, i, List.of("decor"), 1f, i))
        .toList();
    repository.upsert(points);

    assertThat(repository.scrollAllPayloads()).hasSize(300);
    assertThat(repository.count()).isEqualTo(300);
  }

  // 4.3

  @Test
  void searchWithTwoTagsReturnsUnion() {
    seedFurniture();

    List<ScoredProduct> result = repository.search(vector(DIMS, 1f), List.of("velvet", "leather"),
        null, null, 20);

    assertThat(result).extracting(r -> r.product().name())
        .containsExactlyInAnyOrder("Velvet Sofa", "Leather Chair", "Velvet Leather Ottoman")
        .doesNotHaveDuplicates();
  }

  @Test
  void searchWithUnknownTagReturnsNothing() {
    seedFurniture();

    assertThat(repository.search(vector(DIMS, 1f), List.of("spaceship"), null, null, 20)).isEmpty();
  }

  @Test
  void priceRangeIsInclusive() {
    seedFurniture();

    List<ScoredProduct> result = repository.search(vector(DIMS, 1f), List.of(), 100, 400, 20);

    assertThat(result).extracting(r -> r.product().price())
        .containsExactlyInAnyOrder(100L, 250L, 400L);
  }

  @Test
  void tagsAndPriceApplyTogether() {
    seedFurniture();

    List<ScoredProduct> result = repository.search(vector(DIMS, 1f), List.of("seating"), 100, 400,
        20);

    assertThat(result).allSatisfy(r -> {
      assertThat(r.product().tags()).contains("seating");
      assertThat(r.product().price()).isBetween(100L, 400L);
    });
    assertThat(result).extracting(r -> r.product().name())
        .containsExactlyInAnyOrder("Leather Chair", "Velvet Leather Ottoman");
  }

  @Test
  void searchIsOrderedByScoreAndLimitedToK() {
    seedFurniture();

    List<ScoredProduct> result = repository.search(vector(DIMS, 0f, 1f), List.of(), null, null, 2);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).score()).isGreaterThanOrEqualTo(result.get(1).score());
  }

  // 4.4

  @Test
  void similarExcludesItselfIsOrderedAndLimited() {
    repository.ensureCollection();
    ProductPoint target = point("Target", 1, List.of("seating"), 1f, 0f);
    ProductPoint near = point("Near", 2, List.of("seating"), 0.9f, 0.1f);
    ProductPoint mid = point("Mid", 3, List.of("seating"), 0.5f, 0.5f);
    ProductPoint far = point("Far", 4, List.of("seating"), 0f, 1f);
    repository.upsert(List.of(target, near, mid, far));

    List<ScoredProduct> result = repository.similar(target.product().id(), 2).orElseThrow();

    assertThat(result).extracting(r -> r.product().name()).containsExactly("Near", "Mid");
    assertThat(result.get(0).score()).isGreaterThanOrEqualTo(result.get(1).score());
  }

  @Test
  void similarOfMissingPointIsEmpty() {
    seedFurniture();

    assertThat(repository.similar(UUID.randomUUID().toString(), 4)).isEmpty();
  }

  private void seedFurniture() {
    repository.ensureCollection();
    repository.upsert(List.of(
        point("Velvet Sofa", 1769, List.of("seating", "velvet"), 1f, 0.1f),
        point("Leather Chair", 250, List.of("seating", "leather"), 0.9f, 0.3f),
        point("Velvet Leather Ottoman", 100, List.of("seating", "velvet", "leather"), 0.8f, 0.5f),
        point("Oak Table", 400, List.of("tables", "wood"), 0.2f, 1f),
        point("Floor Lamp", 99, List.of("lighting"), 0.1f, 0.9f),
        point("Rug", 401, List.of("rugs"), 0f, 1f)));
  }

  private List<Float> storedVector(String id) throws Exception {
    List<RetrievedPoint> points = client.retrieveAsync(collection,
        List.of(PointIdFactory.id(UUID.fromString(id))), WithPayloadSelectorFactory.enable(false),
        WithVectorsSelectorFactory.enable(true), null).get();
    return points.get(0).getVectors().getVector().getDataList();
  }

  static ProductPoint point(String name, long price, List<String> tags, float... components) {
    String id = UUID.nameUUIDFromBytes(name.getBytes()).toString();
    return new ProductPoint(new IndexedProduct(id, name, name + " description", price, tags,
        "hash-" + name), vector(DIMS, components));
  }
}
