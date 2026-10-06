package com.amazon.sample.assistant.config;

import com.amazon.sample.assistant.chat.ChatTurnService;
import com.amazon.sample.assistant.chat.ToolCallingLoop;
import com.amazon.sample.assistant.chat.context.ContextRetriever;
import com.amazon.sample.assistant.chat.context.SystemPromptFactory;
import com.amazon.sample.assistant.chat.llm.ChatRateLimiter;
import com.amazon.sample.assistant.chat.llm.ExtraBody;
import com.amazon.sample.assistant.chat.llm.ReasoningPolicy;
import com.amazon.sample.assistant.chat.rewrite.CatalogTagsCache;
import com.amazon.sample.assistant.chat.rewrite.QueryRewriter;
import com.amazon.sample.assistant.chat.session.SessionStore;
import com.amazon.sample.assistant.products.catalog.CatalogClient;
import com.amazon.sample.assistant.products.search.ProductSearchService;
import com.amazon.sample.assistant.tools.SafeToolCallback;
import com.amazon.sample.assistant.tools.StoreTools;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
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
    OpenAiChatOptions options = OpenAiChatOptions.builder()
        .model(model)
        .temperature(MAIN_TEMPERATURE)
        .maxTokens(properties.chat().maxTokens())
        .build();
    ExtraBody.apply(options, properties.chat().reasoning().offExtraBody());
    return options;
  }

  /**
   * Modelo compacto de reescritura: temperatura 0, max-tokens acotado y thinking
   * desactivado (D3). Va sin streaming, por el {@code RestClient} de Spring AI,
   * así que su lectura HTTP tiene el {@code spring.http.client.read-timeout}.
   */
  @Bean
  ChatClient rewriteChatClient(OpenAiChatModel chatModel,
      @Value("${retail.assistant.models.rewrite}") String model, ChatProperties properties,
      @Value("${spring.http.client.read-timeout:#{null}}") Duration readTimeout) {
    checkRewriteTimeout(readTimeout, properties.rewrite().timeout());
    OpenAiChatOptions options = OpenAiChatOptions.builder()
        .model(model)
        .temperature(0.0)
        .maxTokens(properties.rewrite().maxTokens())
        .build();
    ExtraBody.apply(options, properties.rewrite().extraBody());
    return ChatClient.builder(chatModel).defaultOptions(options).build();
  }

  /**
   * El {@code read-timeout} de los {@code RestClient} tiene que ser mayor que el
   * tiempo límite de la reescritura: si no, la lectura HTTP corta la reescritura
   * antes (con 10 s contra 12 s, los fallbacks llegaban a los 10 s como
   * {@code llm-provider-unavailable}, {@code select-assistant-models}).
   *
   * @param readTimeout {@code spring.http.client.read-timeout}; sin valor, el del
   *     cliente HTTP, que no corta antes
   * @throws IllegalStateException si el {@code read-timeout} no es mayor
   */
  static void checkRewriteTimeout(Duration readTimeout, Duration rewriteTimeout) {
    if (readTimeout != null && readTimeout.compareTo(rewriteTimeout) <= 0) {
      throw new IllegalStateException("spring.http.client.read-timeout (" + readTimeout
          + ") tiene que ser mayor que retail.assistant.rewrite.timeout (" + rewriteTimeout
          + "): si no, la lectura HTTP corta la reescritura antes de su tiempo límite");
    }
  }

  /**
   * Las tools del modelo principal ({@code add-assistant-tools}, D2), envueltas
   * para que un tool call ilegible vuelva al modelo como error.
   */
  @Bean
  List<ToolCallback> storeToolCallbacks(StoreTools storeTools) {
    return SafeToolCallback.wrap(MethodToolCallbackProvider.builder().toolObjects(storeTools)
        .build().getToolCallbacks());
  }

  /**
   * Modelo principal (D3). El razonamiento y el max-tokens se eligen por turno.
   * Es el único cliente con tools: el de reescritura no las tiene
   * ({@code add-assistant-tools}, D2).
   */
  @Bean
  ChatClient mainChatClient(OpenAiChatModel chatModel,
      @Qualifier("mainChatOptions") OpenAiChatOptions mainChatOptions,
      @Qualifier("storeToolCallbacks") List<ToolCallback> storeToolCallbacks) {
    return ChatClient.builder(chatModel).defaultOptions(mainChatOptions)
        .defaultToolCallbacks(storeToolCallbacks).build();
  }

  /**
   * Ejecuta los tool calls del ciclo del turno. Un nombre de tool que no existe
   * vuelve al modelo como error en lugar de cortar el turno.
   */
  @Bean
  ToolCallingManager storeToolCallingManager() {
    return ToolCallingManager.builder()
        .toolCallbackResolver(SafeToolCallback.unknownToolResolver())
        .build();
  }

  @Bean
  ToolCallingLoop toolCallingLoop(@Qualifier("mainChatClient") ChatClient mainChatClient,
      @Qualifier("storeToolCallingManager") ToolCallingManager toolCallingManager,
      @Qualifier("storeToolCallbacks") List<ToolCallback> storeToolCallbacks,
      ChatRateLimiter limiter, ToolsProperties tools, RateLimitProperties rateLimit,
      ChatProperties properties) {
    return new ToolCallingLoop(mainChatClient, toolCallingManager, storeToolCallbacks, limiter,
        tools, rateLimit, properties.chat());
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
      @Value("classpath:prompts/rewrite.st") Resource template, ChatRateLimiter limiter) {
    return new QueryRewriter(rewriteChatClient, tags, properties.rewrite(), template, limiter);
  }

  @Bean
  ContextRetriever contextRetriever(ProductSearchService search, ChatProperties properties) {
    return new ContextRetriever(search, properties.chat().retrievalK(),
        properties.chat().minScore());
  }

  @Bean
  SystemPromptFactory systemPromptFactory(
      @Value("classpath:prompts/system.st") Resource template, CatalogTagsCache tags) {
    return new SystemPromptFactory(template, tags::tagNames);
  }

  @Bean
  ReasoningPolicy reasoningPolicy(@Qualifier("mainChatOptions") OpenAiChatOptions mainChatOptions,
      ChatProperties properties) {
    return new ReasoningPolicy(mainChatOptions, properties.chat());
  }

  @Bean
  ChatTurnService chatTurnService(SessionStore sessions, QueryRewriter rewriter,
      ContextRetriever retriever, SystemPromptFactory systemPrompt, ReasoningPolicy reasoning,
      ToolCallingLoop loop, ToolsProperties tools, ChatProperties properties) {
    return new ChatTurnService(sessions, rewriter, retriever, systemPrompt, reasoning, loop,
        tools.maxToolCalls(), properties.chat());
  }
}
