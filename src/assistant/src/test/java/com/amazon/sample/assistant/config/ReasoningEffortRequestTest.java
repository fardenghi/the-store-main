package com.amazon.sample.assistant.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.assistant.chat.FakeChatProvider;
import com.amazon.sample.assistant.chat.llm.ReasoningPolicy;
import com.amazon.sample.assistant.chat.rewrite.Intent;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * Regresión del 400 "duplicate field `reasoning_effort`"
 * ({@code select-assistant-models}, segundo intento): con
 * {@code reasoning_effort} en los {@code extra-body}, el cuerpo que llega al
 * proveedor lo trae una sola vez, también en los requests con tools, y con el
 * nivel del turno ({@code low} sin razonamiento, {@code high} en las
 * comparaciones).
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE, properties = {
    "spring.ai.openai.api-key=test-key",
    "spring.ai.vectorstore.qdrant.port=1",
    "spring.application.json={"
        + "\"retail.assistant.chat.reasoning.on-extra-body\":{\"reasoning_effort\":\"high\"},"
        + "\"retail.assistant.chat.reasoning.off-extra-body\":{\"reasoning_effort\":\"low\"},"
        + "\"retail.assistant.rewrite.extra-body\":{\"reasoning_effort\":\"low\"}}"
})
class ReasoningEffortRequestTest {

  private static final Pattern REASONING_EFFORT = Pattern.compile("\"reasoning_effort\"\\s*:");

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
  void requestWithToolsSendsReasoningEffortOnceWithTheTurnLevel() {
    stream(Intent.SEARCH);
    stream(Intent.COMPARE);

    assertThat(PROVIDER.requests()).allSatisfy(request ->
        assertThat(request.path("tools").isArray()).isTrue());
    assertThat(PROVIDER.rawRequests()).allSatisfy(raw ->
        assertThat(occurrences(raw)).as(raw).isEqualTo(1));
    assertThat(PROVIDER.requests().get(0).path("reasoning_effort").asText()).isEqualTo("low");
    assertThat(PROVIDER.requests().get(0).path("max_tokens").asInt()).isEqualTo(1024);
    assertThat(PROVIDER.requests().get(1).path("reasoning_effort").asText()).isEqualTo("high");
    assertThat(PROVIDER.requests().get(1).path("max_tokens").asInt()).isEqualTo(4096);
  }

  @Test
  void requiredToolChoiceKeepsASingleReasoningEffort() {
    var options = reasoningPolicy.optionsFor(Intent.SEARCH).options();
    options.setToolChoice("required");
    mainChatClient.prompt().user("add the lamp to my cart").options(options)
        .stream().content().collectList().block();

    String raw = PROVIDER.rawRequests().get(0);
    assertThat(occurrences(raw)).as(raw).isEqualTo(1);
    assertThat(PROVIDER.requests().get(0).path("tool_choice").asText()).isEqualTo("required");
  }

  @Test
  void rewriteRequestSendsReasoningEffortOnce() {
    rewriteChatClient.prompt().user("hi").call().content();

    String raw = PROVIDER.rawRequests().get(0);
    JsonNode request = PROVIDER.requests().get(0);
    assertThat(occurrences(raw)).as(raw).isEqualTo(1);
    assertThat(request.path("reasoning_effort").asText()).isEqualTo("low");
    assertThat(request.has("chat_template_kwargs")).isFalse();
  }

  private void stream(Intent intent) {
    mainChatClient.prompt().user("hi")
        .options(reasoningPolicy.optionsFor(intent).options())
        .stream().content().collectList().block();
  }

  private static int occurrences(String raw) {
    Matcher matcher = REASONING_EFFORT.matcher(raw);
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    return count;
  }
}
