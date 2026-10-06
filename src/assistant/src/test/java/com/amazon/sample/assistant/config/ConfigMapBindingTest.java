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
          new SystemEnvironmentPropertySource("configmap-systemEnvironment", Map.of(
              "NVIDIA_API_KEY", ApiKeysStartupLogger.PLACEHOLDER,
              "GOOGLE_API_KEY", ApiKeysStartupLogger.PLACEHOLDER,
              "SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL", "deepseek-ai/deepseek-v4.1-flash",
              "SPRING_AI_OPENAI_CHAT_OPTIONS_MAX_TOKENS", "777",
              "RETAIL_ASSISTANT_MODELS_REWRITE", "google/gemma-3-12b-it",
              "SPRING_AI_GOOGLE_GENAI_EMBEDDING_TEXT_OPTIONS_DIMENSIONS", "512",
              "SPRING_AI_VECTORSTORE_QDRANT_PORT", "1")));
    }
  }

  @Autowired
  private OpenAiChatModel chatModel;

  @Autowired
  private GoogleGenAiTextEmbeddingModel embeddingModel;

  @Value("${retail.assistant.models.rewrite}")
  private String rewriteModel;

  @Test
  void configMapVariablesOverrideDefaults() {
    OpenAiChatOptions options = (OpenAiChatOptions) chatModel.getDefaultOptions();

    assertThat(options.getModel()).isEqualTo("deepseek-ai/deepseek-v4.1-flash");
    assertThat(options.getMaxTokens()).isEqualTo(777);
    assertThat(rewriteModel).isEqualTo("google/gemma-3-12b-it");
    assertThat(embeddingModel.defaultOptions.getDimensions()).isEqualTo(512);
  }
}
