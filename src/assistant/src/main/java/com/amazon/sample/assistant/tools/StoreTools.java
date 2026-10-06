package com.amazon.sample.assistant.tools;

import com.amazon.sample.assistant.carts.CartsClient;
import com.amazon.sample.assistant.carts.CartsUnavailableException;
import com.amazon.sample.assistant.chat.session.ShownProduct;
import com.amazon.sample.assistant.config.ToolsProperties;
import com.amazon.sample.assistant.products.catalog.CatalogClient;
import com.amazon.sample.assistant.products.catalog.CatalogProduct;
import com.amazon.sample.assistant.products.catalog.CatalogUnavailableException;
import com.amazon.sample.assistant.products.embedding.EmbeddingProviderException;
import com.amazon.sample.assistant.products.search.IndexUnavailableException;
import com.amazon.sample.assistant.products.search.ProductResult;
import com.amazon.sample.assistant.products.search.ProductSearchService;
import com.amazon.sample.assistant.products.vector.VectorStoreException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.codec.ServerSentEvent;

/**
 * Las tres tools del modelo principal (D2 a D7 de {@code add-assistant-tools}):
 * búsqueda con filtros estructurados, detalle con precio vivo y agregar al
 * carrito de la sesión.
 *
 * <p>Cada tool valida sus argumentos antes de llamar a cualquier servicio (D3),
 * devuelve los errores como resultado para el modelo (D2), emite el evento
 * {@code tool} (y {@code cart-updated}) por el canal del turno y deja una línea
 * en el logger {@code assistant.tool} (D7, D12). El contexto del turno (la
 * sesión, que es el {@code customerId}) llega por el {@link ToolContext} y no
 * es un parámetro que el modelo vea o pueda elegir.
 */
public class StoreTools {

  public static final String SEARCH_PRODUCTS = "searchProducts";
  public static final String GET_PRODUCT_DETAILS = "getProductDetails";
  public static final String ADD_TO_CART = "addToCart";

  /** Tamaño de página al recorrer {@code GET /catalog/products} y tope de páginas (D4). */
  static final int CATALOG_PAGE_SIZE = 50;
  static final int CATALOG_MAX_PAGES = 10;

  private static final Logger log = LoggerFactory.getLogger(StoreTools.class);

  private final CatalogClient catalog;
  private final CartsClient carts;
  private final ProductSearchService search;
  private final ToolArguments arguments;
  private final ToolsProperties properties;
  private final ToolLogger toolLogger = new ToolLogger();

  public StoreTools(CatalogClient catalog, CartsClient carts, ProductSearchService search,
      ToolArguments arguments, ToolsProperties properties) {
    this.catalog = catalog;
    this.carts = carts;
    this.search = search;
    this.arguments = arguments;
    this.properties = properties;
  }

