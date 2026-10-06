package com.amazon.sample.ui.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * Providers {@code mock}, {@code openai} y {@code bedrock}: le manda al modelo
 * solo el mensaje del usuario, sin system prompt (la persona vive en el
 * assistant), y termina el stream con {@code done}.
 */
@Slf4j
public class SpringAiChatStreamService implements ChatStreamService {

  private final ChatClient client;

  public SpringAiChatStreamService(ChatClient client) {
    this.client = client;
  }

  @Override
  public Flux<ServerSentEvent<String>> stream(
    String sessionId,
    String message
  ) {
    return this.client.prompt()
      .user(message)
      .stream()
      .content()
      .map(ChatEvents::text)
      .concatWith(Flux.just(ChatEvents.done()))
      .onErrorResume(e -> {
        log.warn("Chat model error: {}", e.getMessage());
        return Flux.just(
          ChatEvents.error(
            ChatEvents.ASSISTANT_UNAVAILABLE,
            "The chat model is not available"
          )
        );
      });
  }
}
