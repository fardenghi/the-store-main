package com.amazon.sample.assistant.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Defaults y validación de {@code retail.assistant.tools} y {@code retail.assistant.rate-limit} (D11). */
class ToolsPropertiesTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({ToolsProperties.class, RateLimitProperties.class})
  static class Config {
  }

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
      .withUserConfiguration(Config.class);

  @Test
  void loadsDefaultsFromApplicationYml() {
    runner.run(context -> {
      ToolsProperties tools = context.getBean(ToolsProperties.class);
      assertThat(tools.maxModelCalls()).isEqualTo(4);
      assertThat(tools.maxToolCalls()).isEqualTo(6);
      assertThat(tools.maxQuantity()).isEqualTo(10);
      assertThat(tools.searchDefaultLimit()).isEqualTo(5);
      assertThat(tools.searchMaxLimit()).isEqualTo(10);
      assertThat(tools.descriptionMaxChars()).isEqualTo(300);
      assertThat(tools.http().connectTimeout()).isEqualTo(Duration.ofSeconds(2));
      assertThat(tools.http().readTimeout()).isEqualTo(Duration.ofSeconds(5));

      RateLimitProperties rateLimit = context.getBean(RateLimitProperties.class);
      assertThat(rateLimit.requestsPerMinute()).isEqualTo(36);
      assertThat(rateLimit.maxWait()).isEqualTo(Duration.ofSeconds(30));
      assertThat(rateLimit.max429Retries()).isEqualTo(2);
      assertThat(rateLimit.defaultRetryAfter()).isEqualTo(Duration.ofSeconds(5));
    });
  }

  @Test
  void maxModelCallsBelowOneFailsStartup() {
    runner.withPropertyValues("retail.assistant.tools.max-model-calls=0")
        .run(context -> assertThat(context).hasFailed()
            .getFailure().rootCause().hasMessageContaining("maxModelCalls"));
  }

  @Test
  void requestsPerMinuteBelowOneFailsStartup() {
    runner.withPropertyValues("retail.assistant.rate-limit.requests-per-minute=0")
        .run(context -> assertThat(context).hasFailed()
            .getFailure().rootCause().hasMessageContaining("requestsPerMinute"));
  }
}
