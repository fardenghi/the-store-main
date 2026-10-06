package com.amazon.sample.assistant.products.search;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.amazon.sample.assistant.products.embedding.EmbeddingProviderException;
import com.amazon.sample.assistant.products.embedding.EmbeddingProviderException.Reason;
import com.amazon.sample.assistant.products.vector.VectorStoreException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductSearchController.class)
class ProductSearchControllerTest {

  private static final String ID = "3600929b-2826-5a98-908f-82a1d50bcf2b";

  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private ProductSearchService searchService;

  @MockitoBean
  private SimilarProductsService similarService;

  private static ProductResult sofa() {
    return new ProductResult(ID, "Eva Tufted Velvet Sofa", "Mid-century sofa", 1769,
        List.of("seating", "velvet"), 0.83f);
  }

  @Test
  void searchReturnsJsonArray() throws Exception {
    when(searchService.search(eq("velvet sofa"), eq(List.of("velvet", "leather")), eq(100),
        eq(2000), eq(3))).thenReturn(List.of(sofa()));

    mvc.perform(get("/assistant/products/search")
            .param("q", "velvet sofa").param("tags", "velvet, leather,")
            .param("minPrice", "100").param("maxPrice", "2000").param("k", "3"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$[0].id").value(ID))
        .andExpect(jsonPath("$[0].name").value("Eva Tufted Velvet Sofa"))
        .andExpect(jsonPath("$[0].description").value("Mid-century sofa"))
        .andExpect(jsonPath("$[0].price").value(1769))
        .andExpect(jsonPath("$[0].tags[1]").value("velvet"))
        .andExpect(jsonPath("$[0].score").isNumber());
  }

  @Test
  void searchWithoutOptionalParameters() throws Exception {
    when(searchService.search(eq("lamp"), eq(List.of()), isNull(), isNull(), isNull()))
        .thenReturn(List.of());

    mvc.perform(get("/assistant/products/search").param("q", "lamp"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  @Test
  void invalidParameterIs400ProblemDetail() throws Exception {
    when(searchService.search(any(), anyList(), any(), any(), any()))
        .thenThrow(new InvalidParameterException("minPrice", "minPrice no puede superar a maxPrice"));

    mvc.perform(get("/assistant/products/search")
            .param("q", "table").param("minPrice", "500").param("maxPrice", "100"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("invalid-parameter"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.parameter").value("minPrice"))
        .andExpect(jsonPath("$.detail").value(containsString("maxPrice")));
  }

  @Test
  void nonNumericParameterIs400() throws Exception {
    mvc.perform(get("/assistant/products/search").param("q", "lamp").param("maxPrice", "cien"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("invalid-parameter"))
        .andExpect(jsonPath("$.parameter").value("maxPrice"));
  }

  @Test
  void indexUnavailableIs503() throws Exception {
    when(searchService.search(any(), anyList(), any(), any(), any()))
        .thenThrow(new IndexUnavailableException());

    mvc.perform(get("/assistant/products/search").param("q", "lamp"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.type").value("index-unavailable"));
  }

  @Test
  void qdrantDownIs503IndexUnavailable() throws Exception {
    when(searchService.search(any(), anyList(), any(), any(), any()))
        .thenThrow(new VectorStoreException("Error de Qdrant", null));

    mvc.perform(get("/assistant/products/search").param("q", "lamp"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.type").value("index-unavailable"));
  }

  @Test
  void quotaIs503WithRetryAfter() throws Exception {
    when(searchService.search(any(), anyList(), any(), any(), any()))
        .thenThrow(new EmbeddingProviderException(Reason.QUOTA, Duration.ofMillis(12300), "429",
            null));

    mvc.perform(get("/assistant/products/search").param("q", "lamp"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "13"))
        .andExpect(jsonPath("$.type").value("embedding-quota-exceeded"));
  }

  @Test
  void quotaWithoutDelayUsesDefaultRetryAfter() throws Exception {
    when(searchService.search(any(), anyList(), any(), any(), any()))
        .thenThrow(new EmbeddingProviderException(Reason.QUOTA, null, "429", null));

    mvc.perform(get("/assistant/products/search").param("q", "lamp"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "60"));
  }

  @Test
  void unauthorizedIs503() throws Exception {
    when(searchService.search(any(), anyList(), any(), any(), any()))
        .thenThrow(new EmbeddingProviderException(Reason.UNAUTHORIZED, null, "400", null));

    mvc.perform(get("/assistant/products/search").param("q", "lamp"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().doesNotExist("Retry-After"))
        .andExpect(jsonPath("$.type").value("embedding-provider-unauthorized"))
        .andExpect(jsonPath("$.detail").value(containsString("no está configurado")));
  }

  @Test
  void unavailableIs503() throws Exception {
    when(searchService.search(any(), anyList(), any(), any(), any()))
        .thenThrow(new EmbeddingProviderException(Reason.UNAVAILABLE, null, "503", null));

    mvc.perform(get("/assistant/products/search").param("q", "lamp"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.type").value("embedding-provider-unavailable"));
  }

  @Test
  void similarReturnsJsonArray() throws Exception {
    when(similarService.similar(ID, 4)).thenReturn(List.of(sofa()));

    mvc.perform(get("/assistant/products/{id}/similar", ID).param("k", "4"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(ID))
        .andExpect(jsonPath("$[0].score").isNumber());
    verify(similarService).similar(ID, 4);
  }

  @Test
  void similarOfUnknownProductIs404() throws Exception {
    when(similarService.similar(eq("00000000-0000-0000-0000-000000000000"), isNull()))
        .thenThrow(new ProductNotFoundException("00000000-0000-0000-0000-000000000000"));

    mvc.perform(get("/assistant/products/00000000-0000-0000-0000-000000000000/similar"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("product-not-found"));
  }

  @Test
  void similarWithKOutOfRangeIs400() throws Exception {
    when(similarService.similar(ID, 13))
        .thenThrow(new InvalidParameterException("k", "k tiene que estar entre 1 y 12"));

    mvc.perform(get("/assistant/products/{id}/similar", ID).param("k", "13"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("invalid-parameter"));
  }
}
