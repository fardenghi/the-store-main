package com.amazon.sample.assistant.tools;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazon.sample.assistant.carts.CartsClient;
import com.amazon.sample.assistant.chat.rewrite.CatalogTagsCache;
import com.amazon.sample.assistant.config.ToolsProperties;
import com.amazon.sample.assistant.products.catalog.CatalogClient;
import com.amazon.sample.assistant.products.catalog.CatalogProduct;
import com.amazon.sample.assistant.products.search.ProductResult;
import com.amazon.sample.assistant.products.search.ProductSearchService;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Sinks;

/** Mocks de los servicios y helpers comunes de los tests de las tools. */
class ToolsFixtures {

  static final ToolsProperties PROPERTIES = new ToolsProperties(4, 6, 10, 5, 10, 300,
      new ToolsProperties.Http(Duration.ofSeconds(2), Duration.ofSeconds(5)),
      ToolsProperties.CorrectiveToolChoice.REQUIRED);

  static final List<String> TAGS = List.of("seating", "lighting", "tables", "dining", "velvet",
      "leather", "decor");

  final CatalogClient catalog = mock(CatalogClient.class);
  final CartsClient carts = mock(CartsClient.class);
  final ProductSearchService search = mock(ProductSearchService.class);
  final CatalogTagsCache tagsCache = mock(CatalogTagsCache.class);
  final StoreTools tools;
  final Sinks.Many<ServerSentEvent<?>> sink = Sinks.many().replay().all();
  final TurnToolContext turn;
  final ToolContext context;

  ToolsFixtures() {
    this(PROPERTIES);
  }

  ToolsFixtures(ToolsProperties properties) {
    when(tagsCache.tagNames()).thenReturn(TAGS);
    tools = new StoreTools(catalog, carts, search, new ToolArguments(tagsCache, properties),
        properties);
    turn = new TurnToolContext("session-0123456789", sink, properties.maxToolCalls());
    context = new ToolContext(turn.asToolContext());
  }

  static String id(String seed) {
    return UUID.nameUUIDFromBytes(seed.getBytes()).toString();
  }

  static CatalogProduct product(String seed, String name, int price, String... tags) {
    return new CatalogProduct(id(seed), name, "Description of " + name, price,
        Arrays.stream(tags).map(tag -> new CatalogProduct.Tag(tag, tag)).toList());
  }

  /** Resultado del índice con el precio del payload (que puede estar viejo). */
  static ProductResult indexed(CatalogProduct product, long payloadPrice, float score) {
    return new ProductResult(product.id(), product.name(), product.description(), payloadPrice,
        product.tagNames(), score);
  }
}
