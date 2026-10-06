package com.amazon.sample.assistant.chat.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazon.sample.assistant.config.ChatProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

/** Cada campo del {@code extra-body} por un solo camino ({@link ExtraBody}). */
class ExtraBodyTest {

  @Test
  void reasoningEffortGoesToTheNativeOption() {
    OpenAiChatOptions options = OpenAiChatOptions.builder().build();

    ExtraBody.apply(options, Map.of("reasoning_effort", "low",
        "chat_template_kwargs", Map.of("enable_thinking", false)));

    assertThat(options.getReasoningEffort()).isEqualTo("low");
    assertThat(options.getExtraBody())
        .isEqualTo(Map.of("chat_template_kwargs", Map.of("enable_thinking", false)));
  }

  @Test
  void aMapWithoutReasoningEffortClearsThePreviousLevel() {
    OpenAiChatOptions options = OpenAiChatOptions.builder().reasoningEffort("high").build();

    ExtraBody.apply(options, ChatProperties.NEMOTRON_THINKING_OFF);

    assertThat(options.getReasoningEffort()).isNull();
    assertThat(options.getExtraBody()).isEqualTo(ChatProperties.NEMOTRON_THINKING_OFF);
  }

  @Test
  void otherNativeFieldsAreAConfigurationError() {
    OpenAiChatOptions options = OpenAiChatOptions.builder().build();

    assertThatThrownBy(() -> ExtraBody.apply(options, Map.of("temperature", 0.2, "top_p", 0.9)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("[temperature, top_p]");
  }
}
