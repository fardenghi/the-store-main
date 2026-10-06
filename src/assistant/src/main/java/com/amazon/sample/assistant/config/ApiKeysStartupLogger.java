package com.amazon.sample.assistant.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Informa al arrancar si cada clave de proveedor está configurada, sin mostrar
 * nunca su valor. Con el placeholder el servicio arranca igual, pero las
 * llamadas al proveedor van a fallar con 401/403 (D3).
 */
@Component
public class ApiKeysStartupLogger {

  public static final String PLACEHOLDER = "not-configured";

  private static final Logger log = LoggerFactory.getLogger(ApiKeysStartupLogger.class);

  private final Environment environment;

  public ApiKeysStartupLogger(Environment environment) {
    this.environment = environment;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void logApiKeysStatus() {
    report("NVIDIA_API_KEY", "chat", "spring.ai.openai.api-key");
    report("GOOGLE_API_KEY", "embeddings", "spring.ai.google.genai.embedding.api-key");
  }

  private void report(String name, String usage, String property) {
    if (isConfigured(environment.getProperty(property))) {
      log.info("{} ({}): configurada", name, usage);
    } else {
      log.warn("{} ({}): NO configurada (placeholder); las llamadas al proveedor van a fallar",
          name, usage);
    }
  }

  static boolean isConfigured(String value) {
    return StringUtils.hasText(value) && !PLACEHOLDER.equals(value.trim());
  }
}
