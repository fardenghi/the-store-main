package com.amazon.sample.assistant.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazon.sample.assistant.AssistantApplication;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * El tiempo límite de la reescritura es el que corta, no el
 * {@code spring.http.client.read-timeout} de los {@code RestClient}
 * ({@code select-assistant-models}, segundo intento: con 10 s contra 12 s, la
 * reescritura caía al fallback a los 10 s como {@code llm-provider-unavailable}).
 */
class RewriteTimeoutTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ChatProperties.class)
  static class Config {
  }

  @Test
  void defaultReadTimeoutIsLongerThanTheRewriteTimeout() {
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Config.class)
        .run(context -> {
          Duration readTimeout = Binder.get(context.getEnvironment())
              .bind("spring.http.client.read-timeout", Duration.class).get();
          Duration rewriteTimeout = context.getBean(ChatProperties.class).rewrite().timeout();
          assertThat(rewriteTimeout).isEqualTo(Duration.ofSeconds(12));
          assertThat(readTimeout).isEqualTo(Duration.ofSeconds(30)).isGreaterThan(rewriteTimeout);
        });
  }

  @Test
  void aReadTimeoutThatCutsTheRewriteFirstIsRejected() {
    assertThatThrownBy(() -> ChatConfiguration.checkRewriteTimeout(Duration.ofSeconds(10),
        Duration.ofSeconds(12)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.http.client.read-timeout (PT10S)")
        .hasMessageContaining("retail.assistant.rewrite.timeout (PT12S)");
    assertThatThrownBy(() -> ChatConfiguration.checkRewriteTimeout(Duration.ofSeconds(12),
        Duration.ofSeconds(12))).isInstanceOf(IllegalStateException.class);
    assertThatCode(() -> ChatConfiguration.checkRewriteTimeout(Duration.ofSeconds(30),
        Duration.ofSeconds(12))).doesNotThrowAnyException();
    assertThatCode(() -> ChatConfiguration.checkRewriteTimeout(null, Duration.ofSeconds(12)))
        .doesNotThrowAnyException();
  }

  @Test
  void theAssistantDoesNotStartWithAReadTimeoutShorterThanTheRewrite() {
    // Como argumentos: las properties del builder tienen menos prioridad que el YAML.
    SpringApplicationBuilder app = new SpringApplicationBuilder(AssistantApplication.class)
        .web(WebApplicationType.NONE);

    assertThatThrownBy(() -> app.run("--spring.ai.vectorstore.qdrant.port=1",
        "--retail.assistant.indexing.sync-on-startup=false",
        "--spring.http.client.read-timeout=10s",
        "--retail.assistant.rewrite.timeout=12s")).rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tiene que ser mayor que retail.assistant.rewrite.timeout");
  }
}
