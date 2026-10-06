package com.amazon.sample.assistant.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.assistant.chat.llm.ReasoningPolicy;
import com.amazon.sample.assistant.chat.rewrite.Intent;
import com.amazon.sample.assistant.config.ChatProperties.ReasoningMode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.context.ContextConfiguration;

/**
 * Plan B por ConfigMap (D11): {@code SPRING_APPLICATION_JSON} reemplaza por
 * completo los {@code extra-body} (no se combinan con los defaults de
 * Nemotron), y las variables escalares del ConfigMap cambian el resto.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE, properties = {
    "spring.application.json={"
        + "\"retail.assistant.chat.reasoning.on-extra-body\":"
        + "{\"chat_template_kwargs\":{\"thinking\":true}},"
        + "\"retail.assistant.chat.reasoning.off-extra-body\":"
        + "{\"chat_template_kwargs\":{\"thinking\":false}},"
        + "\"retail.assistant.rewrite.extra-body\":{}}",
    "spring.ai.vectorstore.qdrant.port=1"
})
@ContextConfiguration(initializers = PlanBExtraBodyTest.ConfigMapEnvironment.class)
class PlanBExtraBodyTest {

  /** Variables escalares del ConfigMap, con el binding relajado de las variables de entorno. */
  static class ConfigMapEnvironment
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
      context.getEnvironment().getPropertySources().addFirst(
          new SystemEnvironmentPropertySource("configmap-systemEnvironment", Map.of(
              "RETAIL_ASSISTANT_CHAT_REASONING_MODE", "always",
              "RETAIL_ASSISTANT_CHAT_RETRIEVAL_K", "7",
              "RETAIL_ASSISTANT_CHAT_MEMORY_IDLE_TTL", "5m")));
    }
  }

  @Autowired
  private ChatProperties properties;

  @Autowired
  private ReasoningPolicy reasoningPolicy;

  @Test
  void springApplicationJsonReplacesExtraBodies() {
    assertThat(properties.chat().reasoning().onExtraBody())
        .isEqualTo(Map.of("chat_template_kwargs", Map.of("thinking", true)));
    assertThat(properties.chat().reasoning().offExtraBody())
        .isEqualTo(Map.of("chat_template_kwargs", Map.of("thinking", false)));
    assertThat(properties.rewrite().extraBody()).isEmpty();
  }

  @Test
  void scalarVariablesOverrideDefaults() {
    assertThat(properties.chat().reasoning().mode()).isEqualTo(ReasoningMode.ALWAYS);
    assertThat(properties.chat().retrievalK()).isEqualTo(7);
    assertThat(properties.chat().memory().idleTtl()).hasMinutes(5);
  }

  @Test
  void turnOptionsUseTheReplacedExtraBody() {
    var options = reasoningPolicy.optionsFor(Intent.SEARCH);

    assertThat(options.reasoning()).isTrue();
    assertThat(options.options().getExtraBody())
        .isEqualTo(Map.of("chat_template_kwargs", Map.of("thinking", true)));
  }
}
