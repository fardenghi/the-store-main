package com.amazon.sample.assistant.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.assistant.chat.FakeChatProvider;
import com.amazon.sample.assistant.chat.llm.ReasoningPolicy;
import com.amazon.sample.assistant.chat.rewrite.Intent;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Lo que manda cada {@code ChatClient} a NVIDIA (D3, D7): modelo,
 * {@code temperature}, {@code max_tokens} y {@code chat_template_kwargs},
 * capturado por un proveedor falso.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE, properties = {
    "spring.ai.openai.api-key=test-key",
    "spring.ai.vectorstore.qdrant.port=1"
})
class ChatClientsRequestTest {

  static final FakeChatProvider PROVIDER;

  static {
    try {
      PROVIDER = new FakeChatProvider();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void provider(DynamicPropertyRegistry registry) {
    registry.add("spring.ai.openai.base-url", PROVIDER::baseUrl);
  }

  @AfterAll
  static void stop() {
    PROVIDER.close();
  }

  @Autowired
  @Qualifier("rewriteChatClient")
  private ChatClient rewriteChatClient;

  @Autowired
  @Qualifier("mainChatClient")
  private ChatClient mainChatClient;

  @Autowired
  private ReasoningPolicy reasoningPolicy;

  @BeforeEach
  void reset() {
    PROVIDER.reset();
  }

  @Test
  void rewriteClientUsesCompactModelWithoutThinking() {
    rewriteChatClient.prompt().user("hi").call().content();

    JsonNode request = PROVIDER.requests().get(0);
    assertThat(request.path("model").asText()).isEqualTo("nvidia/nemotron-3.5-lightning-30b-a3b");
    assertThat(request.path("temperature").asDouble()).isZero();
    assertThat(request.path("max_tokens").asInt()).isEqualTo(256);
    assertThat(request.path("chat_template_kwargs").path("enable_thinking").isBoolean()).isTrue();
    assertThat(request.path("chat_template_kwargs").path("enable_thinking").asBoolean()).isFalse();
    assertThat(request.path("stream").asBoolean(false)).isFalse();
  }

  @Test
  void mainClientWithoutReasoning() {
    stream(Intent.SEARCH);

    JsonNode request = PROVIDER.requests().get(0);
    assertThat(request.path("model").asText()).isEqualTo("nvidia/nemotron-3-super-120b-a12b");
    assertThat(request.path("temperature").asDouble()).isEqualTo(0.6);
    assertThat(request.path("max_tokens").asInt()).isEqualTo(1024);
    assertThat(request.path("chat_template_kwargs").path("enable_thinking").asBoolean()).isFalse();
    assertThat(request.path("stream").asBoolean()).isTrue();
  }

  @Test
  void mainClientWithReasoningForComparisons() {
    stream(Intent.COMPARE);

    JsonNode request = PROVIDER.requests().get(0);
    assertThat(request.path("model").asText()).isEqualTo("nvidia/nemotron-3-super-120b-a12b");
    assertThat(request.path("temperature").asDouble()).isEqualTo(0.6);
    assertThat(request.path("max_tokens").asInt()).isEqualTo(4096);
    assertThat(request.path("chat_template_kwargs").path("enable_thinking").asBoolean()).isTrue();
  }

  private void stream(Intent intent) {
    mainChatClient.prompt().user("hi")
        .options(reasoningPolicy.optionsFor(intent).options())
        .stream().content().collectList().block();
  }
}
