package com.amazon.sample.assistant.products.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazon.sample.assistant.products.Backoff;
import com.amazon.sample.assistant.products.CatalogFixtures;
import com.amazon.sample.assistant.products.catalog.CatalogClient;
import com.amazon.sample.assistant.products.catalog.CatalogProduct;
import com.amazon.sample.assistant.products.catalog.CatalogReadException;
import com.amazon.sample.assistant.products.embedding.EmbeddingGateway;
import com.amazon.sample.assistant.products.embedding.ProductEmbedder;
import com.amazon.sample.assistant.products.index.ProductIndexState.Phase;
import com.amazon.sample.assistant.products.vector.IndexedProduct;
import com.amazon.sample.assistant.products.vector.ProductVectorRepository;
import com.amazon.sample.assistant.products.vector.QdrantTestSupport;
import com.google.genai.errors.ClientException;
import io.qdrant.client.QdrantClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

/**
 * Escenarios de la spec {@code product-indexing} contra un Qdrant real, con
 * el catálogo y el proveedor de embeddings mockeados.
 */
class ProductIndexerTest {

  private final CatalogClient catalog = mock(CatalogClient.class);
  private final EmbeddingGateway gateway = mock(EmbeddingGateway.class);
  private final List<Duration> sleeps = new ArrayList<>();
  private final ProductIndexState state = new ProductIndexState();

  private QdrantClient client;
  private ProductVectorRepository repository;
  private ProductIndexer indexer;
  private List<CatalogProduct> products;

  @BeforeEach
  void setUp() throws Exception {
    client = QdrantTestSupport.newClient();
    repository = new ProductVectorRepository(client, QdrantTestSupport.uniqueCollection(), 768);
    ProductEmbedder embedder = new ProductEmbedder(gateway, "gemini-embedding-001", 768, 100, 16);
    indexer = new ProductIndexer(catalog, embedder, repository, state, 100, 5, Backoff.DEFAULT,
        sleeps::add, Clock.systemUTC());
    products = CatalogFixtures.realCatalog();
    when(catalog.fetchAll()).thenAnswer(invocation -> products);
    when(gateway.embed(any(), anyList(), any())).thenAnswer(ProductIndexerTest::embed);
  }

  @AfterEach
  void tearDown() {
    client.close();
  }

  private static List<float[]> embed(InvocationOnMock invocation) {
    List<String> texts = invocation.getArgument(1);
    return texts.stream().map(CatalogFixtures::deterministicVector).toList();
  }

  @Test
  void firstSyncEmbedsEverythingInOneRequest() {
    SyncReport report = indexer.sync();

    assertThat(products).hasSize(80);
    assertThat(report.embedded()).isEqualTo(80);
    assertThat(report.providerRequests()).isEqualTo(1);
    verify(gateway, times(1)).embed(any(), anyList(), any());
    assertThat(repository.count()).isEqualTo(80);
    assertThat(state.phase()).isEqualTo(Phase.READY);
    assertThat(state.snapshot().points()).isEqualTo(80);
    assertThat(state.snapshot().lastSync()).isNotNull();

    CatalogProduct first = products.get(0);
    IndexedProduct point = repository.scrollAllPayloads().get(first.id());
    assertThat(point.name()).isEqualTo(first.name());
    assertThat(point.description()).isEqualTo(first.description());
    assertThat(point.price()).isEqualTo(first.price());
    assertThat(point.tags()).isEqualTo(first.tagNames());
  }

  @Test
  void restartWithoutChangesMakesNoRequests() {
    indexer.sync();
    Map<String, IndexedProduct> before = repository.scrollAllPayloads();

    SyncReport report = indexer.sync();

    assertThat(report.providerRequests()).isZero();
    assertThat(report.unchanged()).isEqualTo(80);
    assertThat(report.embedded() + report.payloadUpdated() + report.deleted()).isZero();
    verify(gateway, times(1)).embed(any(), anyList(), any());
    assertThat(repository.scrollAllPayloads()).isEqualTo(before);
  }

  @Test
  void changedDescriptionReembedsOnlyThatProduct() {
    indexer.sync();
    CatalogProduct changed = products.get(5);
    replace(5, new CatalogProduct(changed.id(), changed.name(), "A brand new description.",
        changed.price(), changed.tags()));

    SyncReport report = indexer.sync();

    assertThat(report.embedded()).isEqualTo(1);
    assertThat(report.providerRequests()).isEqualTo(1);
    assertThat(repository.scrollAllPayloads().get(changed.id()).description())
        .isEqualTo("A brand new description.");
  }

