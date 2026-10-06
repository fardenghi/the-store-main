package com.amazon.sample.ui.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazon.sample.ui.UiApplication;
import com.amazon.sample.ui.chat.ChatStreamService;
import com.amazon.sample.ui.chat.SpringAiChatStreamService;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

class AssistantConfigurationTests {

  @Nested
  @SpringBootTest
  class Defaults {

    @Autowired
    private AssistantProperties properties;

    @Autowired
    private EndpointProperties endpoints;

    @Test
    void loadsDefaultsFromApplicationYml() {
      assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
      assertThat(properties.getChatTimeout()).isEqualTo(Duration.ofSeconds(160));
      assertThat(properties.getKeepaliveInterval()).isEqualTo(
        Duration.ofSeconds(10)
      );
      assertThat(properties.getSimilarK()).isEqualTo(4);
      assertThat(properties.getSimilarTimeout()).isEqualTo(Duration.ofSeconds(2));
      assertThat(endpoints.getAssistant()).isNullOrEmpty();
    }
  }

  @Test
  void assistantProviderWithoutEndpointFailsToStart() {
    assertThatThrownBy(() ->
      start(
        "retail.ui.chat.enabled=true",
        "retail.ui.chat.provider=assistant"
      ).close()
    )
      .rootCause()
      .hasMessageContaining("retail.ui.endpoints.assistant");
  }

  @Test
  void mockProviderWithoutEndpointStarts() {
    try (
      var context = start(
        "retail.ui.chat.enabled=true",
        "retail.ui.chat.provider=mock"
      )
    ) {
      assertThat(context.getBean(ChatStreamService.class)).isInstanceOf(
        SpringAiChatStreamService.class
      );
    }
  }

  // Como argumentos de línea de comandos, para que le ganen a application.yml.
  private static ConfigurableApplicationContext start(String... properties) {
    var args = new ArrayList<String>();
    args.add("--server.port=0");
    for (var property : properties) {
      args.add("--" + property);
    }
    return new SpringApplicationBuilder(UiApplication.class).run(
      args.toArray(String[]::new)
    );
  }
}
