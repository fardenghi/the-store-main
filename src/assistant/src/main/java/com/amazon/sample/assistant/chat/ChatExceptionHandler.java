package com.amazon.sample.assistant.chat;

import com.amazon.sample.assistant.products.search.InvalidParameterException;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errores del chat antes de abrir el stream, como {@link ProblemDetail} con un
 * {@code type} estable (D1). El {@code Content-Type} va explícito porque el
 * endpoint produce {@code text/event-stream}. Tiene precedencia sobre el
 * handler genérico de ProblemDetail de Spring, para que un cuerpo ilegible
 * también responda {@code invalid-parameter}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ChatController.class)
public class ChatExceptionHandler {

  @ExceptionHandler(InvalidParameterException.class)
  ResponseEntity<ProblemDetail> invalidParameter(InvalidParameterException e) {
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "invalid-parameter",
        "Parámetro inválido", e.getMessage());
    problem.setProperty("parameter", e.parameter());
    return response(problem);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemDetail> unreadableBody(HttpMessageNotReadableException e) {
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "invalid-parameter",
        "Parámetro inválido", "El cuerpo tiene que ser un JSON {\"message\": \"...\"}");
    problem.setProperty("parameter", "message");
    return response(problem);
  }

  @ExceptionHandler(SessionBusyException.class)
  ResponseEntity<ProblemDetail> sessionBusy(SessionBusyException e) {
    return response(problem(HttpStatus.CONFLICT, "session-busy", "Sesión ocupada",
        e.getMessage()));
  }

  private static ResponseEntity<ProblemDetail> response(ProblemDetail problem) {
    return ResponseEntity.status(problem.getStatus())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }

  private static ProblemDetail problem(HttpStatus status, String type, String title,
      String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(type));
    problem.setTitle(title);
    return problem;
  }
}
