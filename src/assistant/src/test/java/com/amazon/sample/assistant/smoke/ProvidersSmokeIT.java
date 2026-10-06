package com.amazon.sample.assistant.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test contra los proveedores reales: una llamada al modelo principal,
 * una al de reescritura y un embedding (3 requests en total). Consume cuota,
 * así que solo corre con {@code ./mvnw -Psmoke verify} y con las claves
 * definidas en el entorno.
 */
@Tag("smoke")
@EnabledIfEnvironmentVariable(named = "NVIDIA_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "GOOGLE_API_KEY", matches = ".+")
@SpringBootTest(properties = {
    // Sin reintentos: un 429 no debe multiplicar las requests.
    "spring.ai.retry.max-attempts=1"
})
class ProvidersSmokeIT {

  private static final Map<String, Object> THINKING_OFF =
      Map.of("chat_template_kwargs", Map.of("enable_thinking", false));

  @Autowired
  private OpenAiChatModel chatModel;

  @Autowired
  private EmbeddingModel embeddingModel;

  @Value("${spring.ai.openai.chat.options.model}")
  private String mainModel;

  @Value("${retail.assistant.models.rewrite}")
  private String rewriteModel;

  @Test
  void mainChatModelResponds() {
    ChatResponse response = chatModel.call(new Prompt("Respondé solo con la palabra: listo",
        OpenAiChatOptions.builder().model(mainModel).maxTokens(1024).extraBody(THINKING_OFF).build()));

    System.out.printf("smoke chat: pedido=%s respondido=%s texto=%s%n", mainModel,
        response.getMetadata().getModel(), response.getResult().getOutput().getText());
    assertThat(response.getResult().getOutput().getText()).isNotBlank();
  }

  @Test
  void rewriteChatModelResponds() {
    ChatResponse response = chatModel.call(new Prompt("Respondé solo con la palabra: listo",
        OpenAiChatOptions.builder().model(rewriteModel).maxTokens(64).extraBody(THINKING_OFF).build()));

    System.out.printf("smoke chat: pedido=%s respondido=%s texto=%s%n", rewriteModel,
        response.getMetadata().getModel(), response.getResult().getOutput().getText());
    assertThat(response.getResult().getOutput().getText()).isNotBlank();
  }

  @Test
  void embeddingHas768Dimensions() {
    float[] vector = embeddingModel.embed("sillón de dos cuerpos color gris");

    assertThat(vector).hasSize(768);
  }
}
