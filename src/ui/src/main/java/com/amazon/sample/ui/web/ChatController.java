/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: MIT-0
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.amazon.sample.ui.web;

import com.amazon.sample.ui.chat.ChatEvents;
import com.amazon.sample.ui.chat.ChatStreamService;
import com.amazon.sample.ui.config.AssistantProperties;
import com.amazon.sample.ui.web.util.SessionIDUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/chat")
@ConditionalOnProperty(prefix = "retail.ui.chat", name = "enabled")
public class ChatController {

  static class ChatRequest {

    @JsonProperty("message")
    private String message;

    public String getMessage() {
      return message;
    }

    @SuppressWarnings("unused")
    public void setMessage(String message) {
      this.message = message;
    }
  }

  private final ChatStreamService chatStreamService;

  private final Duration keepaliveInterval;

  public ChatController(
    ChatStreamService chatStreamService,
    AssistantProperties properties
  ) {
    this.chatStreamService = chatStreamService;
    this.keepaliveInterval = properties.getKeepaliveInterval();
  }

  @PostMapping(value = "/submit", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<String>> streamEvents(
    @RequestBody ChatRequest body,
    ServerHttpRequest request,
    ServerHttpResponse response
  ) {
    response.getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-cache");
    response.getHeaders().set("X-Accel-Buffering", "no");

    // La sesión sale siempre de la cookie: SessionIDWebFilter pisa cualquier
    // X-Session-ID que mande el navegador.
    String sessionId = SessionIDUtil.getSessionId(request);

    return withKeepalive(
      this.chatStreamService.stream(sessionId, body.getMessage()),
      this.keepaliveInterval
    );
  }

  /**
   * Intercala un comentario SSE cada {@code interval} para que ningún proxy
   * corte la conexión mientras el assistant razona (D5). El intervalo termina
   * junto con el stream principal.
   */
  static Flux<ServerSentEvent<String>> withKeepalive(
    Flux<ServerSentEvent<String>> events,
    Duration interval
  ) {
    return withKeepalive(events, Flux.interval(interval, interval));
  }

  static Flux<ServerSentEvent<String>> withKeepalive(
    Flux<ServerSentEvent<String>> events,
    Flux<Long> ticks
  ) {
    return events.publish(shared ->
      Flux.merge(
        shared,
        ticks
          .map(tick -> ChatEvents.keepalive())
          .takeUntilOther(shared.then(Mono.just(true)))
      )
    );
  }
}
