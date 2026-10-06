package com.amazon.sample.assistant.config;

import com.amazon.sample.assistant.chat.ChatTurnService;
import com.amazon.sample.assistant.chat.context.ContextRetriever;
import com.amazon.sample.assistant.chat.context.SystemPromptFactory;
import com.amazon.sample.assistant.chat.llm.ReasoningPolicy;
import com.amazon.sample.assistant.chat.rewrite.CatalogTagsCache;
import com.amazon.sample.assistant.chat.rewrite.QueryRewriter;
import com.amazon.sample.assistant.chat.session.SessionStore;
import com.amazon.sample.assistant.products.catalog.CatalogClient;
import com.amazon.sample.assistant.products.search.ProductSearchService;
import java.util.LinkedHashMap;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * Chat con el asistente (add-assistant-chat): dos {@link ChatClient} sobre el
 * mismo {@link OpenAiChatModel} autoconfigurado (D3), memoria por sesión,
 * reescritura, retrieval y el pipeline del turno.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatProperties.class)
public class ChatConfiguration {

  /** Temperatura del modelo principal: la que usaba la {@code ui}. */
  static final double MAIN_TEMPERATURE = 0.6;

  /** Opciones base del modelo principal; el turno les suma razonamiento y max-tokens (D7). */
  @Bean
  OpenAiChatOptions mainChatOptions(
      @Value("${spring.ai.openai.chat.options.model}") String model, ChatProperties properties) {
    return OpenAiChatOptions.builder()
        .model(model)
        .temperature(MAIN_TEMPERATURE)
        .maxTokens(properties.chat().maxTokens())
        .extraBody(new LinkedHashMap<>(properties.chat().reasoning().offExtraBody()))
        .build();
  }

  /** Modelo compacto de reescritura: temperatura 0, 256 tokens y thinking desactivado (D3). */
  @Bean
  ChatClient rewriteChatClient(OpenAiChatModel chatModel,
      @Value("${retail.assistant.models.rewrite}") String model, ChatProperties properties) {
    return ChatClient.builder(chatModel)
        .defaultOptions(OpenAiChatOptions.builder()
            .model(model)
            .temperature(0.0)
            .maxTokens(256)
            .extraBody(new LinkedHashMap<>(properties.rewrite().extraBody()))
            .build())
        .build();
  }

  /** Modelo principal (D3). El razonamiento y el max-tokens se eligen por turno. */
  @Bean
  ChatClient mainChatClient(OpenAiChatModel chatModel,
      @Qualifier("mainChatOptions") OpenAiChatOptions mainChatOptions) {
    return ChatClient.builder(chatModel).defaultOptions(mainChatOptions).build();
  }

  @Bean
  SessionStore sessionStore(ChatProperties properties) {
    ChatProperties.Memory memory = properties.chat().memory();
    return new SessionStore(memory.maxTurns(), memory.idleTtl(), memory.maxSessions());
  }

  @Bean
  CatalogTagsCache catalogTagsCache(CatalogClient catalog) {
    return new CatalogTagsCache(catalog);
  }

  @Bean
  QueryRewriter queryRewriter(@Qualifier("rewriteChatClient") ChatClient rewriteChatClient,
      CatalogTagsCache tags, ChatProperties properties,
      @Value("classpath:prompts/rewrite.st") Resource template) {
    return new QueryRewriter(rewriteChatClient, tags, properties.rewrite(), template);
  }

  @Bean
  ContextRetriever contextRetriever(ProductSearchService search, ChatProperties properties) {
    return new ContextRetriever(search, properties.chat().retrievalK(),
        properties.chat().minScore());
  }

  @Bean
  SystemPromptFactory systemPromptFactory(
      @Value("classpath:prompts/system.st") Resource template) {
    return new SystemPromptFactory(template);
  }

  @Bean
  ReasoningPolicy reasoningPolicy(@Qualifier("mainChatOptions") OpenAiChatOptions mainChatOptions,
      ChatProperties properties) {
    return new ReasoningPolicy(mainChatOptions, properties.chat());
  }

  @Bean
  ChatTurnService chatTurnService(SessionStore sessions, QueryRewriter rewriter,
      ContextRetriever retriever, SystemPromptFactory systemPrompt, ReasoningPolicy reasoning,
      @Qualifier("mainChatClient") ChatClient mainChatClient, ChatProperties properties) {
    return new ChatTurnService(sessions, rewriter, retriever, systemPrompt, reasoning,
        mainChatClient, properties.chat());
  }
}
