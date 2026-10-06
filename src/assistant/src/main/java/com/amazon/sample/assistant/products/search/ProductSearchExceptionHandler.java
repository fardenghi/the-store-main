package com.amazon.sample.assistant.products.search;

import com.amazon.sample.assistant.products.embedding.EmbeddingProviderException;
import com.amazon.sample.assistant.products.embedding.EmbeddingProviderException.Reason;
import com.amazon.sample.assistant.products.vector.VectorStoreException;
import java.net.URI;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errores de la búsqueda y de los similares como {@link ProblemDetail}, con un
 * {@code type} estable (D8).
 */
@RestControllerAdvice(assignableTypes = ProductSearchController.class)
public class ProductSearchExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ProductSearchExceptionHandler.class);

  /** {@code Retry-After} cuando Gemini no informa la espera: su ventana de RPM. */
  static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(60);

  @ExceptionHandler(InvalidParameterException.class)
  ResponseEntity<ProblemDetail> invalidParameter(InvalidParameterException e) {
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "invalid-parameter",
        "Parámetro inválido", e.getMessage());
    problem.setProperty("parameter", e.parameter());
    return ResponseEntity.of(problem).build();
  }

  @ExceptionHandler(ProductNotFoundException.class)
  ResponseEntity<ProblemDetail> notFound(ProductNotFoundException e) {
    return ResponseEntity.of(problem(HttpStatus.NOT_FOUND, "product-not-found",
        "Producto no encontrado", e.getMessage())).build();
  }

  @ExceptionHandler(IndexUnavailableException.class)
  ResponseEntity<ProblemDetail> indexUnavailable(IndexUnavailableException e) {
    return ResponseEntity.of(problem(HttpStatus.SERVICE_UNAVAILABLE, "index-unavailable",
        "Índice de productos no disponible", e.getMessage())).build();
  }

  /** Qdrant caído o sin responder: para el cliente, el índice no está disponible. */
  @ExceptionHandler(VectorStoreException.class)
  ResponseEntity<ProblemDetail> vectorStore(VectorStoreException e) {
    log.warn("Error de Qdrant en la búsqueda: {}", e.getMessage());
    return ResponseEntity.of(problem(HttpStatus.SERVICE_UNAVAILABLE, "index-unavailable",
        "Índice de productos no disponible", "No se pudo consultar el vector store")).build();
  }

  @ExceptionHandler(EmbeddingProviderException.class)
  ResponseEntity<ProblemDetail> embeddingProvider(EmbeddingProviderException e) {
    log.warn("Error del proveedor de embeddings en la búsqueda: {} ({})", e.reason(),
        e.getMessage());
    String detail = switch (e.reason()) {
      case QUOTA -> "Se excedió la cuota del proveedor de embeddings";
      case UNAUTHORIZED -> "El proveedor de embeddings no está configurado o rechazó la clave";
      case UNAVAILABLE -> "El proveedor de embeddings no está disponible";
    };
    ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, e.reason().type(),
        "Error del proveedor de embeddings", detail);
    ResponseEntity.HeadersBuilder<?> response = ResponseEntity.of(problem);
    if (e.reason() == Reason.QUOTA) {
      long seconds = Math.max(1,
          (long) Math.ceil(e.retryAfter().orElse(DEFAULT_RETRY_AFTER).toMillis() / 1000.0));
      response.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
    }
    return response.build();
  }

  private static ProblemDetail problem(HttpStatus status, String type, String title,
      String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(type));
    problem.setTitle(title);
    return problem;
  }
}
