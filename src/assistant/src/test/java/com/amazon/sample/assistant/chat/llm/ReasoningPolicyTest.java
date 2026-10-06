package com.amazon.sample.assistant.chat.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.assistant.chat.llm.ReasoningPolicy.TurnOptions;
import com.amazon.sample.assistant.chat.rewrite.Intent;
import com.amazon.sample.assistant.config.ChatProperties;
import com.amazon.sample.assistant.config.ChatProperties.ReasoningMode;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.openai.OpenAiChatOptions;

/** Razonamiento por turno según el modo y el intent (D7). */
class ReasoningPolicyTest {

  private static final OpenAiChatOptions BASE = OpenAiChatOptions.builder()
      .model("meta/muse-glimmer-30b").temperature(0.6).maxTokens(1024).build();

  static ChatProperties.Chat chat(ReasoningMode mode) {
    return new ChatProperties.Chat(5, 0, 1024, 4096, true,
        new ChatProperties.Reasoning(mode, null, null, false),
        new ChatProperties.Memory(10, Duration.ofMinutes(30), 10_000),
        new ChatProperties.Timeouts(Duration.ofSeconds(60), Duration.ofSeconds(20),
            Duration.ofSeconds(120), Duration.ofSeconds(10)));
  }

  @ParameterizedTest(name = "{0} + {1} -> razonamiento {2}")
  @CsvSource({
      "AUTO,   SEARCH,  false",
      "AUTO,   COMPARE, true",
      "AUTO,   OTHER,   false",
      "ALWAYS, SEARCH,  true",
      "ALWAYS, COMPARE, true",
      "ALWAYS, OTHER,   true",
      "NEVER,  SEARCH,  false",
      "NEVER,  COMPARE, false",
      "NEVER,  OTHER,   false"
  })
  void optionsForEachModeAndIntent(ReasoningMode mode, Intent intent, boolean expected) {
    TurnOptions options = new ReasoningPolicy(BASE, chat(mode)).optionsFor(intent);

    assertThat(options.reasoning()).isEqualTo(expected);
    assertThat(options.options().getModel()).isEqualTo("meta/muse-glimmer-30b");
    assertThat(options.options().getTemperature()).isEqualTo(0.6);
    assertThat(options.options().getMaxTokens()).isEqualTo(expected ? 4096 : 1024);
    // Defaults de muse-glimmer-30b: reasoning_effort va por la opción nativa (ExtraBody).
    assertThat(options.options().getReasoningEffort()).isEqualTo(expected ? "high" : "low");
    assertThat(options.options().getExtraBody()).isEmpty();
    // Las opciones base no se modifican.
    assertThat(BASE.getMaxTokens()).isEqualTo(1024);
    assertThat(BASE.getExtraBody()).isNull();
  }
}