  @Test
  void priceOnlyChangeUpdatesPayloadWithoutRequests() {
    indexer.sync();
    CatalogProduct changed = products.get(7);
    replace(7, new CatalogProduct(changed.id(), changed.name(), changed.description(),
        changed.price() + 10, changed.tags()));

    SyncReport report = indexer.sync();

    assertThat(report.providerRequests()).isZero();
    assertThat(report.payloadUpdated()).isEqualTo(1);
    assertThat(repository.scrollAllPayloads().get(changed.id()).price())
        .isEqualTo(changed.price() + 10);
  }

  @Test
  void removedProductIsDeleted() {
    indexer.sync();
    CatalogProduct removed = products.get(0);
    products = products.subList(1, products.size());

    SyncReport report = indexer.sync();

    assertThat(report.deleted()).isEqualTo(1);
    assertThat(repository.count()).isEqualTo(79);
    assertThat(repository.scrollAllPayloads()).doesNotContainKey(removed.id());
  }

  @Test
  void failedCatalogReadDeletesNothing() throws Exception {
    indexer.sync();
    when(catalog.fetchAll()).thenThrow(new CatalogReadException("400 en la página 2", null));

    assertThat(indexer.sync()).isNull();

    assertThat(repository.count()).isEqualTo(80);
    assertThat(state.phase()).isEqualTo(Phase.FAILED);
    assertThat(state.snapshot().error()).startsWith("catalog-unavailable");
  }

  @Test
  void quotaErrorIsRetriedAfterRetryDelay() {
    when(gateway.embed(any(), anyList(), any()))
        .thenThrow(new ClientException(429, "RESOURCE_EXHAUSTED",
            "Quota exceeded. Please retry in 7s."))
        .thenAnswer(ProductIndexerTest::embed);

    SyncReport report = indexer.sync();

    assertThat(report.embedded()).isEqualTo(80);
    assertThat(report.providerRequests()).isEqualTo(2);
    assertThat(sleeps).containsExactly(Duration.ofSeconds(7));
    assertThat(repository.count()).isEqualTo(80);
    assertThat(state.phase()).isEqualTo(Phase.READY);
  }

  @Test
  void quotaRetriesAreBounded() {
    when(gateway.embed(any(), anyList(), any()))
        .thenThrow(new ClientException(429, "RESOURCE_EXHAUSTED", "Quota exceeded"));

    assertThat(indexer.sync()).isNull();

    // 1 intento + 5 reintentos con el backoff (no vino retryDelay).
    verify(gateway, times(6)).embed(any(), anyList(), any());
    assertThat(sleeps).hasSize(5);
    assertThat(state.phase()).isEqualTo(Phase.FAILED);
    assertThat(state.snapshot().error()).startsWith("embedding-quota-exceeded");
  }

  @Test
  void invalidKeyFailsWithoutRetries() {
    when(gateway.embed(any(), anyList(), any()))
        .thenThrow(new ClientException(400, "INVALID_ARGUMENT", "API key not valid."));

    assertThat(indexer.sync()).isNull();

    verify(gateway, times(1)).embed(any(), anyList(), any());
    assertThat(sleeps).isEmpty();
    assertThat(state.phase()).isEqualTo(Phase.FAILED);
    assertThat(state.snapshot().error()).startsWith("embedding-provider-unauthorized");
    assertThat(repository.count()).isZero();
  }

  @Test
  void recreatedCollectionIsFullyReindexed() throws Exception {
    client.createCollectionAsync(repository.collection(),
        io.qdrant.client.grpc.Collections.VectorParams.newBuilder().setSize(384)
            .setDistance(io.qdrant.client.grpc.Collections.Distance.Cosine).build()).get();

    SyncReport report = indexer.sync();

    assertThat(report.embedded()).isEqualTo(80);
    assertThat(repository.count()).isEqualTo(80);
  }

  @Test
  void catalogIsNotReadWhenQdrantIsDown() throws Exception {
    ProductVectorRepository unreachable = new ProductVectorRepository(
        new QdrantClient(io.qdrant.client.QdrantGrpcClient.newBuilder("localhost", 1, false)
            .build()), "products", 768);
    ProductIndexer down = new ProductIndexer(catalog, new ProductEmbedder(gateway,
        "gemini-embedding-001", 768, 100, 16), unreachable, state, 100, 5, Backoff.DEFAULT,
        sleeps::add, Clock.systemUTC());

    assertThat(down.sync()).isNull();

    assertThat(sleeps).hasSize(ProductIndexer.QDRANT_MAX_ATTEMPTS - 1);
    assertThat(state.snapshot().error()).startsWith("vector-store-unavailable");
    verify(catalog, never()).fetchAll();
  }

  private void replace(int index, CatalogProduct product) {
    List<CatalogProduct> copy = new ArrayList<>(products);
    copy.set(index, product);
    products = copy;
  }
}
