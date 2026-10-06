package com.amazon.sample.assistant.chat;

import com.amazon.sample.assistant.products.search.InvalidParameterException;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Chat con el asistente en streaming SSE (D1). La sesión se identifica con
 * {@code X-Session-ID}, el mismo id que la {@code ui} usa como
 * {@code customerId} del carrito.
 *
 * <p>Los errores de validación ({@code 400 invalid-parameter}) y la sesión
 * ocupada ({@code 409 session-busy}) se responden como {@code ProblemDetail}
 * antes de abrir el stream, sin llamar a ningún proveedor.
 */
@RestController
public class ChatController {

  static final String SESSION_HEADER = "X-Session-ID";
  static final int MAX_MESSAGE_LENGTH = 2000;

  private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

  private final ChatTurnService turns;

  public ChatController(ChatTurnService turns) {
    this.turns = turns;
  }

  @PostMapping(path = "/assistant/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<?>> chat(
      @RequestHeader(name = SESSION_HEADER, required = false) String sessionId,
      @RequestBody(required = false) ChatRequest request) {
    if (sessionId == null || sessionId.isEmpty()) {
      throw new InvalidParameterException(SESSION_HEADER, "El header X-Session-ID es obligatorio");
    }
    if (!SESSION_ID.matcher(sessionId).matches()) {
      throw new InvalidParameterException(SESSION_HEADER,
          "X-Session-ID tiene que tener hasta 128 caracteres entre letras, dígitos, - y _");
    }
    String message = request == null ? null : request.message();
    if (message == null || message.isBlank()) {
      throw new InvalidParameterException("message", "El campo message es obligatorio");
    }
    if (message.length() > MAX_MESSAGE_LENGTH) {
      throw new InvalidParameterException("message",
          "message no puede superar los " + MAX_MESSAGE_LENGTH + " caracteres");
    }
    return turns.open(sessionId, message);
  }
}
