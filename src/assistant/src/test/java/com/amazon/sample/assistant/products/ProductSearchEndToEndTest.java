package com.amazon.sample.assistant.products;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.amazon.sample.assistant.config.ApiKeysStartupLogger;
import com.amazon.sample.assistant.products.catalog.CatalogClient;
import com.amazon.sample.assistant.products.catalog.CatalogProduct;
import com.amazon.sample.assistant.products.embedding.EmbeddingGateway;
import com.amazon.sample.assistant.products.index.ProductIndexer;
import com.amazon.sample.assistant.products.index.SyncReport;
import com.amazon.sample.assistant.products.search.ProductResult;
import com.amazon.sample.assistant.products.vector.QdrantTestSupport;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * De punta a punta dentro del proceso: sincroniza el catálogo real contra un
 * Qdrant real (Testcontainers) con embeddings deterministas, y prueba la API
 * HTTP de búsqueda y similares.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "spring.ai.openai.api-key=" + ApiKeysStartupLogger.PLACEHOLDER,
    "spring.ai.google.genai.embedding.api-key=" + ApiKeysStartupLogger.PLACEHOLDER
})
class ProductSearchEndToEndTest {

  private static final ParameterizedTypeReference<List<ProductResult>> RESULTS =
      new ParameterizedTypeReference<>() { };

  @DynamicPropertySource
  static void qdrant(DynamicPropertyRegistry registry) {
    registry.add("spring.ai.vectorstore.qdrant.host", QdrantTestSupport::host);
    registry.add("spring.ai.vectorstore.qdrant.port", QdrantTestSupport::grpcPort);
    registry.add("spring.ai.vectorstore.qdrant.collection-name",
        QdrantTestSupport::uniqueCollection);
  }

  @MockitoBean
  private CatalogClient catalog;

  @MockitoBean
  private EmbeddingGateway gateway;

  @Autowired
  private ProductIndexer indexer;

  @Autowired
  private TestRestTemplate rest;

  private Map<String, CatalogProduct> products;

  @BeforeEach
  void sync() throws Exception {
    List<CatalogProduct> catalogProducts = CatalogFixtures.realCatalog();
    products = catalogProducts.stream()
        .collect(Collectors.toMap(CatalogProduct::id, Function.identity()));
    when(catalog.fetchAll()).thenReturn(catalogProducts);
    when(gateway.embed(any(), anyList(), any())).thenAnswer(invocation -> {
      List<String> texts = invocation.getArgument(1);
      return texts.stream().map(CatalogFixtures::deterministicVector).toList();
    });
    SyncReport report = indexer.sync();
    assertThat(report).isNotNull();
  }

  @Test
  void tagFilterReturnsOnlyProductsWithSomeTag() {
    List<ProductResult> results = search("q=something to sit on&tags=velvet,leather&k=20");

    assertThat(results).isNotEmpty().hasSizeLessThanOrEqualTo(20);
    assertThat(results).allSatisfy(r ->
        assertThat(r.tags()).containsAnyOf("velvet", "leather"));
    assertThat(results).extracting(ProductResult::tags)
        .anySatisfy(tags -> assertThat(tags).contains("velvet"))
        .anySatisfy(tags -> assertThat(tags).contains("leather"));
    assertSortedByScore(results);
  }

  @Test
  void maxPriceIsRespected() {
    List<ProductResult> results = search("q=lamp&maxPrice=100&k=20");

    assertThat(results).isNotEmpty();
    assertThat(results).allSatisfy(r -> assertThat(r.price()).isLessThanOrEqualTo(100));
  }

  @Test
  void tagsAndPriceCombined() {
    List<ProductResult> results =
        search("q=comfortable seat&tags=seating&minPrice=100&maxPrice=400&k=20");

    assertThat(results).allSatisfy(r -> {
      assertThat(r.tags()).contains("seating");
      assertThat(r.price()).isBetween(100L, 400L);
    });
  }

  @Test
  void unknownTagReturnsEmptyArray() {
    assertThat(search("q=chair&tags=spaceship")).isEmpty();
  }

  @Test
  void resultsMatchCatalogData() {
    ProductResult first = search("q=sofa&k=1").get(0);

    CatalogProduct product = products.get(first.id());
    assertThat(first.name()).isEqualTo(product.name());
    assertThat(first.description()).isEqualTo(product.description());
    assertThat(first.price()).isEqualTo(product.price());
    assertThat(first.tags()).isEqualTo(product.tagNames());
  }

  @Test
  void similarExcludesTheProduct() {
    String id = products.keySet().iterator().next();

    ResponseEntity<List<ProductResult>> response = rest.exchange(
        "/assistant/products/" + id + "/similar?k=4", HttpMethod.GET, null, RESULTS);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).hasSize(4)
        .extracting(ProductResult::id).doesNotContain(id).doesNotHaveDuplicates();
    assertSortedByScore(response.getBody());
  }

  @Test
  void similarOfUnknownIdIs404() {
    ResponseEntity<String> response = rest.getForEntity(
        "/assistant/products/00000000-0000-0000-0000-000000000000/similar", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).contains("product-not-found");
  }

  @Test
  void emptyQueryIs400() {
    ResponseEntity<String> response = rest.getForEntity("/assistant/products/search?q=",
        String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("invalid-parameter").contains("\"parameter\":\"q\"");
  }

  private List<ProductResult> search(String query) {
    ResponseEntity<List<ProductResult>> response = rest.exchange(
        "/assistant/products/search?" + query, HttpMethod.GET, null, RESULTS);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return response.getBody();
  }

  private static void assertSortedByScore(List<ProductResult> results) {
    for (int i = 1; i < results.size(); i++) {
      assertThat(results.get(i - 1).score()).isGreaterThanOrEqualTo(results.get(i).score());
    }
  }
}
