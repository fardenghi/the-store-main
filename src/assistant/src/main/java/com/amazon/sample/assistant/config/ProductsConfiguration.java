package com.amazon.sample.assistant.config;

import com.amazon.sample.assistant.products.Backoff;
import com.amazon.sample.assistant.products.Backoff.Sleeper;
import com.amazon.sample.assistant.products.catalog.CatalogClient;
import com.amazon.sample.assistant.products.embedding.EmbeddingGateway;
import com.amazon.sample.assistant.products.embedding.GoogleGenAiEmbeddingGateway;
import com.amazon.sample.assistant.products.embedding.ProductEmbedder;
import com.amazon.sample.assistant.products.index.ProductIndexHealthIndicator;
import com.amazon.sample.assistant.products.index.ProductIndexStartup;
import com.amazon.sample.assistant.products.index.ProductIndexState;
import com.amazon.sample.assistant.products.index.ProductIndexer;
import com.amazon.sample.assistant.products.search.ProductSearchService;
import com.amazon.sample.assistant.products.search.SimilarProductsService;
import com.amazon.sample.assistant.products.vector.ProductVectorRepository;
import io.qdrant.client.QdrantClient;
import java.time.Clock;
import org.springframework.ai.google.genai.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

/**
 * Indexación del catálogo en Qdrant, búsqueda semántica y similares
 * (add-product-indexing).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({IndexingProperties.class, SearchProperties.class})
public class ProductsConfiguration {

  @Bean
  EmbeddingGateway embeddingGateway(GoogleGenAiEmbeddingConnectionDetails connectionDetails) {
    return new GoogleGenAiEmbeddingGateway(connectionDetails);
  }

  /**
   * Modelo y dimensiones salen de las mismas propiedades que configuran el
   * {@code EmbeddingModel} del starter, para que no puedan desalinearse (D2).
   */
  @Bean
  ProductEmbedder productEmbedder(EmbeddingGateway gateway,
      @Value("${spring.ai.google.genai.embedding.text.options.model}") String model,
      @Value("${spring.ai.google.genai.embedding.text.options.dimensions}") int dimensions,
      IndexingProperties indexing, SearchProperties search) {
    return new ProductEmbedder(gateway, model, dimensions, indexing.batchSize(),
        search.queryCacheSize());
  }

  @Bean
  ProductVectorRepository productVectorRepository(QdrantClient client,
      @Value("${spring.ai.vectorstore.qdrant.collection-name}") String collection,
      @Value("${spring.ai.google.genai.embedding.text.options.dimensions}") int dimensions) {
    return new ProductVectorRepository(client, collection, dimensions);
  }

  @Bean
  CatalogClient catalogClient(RestClient.Builder builder,
      @Value("${retail.assistant.endpoints.catalog}") String catalogEndpoint,
      IndexingProperties indexing, ToolsProperties tools) {
    RestClient toolsRestClient = ToolsConfiguration.toolsRestClient(builder, catalogEndpoint,
        tools.http());
    return new CatalogClient(builder.baseUrl(catalogEndpoint).build(), toolsRestClient,
        indexing.pageSize(), Backoff.DEFAULT, Sleeper.THREAD);
  }

  @Bean
  ProductIndexState productIndexState() {
    return new ProductIndexState();
  }

  @Bean
  ProductIndexer productIndexer(CatalogClient catalog, ProductEmbedder embedder,
      ProductVectorRepository repository, ProductIndexState state, IndexingProperties indexing) {
    return new ProductIndexer(catalog, embedder, repository, state, indexing.batchSize(),
        indexing.maxProviderRetries(), Backoff.DEFAULT, Sleeper.THREAD, Clock.systemUTC());
  }

  /** Componente {@code productIndex} de {@code /actuator/health}, fuera de la readiness (D7). */
  @Bean("productIndex")
  ProductIndexHealthIndicator productIndexHealthIndicator(ProductIndexState state) {
    return new ProductIndexHealthIndicator(state);
  }

  /**
   * Un hilo para la sincronización (D5). No se registra como bean
   * {@code TaskExecutor} para no reemplazar el executor que autoconfigura
   * Spring Boot; el ciclo de vida lo maneja {@link ProductIndexStartup}.
   */
  @Bean
  @ConditionalOnProperty(name = "retail.assistant.indexing.sync-on-startup", havingValue = "true")
  ProductIndexStartup productIndexStartup(ProductIndexer indexer) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.setThreadNamePrefix("product-index-");
    executor.setDaemon(true);
    executor.initialize();
    return new ProductIndexStartup(indexer, executor);
  }

  @Bean
  ProductSearchService productSearchService(ProductEmbedder embedder,
      ProductVectorRepository repository, SearchProperties search) {
    return new ProductSearchService(embedder, repository, search);
  }

  @Bean
  SimilarProductsService similarProductsService(ProductVectorRepository repository,
      SearchProperties search) {
    return new SimilarProductsService(repository, search);
  }
}
