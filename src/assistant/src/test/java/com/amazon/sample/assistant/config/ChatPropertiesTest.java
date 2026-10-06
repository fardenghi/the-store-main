package com.amazon.sample.assistant.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.assistant.config.ChatProperties.ReasoningMode;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Defaults de {@code retail.assistant.rewrite} y {@code retail.assistant.chat} (D11). */
class ChatPropertiesTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ChatProperties.class)
  static class Config {
  }

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
      .withUserConfiguration(Config.class);

  @Test
  void loadsDefaultsFromApplicationYml() {
    runner.run(context -> {
      ChatProperties properties = context.getBean(ChatProperties.class);

      assertThat(properties.rewrite().timeout()).isEqualTo(Duration.ofSeconds(5));
      assertThat(properties.rewrite().historyTurns()).isEqualTo(3);
      ChatProperties.Chat chat = properties.chat();
      assertThat(chat.retrievalK()).isEqualTo(5);
      assertThat(chat.minScore()).isZero();
      assertThat(chat.maxTokens()).isEqualTo(1024);
      assertThat(chat.maxTokensReasoning()).isEqualTo(4096);
      assertThat(chat.compareRawRetrieval()).isTrue();
      assertThat(chat.reasoning().mode()).isEqualTo(ReasoningMode.AUTO);
      assertThat(chat.reasoning().stripThinkTags()).isFalse();
      assertThat(chat.memory().maxTurns()).isEqualTo(10);
      assertThat(chat.memory().idleTtl()).isEqualTo(Duration.ofMinutes(30));
      assertThat(chat.memory().maxSessions()).isEqualTo(10_000);
      assertThat(chat.timeouts().firstTokenReasoning()).isEqualTo(Duration.ofSeconds(60));
      assertThat(chat.timeouts().firstToken()).isEqualTo(Duration.ofSeconds(20));
      assertThat(chat.timeouts().turn()).isEqualTo(Duration.ofSeconds(120));
      assertThat(chat.timeouts().keepalive()).isEqualTo(Duration.ofSeconds(10));
    });
  }

  @Test
  void extraBodiesAreNestedMapsWithJsonTypes() {
    runner.run(context -> {
      ChatProperties properties = context.getBean(ChatProperties.class);

      // La clave conserva el guion bajo y el valor es booleano, no "false".
      assertThat(properties.rewrite().extraBody())
          .isEqualTo(Map.of("chat_template_kwargs", Map.of("enable_thinking", false)));
      assertThat(properties.chat().reasoning().onExtraBody())
          .isEqualTo(Map.of("chat_template_kwargs", Map.of("enable_thinking", true)));
      assertThat(properties.chat().reasoning().offExtraBody())
          .isEqualTo(Map.of("chat_template_kwargs", Map.of("enable_thinking", false)));
    });
  }

  @Test
  void textValuesFromEnvironmentBecomeJsonTypes() {
    runner.withPropertyValues(
            "retail.assistant.chat.reasoning.on-extra-body[chat_template_kwargs].thinking=true",
            "retail.assistant.chat.reasoning.on-extra-body[reasoning_budget]=512")
        .run(context -> {
          Map<String, Object> on = context.getBean(ChatProperties.class).chat().reasoning()
              .onExtraBody();

          assertThat(on.get("reasoning_budget")).isEqualTo(512L);
          assertThat(on.get("chat_template_kwargs")).asInstanceOf(
                  org.assertj.core.api.InstanceOfAssertFactories.MAP)
              .containsEntry("thinking", true);
        });
  }

  @Test
  void invalidReasoningModeFailsStartup() {
    runner.withPropertyValues("retail.assistant.chat.reasoning.mode=sometimes")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void invalidRetrievalKFailsStartup() {
    runner.withPropertyValues("retail.assistant.chat.retrieval-k=0")
        .run(context -> assertThat(context).hasFailed());
  }
}
