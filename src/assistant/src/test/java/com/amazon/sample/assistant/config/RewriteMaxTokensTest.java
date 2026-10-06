package com.amazon.sample.assistant.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.assistant.chat.FakeChatProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code retail.assistant.rewrite.max-tokens} ({@code select-assistant-models},
 * task 1.6): con un modelo que no puede apagar el razonamiento, los tokens de
 * la reescritura lo incluyen, así que el límite se configura sin reconstruir la
 * imagen y llega al request de NVIDIA.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE, properties = {
    "spring.ai.openai.api-key=test-key",
    "spring.ai.vectorstore.qdrant.port=1",
    "retail.assistant.rewrite.max-tokens=1024"
})
class RewriteMaxTokensTest {

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
  private ChatProperties properties;

  @Autowired
  @Qualifier("rewriteChatClient")
  private ChatClient rewriteChatClient;

  @Test
  void rewriteRequestUsesTheConfiguredMaxTokens() {
    rewriteChatClient.prompt().user("hi").call().content();

    assertThat(properties.rewrite().maxTokens()).isEqualTo(1024);
    JsonNode request = PROVIDER.requests().get(0);
    assertThat(request.path("max_tokens").asInt()).isEqualTo(1024);
  }
}
