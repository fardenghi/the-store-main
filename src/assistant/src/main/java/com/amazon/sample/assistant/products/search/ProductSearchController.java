package com.amazon.sample.assistant.products.search;

import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Búsqueda semántica y similares por HTTP (D8). Solo se exponen dentro del
 * cluster: el ingress enruta únicamente hacia la {@code ui}.
 */
@RestController
@RequestMapping("/assistant/products")
public class ProductSearchController {

  private final ProductSearchService searchService;
  private final SimilarProductsService similarService;

  public ProductSearchController(ProductSearchService searchService,
      SimilarProductsService similarService) {
    this.searchService = searchService;
    this.similarService = similarService;
  }

  @GetMapping("/search")
  public List<ProductResult> search(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String tags,
      @RequestParam(required = false) String minPrice,
      @RequestParam(required = false) String maxPrice,
      @RequestParam(required = false) String k) {
    return searchService.search(q, parseTags(tags), parseInt("minPrice", minPrice),
        parseInt("maxPrice", maxPrice), parseInt("k", k));
  }

  @GetMapping("/{id}/similar")
  public List<ProductResult> similar(
      @PathVariable String id,
      @RequestParam(required = false) String k) {
    return similarService.similar(id, parseInt("k", k));
  }

  static List<String> parseTags(String tags) {
    if (tags == null || tags.isBlank()) {
      return List.of();
    }
    return Arrays.stream(tags.split(","))
        .map(String::trim)
        .filter(tag -> !tag.isEmpty())
        .distinct()
        .toList();
  }

  static Integer parseInt(String name, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Integer.valueOf(value.trim());
    } catch (NumberFormatException e) {
      throw new InvalidParameterException(name, name + " tiene que ser un número entero");
    }
  }
}
