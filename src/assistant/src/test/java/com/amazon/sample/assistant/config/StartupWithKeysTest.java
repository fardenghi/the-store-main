package com.amazon.sample.assistant.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Con claves (falsas) configuradas, el log de arranque dice que lo están pero
 * nunca muestra su valor.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {
    "spring.ai.openai.api-key=" + StartupWithKeysTest.FAKE_NVIDIA_KEY,
    "spring.ai.google.genai.embedding.api-key=" + StartupWithKeysTest.FAKE_GOOGLE_KEY,
    "spring.ai.vectorstore.qdrant.port=1"
})
class StartupWithKeysTest {

  static final String FAKE_NVIDIA_KEY = "fake-nvidia-key-0123456789";
  static final String FAKE_GOOGLE_KEY = "fake-google-key-0123456789";

  @Test
  void logsConfiguredWithoutPrintingValues(CapturedOutput output) {
    assertThat(output).contains("NVIDIA_API_KEY (chat): configurada");
    assertThat(output).contains("GOOGLE_API_KEY (embeddings): configurada");
    assertThat(output).doesNotContain(FAKE_NVIDIA_KEY);
    assertThat(output).doesNotContain(FAKE_GOOGLE_KEY);
  }

  @Test
  void placeholderAndBlankAreNotConfigured() {
    assertThat(ApiKeysStartupLogger.isConfigured(ApiKeysStartupLogger.PLACEHOLDER)).isFalse();
    assertThat(ApiKeysStartupLogger.isConfigured(" ")).isFalse();
    assertThat(ApiKeysStartupLogger.isConfigured(null)).isFalse();
    assertThat(ApiKeysStartupLogger.isConfigured(FAKE_NVIDIA_KEY)).isTrue();
  }
}
