package com.amazon.sample.ui.chat;

import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * Fuente de los eventos SSE de un turno de chat. Todas las implementaciones
 * respetan el mismo contrato con el navegador: fragmentos sin nombre con
 * {@code {"text"}}, eventos con nombre opcionales y un único evento final
 * {@code done} o {@code error}.
 */
public interface ChatStreamService {
  Flux<ServerSentEvent<String>> stream(String sessionId, String message);
}
