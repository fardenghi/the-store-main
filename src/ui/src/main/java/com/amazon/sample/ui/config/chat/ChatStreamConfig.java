package com.amazon.sample.ui.config.chat;

import com.amazon.sample.ui.chat.AssistantChatStreamService;
import com.amazon.sample.ui.chat.ChatStreamService;
import com.amazon.sample.ui.chat.SpringAiChatStreamService;
import com.amazon.sample.ui.config.AssistantProperties;
import com.amazon.sample.ui.config.EndpointProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/** Elige la implementación de {@link ChatStreamService} según el provider (D1). */
@Configuration
@Slf4j
@ConditionalOnProperty(
  prefix = ChatProperties.PREFIX,
  name = "enabled",
  havingValue = "true"
)
public class ChatStreamConfig {

  @Bean
  @ConditionalOnProperty(
    prefix = ChatProperties.PREFIX,
    name = "provider",
    havingValue = "assistant"
  )
  public ChatStreamService assistantChatStreamService(
    WebClient assistantWebClient,
    EndpointProperties endpoints,
    AssistantProperties properties
  ) {
    if (!StringUtils.hasText(endpoints.getAssistant())) {
      throw new IllegalStateException(
        "retail.ui.endpoints.assistant (RETAIL_UI_ENDPOINTS_ASSISTANT) " +
        "must be set when retail.ui.chat.provider is 'assistant'"
      );
    }

    log.info("Creating assistant chat provider for {}", endpoints.getAssistant());

    return new AssistantChatStreamService(
      assistantWebClient,
      endpoints.getAssistant(),
      properties.getChatTimeout()
    );
  }

  @Bean
  @ConditionalOnExpression(
    "'${retail.ui.chat.provider:}'.matches('mock|openai|bedrock')"
  )
  public ChatStreamService springAiChatStreamService(ChatClient chatClient) {
    return new SpringAiChatStreamService(chatClient);
  }
}
