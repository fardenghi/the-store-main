package com.amazon.sample.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(properties = {
    "spring.ai.openai.api-key=not-configured",
    "spring.ai.google.genai.embedding.api-key=not-configured"
})
class EmbeddingModelContextTest {

  @Autowired
  private ApplicationContext context;

  @Test
  void onlyEmbeddingModelIsGoogleGenAi() {
    Map<String, EmbeddingModel> models = context.getBeansOfType(EmbeddingModel.class);

    assertThat(models).hasSize(1);
    assertThat(models.values().iterator().next()).isInstanceOf(GoogleGenAiTextEmbeddingModel.class);
  }

  @Test
  void embeddingDefaultsAreGemini768ForIndexing() {
    GoogleGenAiTextEmbeddingModel model = context.getBean(GoogleGenAiTextEmbeddingModel.class);

    assertThat(model.defaultOptions.getModel()).isEqualTo("gemini-embedding-001");
    assertThat(model.defaultOptions.getDimensions()).isEqualTo(768);
    assertThat(model.defaultOptions.getTaskType())
        .isEqualTo(GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_DOCUMENT);
  }
}
