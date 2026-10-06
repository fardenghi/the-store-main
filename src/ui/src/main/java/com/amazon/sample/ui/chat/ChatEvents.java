package com.amazon.sample.ui.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.codec.ServerSentEvent;

/** Construcción de los eventos SSE que la ui genera por su cuenta. */
public final class ChatEvents {

  public static final String DONE = "done";

  public static final String ERROR = "error";

  public static final String ASSISTANT_UNAVAILABLE = "assistant-unavailable";

  public static final String SESSION_BUSY = "session-busy";

  public static final String INVALID_PARAMETER = "invalid-parameter";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ChatEvents() {}

  public static ServerSentEvent<String> text(String text) {
    return ServerSentEvent.<String>builder()
      .data(toJson(Map.of("text", text)))
      .build();
  }

  public static ServerSentEvent<String> done() {
    return ServerSentEvent.<String>builder().event(DONE).data("{}").build();
  }

  public static ServerSentEvent<String> error(String type, String detail) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("type", type);
    data.put("detail", detail);
    return ServerSentEvent.<String>builder()
      .event(ERROR)
      .data(toJson(data))
      .build();
  }

  public static ServerSentEvent<String> keepalive() {
    return ServerSentEvent.<String>builder().comment("keepalive").build();
  }

  public static boolean isFinal(ServerSentEvent<String> event) {
    return DONE.equals(event.event()) || ERROR.equals(event.event());
  }

  private static String toJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize chat event", e);
    }
  }
}
