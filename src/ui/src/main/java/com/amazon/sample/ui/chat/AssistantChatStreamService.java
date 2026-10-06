package com.amazon.sample.ui.chat;

import com.amazon.sample.ui.web.util.SessionIDUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Provider {@code assistant}: reenvía el mensaje a {@code POST /assistant/chat}
 * con el {@code X-Session-ID} de la cookie y retransmite los eventos SSE sin
 * modificar su {@code data} (D3). Cualquier falla termina en un único evento
 * {@code error}, nunca en un error HTTP hacia el navegador.
 */
@Slf4j
public class AssistantChatStreamService implements ChatStreamService {

  private static final ParameterizedTypeReference<
    ServerSentEvent<String>
  > SSE_TYPE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<
    Map<String, Object>
  > PROBLEM_TYPE = new ParameterizedTypeReference<>() {};

  private static final int SESSION_LOG_LENGTH = 8;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final WebClient webClient;

  private final String chatUrl;

  private final Duration chatTimeout;

  public AssistantChatStreamService(
    WebClient webClient,
    String endpoint,
    Duration chatTimeout
  ) {
    this.webClient = webClient;
    this.chatUrl = endpoint.endsWith("/")
      ? endpoint + "assistant/chat"
      : endpoint + "/assistant/chat";
    this.chatTimeout = chatTimeout;
  }

  @Override
  public Flux<ServerSentEvent<String>> stream(
    String sessionId,
    String message
  ) {
    return Flux.defer(() -> {
      var turn = new TurnState(now());

      Flux<ServerSentEvent<String>> upstream = this.webClient.post()
        .uri(this.chatUrl)
        .header(SessionIDUtil.HEADER_NAME, sessionId)
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .bodyValue(Map.of("message", message))
        .exchangeToFlux(response -> handleResponse(response, turn));

      return upstream
        .filter(event -> event.data() != null)
        .takeUntil(ChatEvents::isFinal)
        .take(this.chatTimeout)
        .doOnNext(event -> {
          if (ChatEvents.isFinal(event)) {
            turn.finalEvent = describe(event);
          }
        })
        .onErrorResume(e -> {
          log.debug("Assistant chat stream failed: {}", e.toString());
          return Flux.empty();
        })
        .concatWith(
          Mono.defer(() -> {
            if (turn.finalEvent != null) {
              return Mono.empty();
            }
            turn.finalEvent = "error:" + ChatEvents.ASSISTANT_UNAVAILABLE;
            return Mono.just(unavailable());
          })
        )
        .doFinally(signal ->
          log.info(
            "Chat turn session={} upstream={} final={} durationMs={}",
            truncate(sessionId),
            turn.upstreamStatus,
            turn.finalEvent != null ? turn.finalEvent : "cancelled",
            now() - turn.startMillis
          )
        );
    });
  }

  private Flux<ServerSentEvent<String>> handleResponse(
    ClientResponse response,
    TurnState turn
  ) {
    var status = response.statusCode();
    turn.upstreamStatus = String.valueOf(status.value());

    if (status.is2xxSuccessful()) {
      return response.bodyToFlux(SSE_TYPE);
    }

    if (status.value() == HttpStatus.BAD_REQUEST.value()) {
      return response
        .bodyToMono(PROBLEM_TYPE)
        .map(problem -> {
          Object detail = problem.get("detail");
          return detail != null ? detail.toString() : "Invalid message";
        })
        .onErrorReturn("Invalid message")
        .defaultIfEmpty("Invalid message")
        .map(detail -> ChatEvents.error(ChatEvents.INVALID_PARAMETER, detail))
        .flux();
    }

    if (status.value() == HttpStatus.CONFLICT.value()) {
      return response
        .releaseBody()
        .thenReturn(
          ChatEvents.error(
            ChatEvents.SESSION_BUSY,
            "The previous answer for this session is still in progress"
          )
        )
        .flux();
    }

    return response.releaseBody().thenReturn(unavailable()).flux();
  }

  private static ServerSentEvent<String> unavailable() {
    return ChatEvents.error(
      ChatEvents.ASSISTANT_UNAVAILABLE,
      "The assistant is not available"
    );
  }

  private static String describe(ServerSentEvent<String> event) {
    if (ChatEvents.DONE.equals(event.event())) {
      return ChatEvents.DONE;
    }
    try {
      var type = MAPPER.readTree(event.data()).path("type").asText("");
      return type.isEmpty() ? ChatEvents.ERROR : "error:" + type;
    } catch (JsonProcessingException e) {
      return ChatEvents.ERROR;
    }
  }

  private static String truncate(String sessionId) {
    if (sessionId == null) {
      return "none";
    }
    return sessionId.length() <= SESSION_LOG_LENGTH
      ? sessionId
      : sessionId.substring(0, SESSION_LOG_LENGTH);
  }

  /** Reloj del scheduler de Reactor, para que los tests con tiempo virtual lo controlen. */
  private static long now() {
    return Schedulers.parallel().now(TimeUnit.MILLISECONDS);
  }

  private static final class TurnState {

    private final long startMillis;

    private volatile String upstreamStatus = "none";

    private volatile String finalEvent;

    private TurnState(long startMillis) {
      this.startMillis = startMillis;
    }
  }
}
