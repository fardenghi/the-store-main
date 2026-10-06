package com.amazon.sample.assistant.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.context.ContextConfiguration;

/**
 * Las variables de entorno del ConfigMap {@code assistant} (binding relajado de
 * Spring) cambian modelos, max-tokens y dimensiones sin reconstruir la imagen.
 * Simula el cambio al plan B.
 */
@SpringBootTest
@ContextConfiguration(initializers = ConfigMapBindingTest.ConfigMapEnvironment.class)
class ConfigMapBindingTest {

  static class ConfigMapEnvironment
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
      context.getEnvironment().getPropertySources().addFirst(
          new SystemEnvironmentPropertySource("configmap-systemEnvironment", Map.ofEntries(
              Map.entry("NVIDIA_API_KEY", ApiKeysStartupLogger.PLACEHOLDER),
              Map.entry("GOOGLE_API_KEY", ApiKeysStartupLogger.PLACEHOLDER),
              Map.entry("SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL", "nvidia/nemotron-3-super-120b-a12b"),
              Map.entry("SPRING_AI_OPENAI_CHAT_OPTIONS_MAX_TOKENS", "777"),
              Map.entry("RETAIL_ASSISTANT_MODELS_REWRITE", "nvidia/nemotron-3.5-lightning-30b-a3b"),
              Map.entry("SPRING_AI_GOOGLE_GENAI_EMBEDDING_TEXT_OPTIONS_DIMENSIONS", "512"),
              Map.entry("SPRING_AI_VECTORSTORE_QDRANT_PORT", "1"),
              Map.entry("RETAIL_ASSISTANT_TOOLS_MAX_MODEL_CALLS", "3"),
              Map.entry("RETAIL_ASSISTANT_TOOLS_MAX_QUANTITY", "5"),
              Map.entry("RETAIL_ASSISTANT_TOOLS_HTTP_READ_TIMEOUT", "7s"),
              Map.entry("RETAIL_ASSISTANT_RATE_LIMIT_REQUESTS_PER_MINUTE", "18"),
              Map.entry("RETAIL_ASSISTANT_RATE_LIMIT_MAX_WAIT", "15s"),
              Map.entry("RETAIL_ASSISTANT_RATE_LIMIT_MAX_429_RETRIES", "1"))));
    }
  }

  @Autowired
  private OpenAiChatModel chatModel;

  @Autowired
  private GoogleGenAiTextEmbeddingModel embeddingModel;

  @Value("${retail.assistant.models.rewrite}")
  private String rewriteModel;

  @Autowired
  private ToolsProperties tools;

  @Autowired
  private RateLimitProperties rateLimit;

  @Test
  void configMapVariablesOverrideDefaults() {
    OpenAiChatOptions options = (OpenAiChatOptions) chatModel.getDefaultOptions();

    assertThat(options.getModel()).isEqualTo("nvidia/nemotron-3-super-120b-a12b");
    assertThat(options.getMaxTokens()).isEqualTo(777);
    assertThat(rewriteModel).isEqualTo("nvidia/nemotron-3.5-lightning-30b-a3b");
    assertThat(embeddingModel.defaultOptions.getDimensions()).isEqualTo(512);
  }

  @Test
  void toolsAndRateLimitVariablesOverrideDefaults() {
    assertThat(tools.maxModelCalls()).isEqualTo(3);
    assertThat(tools.maxQuantity()).isEqualTo(5);
    assertThat(tools.http().readTimeout()).isEqualTo(java.time.Duration.ofSeconds(7));
    assertThat(rateLimit.requestsPerMinute()).isEqualTo(18);
    assertThat(rateLimit.maxWait()).isEqualTo(java.time.Duration.ofSeconds(15));
    assertThat(rateLimit.max429Retries()).isEqualTo(1);
  }
}