  @Tool(name = SEARCH_PRODUCTS, description = """
      Search the products of The Store. Use it when the shopper asks for products of a \
      category, within a budget or ordered by price, or for products that are not in the \
      product context. Map a category to catalog tags, a budget to minPrice/maxPrice and \
      "cheapest first" to order price_asc. Prices in the result are the current catalog \
      prices. At least one of query, tags, minPrice or maxPrice is required.""")
  public Map<String, Object> searchProducts(
      @ToolParam(required = false, description = "What the shopper is looking for, as a short "
          + "English search query (product type and attributes). Omit it to list products by "
          + "tags and price only") String query,
      @ToolParam(required = false, description = "Catalog tag names for the category the shopper "
          + "asks for (for example lighting for lamps), only from the catalog tags list. A "
          + "product matches if it has at least one of them") List<String> tags,
      @ToolParam(required = false, description = "Minimum price in whole US dollars, inclusive")
          Integer minPrice,
      @ToolParam(required = false, description = "Maximum price in whole US dollars, inclusive. "
          + "\"Under $100\" is 100") Integer maxPrice,
      @ToolParam(required = false, description = "relevance (default), price_asc (cheapest "
          + "first) or price_desc (most expensive first)") String order,
      @ToolParam(required = false, description = "Maximum number of products, from 1 to 10. "
          + "Default 5") Integer limit,
      ToolContext toolContext) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("query", query);
    args.put("tags", tags);
    args.put("minPrice", minPrice);
    args.put("maxPrice", maxPrice);
    args.put("order", order);
    args.put("limit", limit);
    return run(SEARCH_PRODUCTS, toolContext, args, ToolError.SEARCH_UNAVAILABLE, calls -> {
      String validQuery = arguments.query(query);
      List<String> validTags = arguments.tags(tags);
      arguments.prices(minPrice, maxPrice);
      String validOrder = arguments.order(order);
      int validLimit = arguments.limit(limit);
      if (validQuery == null && validTags.isEmpty() && minPrice == null && maxPrice == null) {
        throw new ToolError.Failure(ToolError.of(ToolError.MISSING_CRITERIA,
            "Give at least one search criterion: query, tags, minPrice or maxPrice"));
      }
      Criteria criteria = new Criteria(validQuery, validTags, minPrice, maxPrice, validOrder,
          validLimit);
      return validQuery == null ? searchCatalog(criteria, calls, null)
          : searchSemantic(criteria, calls);
    });
  }

  @Tool(name = GET_PRODUCT_DETAILS, description = """
      Get the current details and price of one product of The Store by its id, straight from \
      the catalog. Use it before telling the shopper the current price of a specific product; \
      its price is more recent than the one in the product context.""")
  public Map<String, Object> getProductDetails(
      @ToolParam(description = "Product id (UUID) from the product context or a previous tool "
          + "result") String productId,
      ToolContext toolContext) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("productId", productId);
    return run(GET_PRODUCT_DETAILS, toolContext, args, ToolError.CATALOG_UNAVAILABLE, calls -> {
      String id = arguments.productId(productId);
      CatalogProduct product = liveProduct(id, calls);
      return new Outcome(details(product), List.of(shown(product)), null);
    });
  }

  @Tool(name = ADD_TO_CART, description = """
      Add a product to the shopping cart of the current shopper, at its current catalog price. \
      Call it only when the shopper explicitly asks to add a product to the cart. If it is not \
      clear which product they mean, ask instead of calling it. Never say that a product was \
      added unless the result has "added".""")
  public Map<String, Object> addToCart(
      @ToolParam(description = "Product id (UUID) from the product context or a previous tool "
          + "result") String productId,
      @ToolParam(required = false, description = "Units to add, from 1 to 10. Default 1")
          Integer quantity,
      ToolContext toolContext) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("productId", productId);
    args.put("quantity", quantity);
    return run(ADD_TO_CART, toolContext, args, ToolError.CART_UNAVAILABLE, calls -> {
      String id = arguments.productId(productId);
      int units = arguments.quantity(quantity);
      TurnToolContext turn = calls.turn;
      if (turn.alreadyAdded(id)) {
        throw new ToolError.Failure(ToolError.of(ToolError.ALREADY_ADDED,
            "This product was already added to the cart in this turn; it was not added again"));
      }
      CatalogProduct product = liveProduct(id, calls);
      try {
        calls.carts++;
        carts.addItem(turn.sessionId(), id, units, product.price());
      } catch (CartsUnavailableException e) {
        log.warn("addToCart: carts no respondió al POST: {}", e.getMessage());
        throw new ToolError.Failure(ToolError.of(ToolError.CART_UNAVAILABLE,
            "The cart service is not available; the product was NOT added to the cart"));
      }
      turn.markAdded(id);
      Integer itemCount = null;
      try {
        calls.carts++;
        itemCount = carts.getCart(turn.sessionId()).itemCount();
      } catch (CartsUnavailableException e) {
        log.warn("addToCart: el producto se agregó pero no se pudo leer el carrito: {}",
            e.getMessage());
      }
      Map<String, Object> added = new LinkedHashMap<>();
      added.put("id", product.id());
      added.put("name", product.name());
      added.put("quantity", units);
      added.put("unitPrice", product.price());
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("added", added);
      Map<String, Object> event = new LinkedHashMap<>();
      event.put("itemId", product.id());
      event.put("name", product.name());
      event.put("quantity", units);
      event.put("unitPrice", product.price());
      if (itemCount != null) {
        result.put("cartItemCount", itemCount);
        event.put("cartItemCount", itemCount);
      }
      return new Outcome(result, List.of(),
          ServerSentEvent.builder(event).event("cart-updated").build());
    });
  }

  /** Búsqueda con texto: índice semántico, hidratada con el catálogo (D4). */
  private Outcome searchSemantic(Criteria criteria, Calls calls) {
    List<ProductResult> found;
    try {
      found = search.search(criteria.query(), criteria.tags(), criteria.minPrice(),
          criteria.maxPrice(), criteria.limit());
    } catch (IndexUnavailableException | VectorStoreException | EmbeddingProviderException e) {
      String cause = e instanceof EmbeddingProviderException embedding
          ? embedding.reason().type() : "index-unavailable";
      log.warn("searchProducts: búsqueda semántica no disponible ({}): {}", cause,
          e.getMessage());
      if (criteria.tags().isEmpty() && criteria.minPrice() == null
          && criteria.maxPrice() == null) {
        throw new ToolError.Failure(ToolError.of(ToolError.SEARCH_UNAVAILABLE,
            "Semantic search is not available right now (" + cause + "). Retry with tags or a "
                + "price range, or tell the shopper that search is unavailable"));
      }
      return searchCatalog(criteria, calls, "semantic-search-unavailable");
    }
    List<CatalogProduct> live = new ArrayList<>();
    for (ProductResult result : found) {
      try {
        calls.catalog++;
        catalog.getProduct(result.id()).ifPresent(live::add);
      } catch (CatalogUnavailableException e) {
        throw catalogUnavailable(e);
      }
    }
    // El rango se vuelve a aplicar sobre el precio vivo, por si el payload estaba viejo.
    List<CatalogProduct> filtered = new ArrayList<>(live.stream()
        .filter(product -> inRange(product, criteria)).toList());
    sortByPrice(filtered, criteria.order());
    return searchResult(filtered, "semantic", null);
  }

  /** Búsqueda sin texto: {@code GET /catalog/products} con tags y orden, y precio acá (D4). */
  private Outcome searchCatalog(Criteria criteria, Calls calls, String degraded) {
    String catalogOrder = "relevance".equals(criteria.order()) ? null : criteria.order();
    Map<String, CatalogProduct> products = new LinkedHashMap<>();
    try {
      for (int page = 1; page <= CATALOG_MAX_PAGES; page++) {
        calls.catalog++;
        List<CatalogProduct> items = catalog.listProducts(criteria.tags(), catalogOrder, page,
            CATALOG_PAGE_SIZE);
        items.forEach(product -> products.putIfAbsent(product.id(), product));
        if (items.size() < CATALOG_PAGE_SIZE) {
          break;
        }
      }
    } catch (CatalogUnavailableException e) {
      throw catalogUnavailable(e);
    }
    List<CatalogProduct> filtered = products.values().stream()
        .filter(product -> inRange(product, criteria))
        .limit(criteria.limit())
        .toList();
    return searchResult(filtered, "catalog", degraded);
  }

  private Outcome searchResult(List<CatalogProduct> products, String source, String degraded) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("products", products.stream().map(this::details).toList());
    result.put("source", source);
    if (degraded != null) {
      result.put("degraded", degraded);
    }
    return new Outcome(result, products.stream().map(StoreTools::shown).toList(), null);
  }

  /** {@code GET /catalog/products/{id}}, sin caché: {@code product-not-found} o {@code catalog-unavailable}. */
  private CatalogProduct liveProduct(String id, Calls calls) {
    Optional<CatalogProduct> product;
    try {
      calls.catalog++;
      product = catalog.getProduct(id);
    } catch (CatalogUnavailableException e) {
      throw catalogUnavailable(e);
    }
    return product.orElseThrow(() -> new ToolError.Failure(ToolError.of(
        ToolError.PRODUCT_NOT_FOUND, "There is no product with id " + id + " in the catalog")));
  }

  private static ToolError.Failure catalogUnavailable(CatalogUnavailableException e) {
    log.warn("Tool sin catálogo: {}", e.getMessage());
    return new ToolError.Failure(ToolError.of(ToolError.CATALOG_UNAVAILABLE,
        "The product catalog is not available right now; do not guess product data or prices"));
  }

  private Map<String, Object> details(CatalogProduct product) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("id", product.id());
    details.put("name", product.name());
    details.put("price", product.price());
    details.put("tags", product.tagNames());
    details.put("description", truncate(product.description()));
    return details;
  }

  private String truncate(String description) {
    if (description == null) {
      return "";
    }
    int max = properties.descriptionMaxChars();
    return description.length() <= max ? description : description.substring(0, max) + "…";
  }

  private static boolean inRange(CatalogProduct product, Criteria criteria) {
    return (criteria.minPrice() == null || product.price() >= criteria.minPrice())
        && (criteria.maxPrice() == null || product.price() <= criteria.maxPrice());
  }

  private static void sortByPrice(List<CatalogProduct> products, String order) {
    if ("price_asc".equals(order)) {
      products.sort(Comparator.comparingInt(CatalogProduct::price));
    } else if ("price_desc".equals(order)) {
      products.sort(Comparator.comparingInt(CatalogProduct::price).reversed());
    }
  }

  private static ShownProduct shown(CatalogProduct product) {
    return new ShownProduct(product.id(), product.name(), product.description(), product.price(),
        product.tagNames());
  }

  /**
   * Ejecuta una tool: presupuesto del turno, cuerpo, evento {@code tool} (y el
   * evento que siga, como {@code cart-updated}), productos para la memoria y
   * línea de log.
   */
  private Map<String, Object> run(String tool, ToolContext toolContext, Map<String, Object> args,
      String unexpectedErrorType, Function<Calls, Outcome> body) {
    TurnToolContext turn = TurnToolContext.from(toolContext);
    Calls calls = new Calls(turn);
    long start = System.nanoTime();
    if (!turn.tryConsumeToolCall()) {
      Map<String, Object> error = ToolError.of(ToolError.BUDGET_EXHAUSTED,
          "The tool budget of this turn is exhausted; answer with the information you have");
      turn.recordOutcome(tool, ToolError.BUDGET_EXHAUSTED);
      toolLogger.log(turn.sessionId(), tool, args, ToolError.BUDGET_EXHAUSTED, 0, calls,
          elapsed(start));
      return error;
    }
    Outcome outcome;
    try {
      outcome = body.apply(calls);
    } catch (ToolError.Failure failure) {
      outcome = new Outcome(failure.result(), List.of(), null);
    } catch (RuntimeException e) {
      log.warn("La tool {} falló de forma inesperada: {}", tool, e.toString());
      outcome = new Outcome(ToolError.of(unexpectedErrorType,
          "The tool failed unexpectedly; tell the shopper it could not be completed"),
          List.of(), null);
    }
    String error = ToolError.typeOf(outcome.result());
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("tool", tool);
    event.put("ok", error == null);
    if (error != null) {
      event.put("error", error);
    } else if (!tool.equals(ADD_TO_CART)) {
      event.put("products", outcome.products().stream()
          .map(p -> Map.of("id", p.id(), "name", p.name(), "price", p.price()))
          .toList());
    }
    turn.emit(ServerSentEvent.builder(event).event("tool").build());
    if (error == null && outcome.followUp() != null) {
      turn.emit(outcome.followUp());
    }
    turn.addShown(outcome.products());
    turn.recordOutcome(tool, error == null ? "ok" : error);
    toolLogger.log(turn.sessionId(), tool, args, error == null ? "ok" : error,
        outcome.products().size(), calls, elapsed(start));
    return outcome.result();
  }

  private static long elapsed(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }

  /** Criterios validados de {@code searchProducts}. */
  private record Criteria(String query, List<String> tags, Integer minPrice, Integer maxPrice,
      String order, int limit) {
  }

  /**
   * Resultado de una tool: el JSON para el modelo, los productos que devolvió
   * (evento {@code tool} y memoria) y un evento que sigue al {@code tool}.
   */
  private record Outcome(Map<String, Object> result, List<ShownProduct> products,
      ServerSentEvent<?> followUp) {
  }

  /** Llamadas a los servicios durante una tool, para su línea de log. */
  static final class Calls {
    final TurnToolContext turn;
    int catalog;
    int carts;

    Calls(TurnToolContext turn) {
      this.turn = turn;
    }
  }
}
