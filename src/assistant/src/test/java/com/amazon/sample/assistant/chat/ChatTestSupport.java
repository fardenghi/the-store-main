package com.amazon.sample.assistant.chat;

import com.amazon.sample.assistant.chat.context.ContextRetriever;
import com.amazon.sample.assistant.chat.context.SystemPromptFactory;
import com.amazon.sample.assistant.chat.llm.ChatRateLimiter;
import com.amazon.sample.assistant.chat.llm.ReasoningPolicy;
import com.amazon.sample.assistant.chat.rewrite.QueryRewriter;
import com.amazon.sample.assistant.chat.session.SessionStore;
import com.amazon.sample.assistant.config.ChatProperties;
import com.amazon.sample.assistant.config.RateLimitProperties;
import com.amazon.sample.assistant.config.ToolsProperties;
import com.amazon.sample.assistant.tools.SafeToolCallback;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;

/** Arma el pipeline del turno sobre un {@link ChatModel} mockeado. */
final class ChatTestSupport {

  static final ToolsProperties TOOLS = new ToolsProperties(4, 6, 10, 5, 10, 300,
      new ToolsProperties.Http(Duration.ofSeconds(2), Duration.ofSeconds(5)),
      ToolsProperties.CorrectiveToolChoice.REQUIRED);

  /** Las mismas tools, con la vuelta correctiva que pide el tool call en el aviso. */
  static final ToolsProperties TOOLS_PROMPT = new ToolsProperties(4, 6, 10, 5, 10, 300,
      TOOLS.http(), ToolsProperties.CorrectiveToolChoice.PROMPT);

  static final RateLimitProperties RATE_LIMIT = new RateLimitProperties(36,
      Duration.ofSeconds(30), 2, Duration.ofSeconds(5));

  private ChatTestSupport() {
  }

  static SystemPromptFactory systemPrompt() {
    return new SystemPromptFactory(new ClassPathResource("prompts/system.st"));
  }

  /**
   * Limitador con el reloj del scheduler de Reactor: con
   * {@code StepVerifier.withVirtualTime}, la pausa y las esperas usan el tiempo
   * virtual, igual que los {@code Mono.delay} del ciclo.
   */
  static ChatRateLimiter limiter() {
    return new ChatRateLimiter(36, Duration.ofSeconds(5), new ReactorClock());
  }

  /** {@link Clock} que lee la hora del scheduler {@code parallel} de Reactor. */
  static final class ReactorClock extends Clock {

    @Override
    public java.time.Instant instant() {
      return java.time.Instant.ofEpochMilli(
          reactor.core.scheduler.Schedulers.parallel().now(java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    @Override
    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }

  /** Ciclo de tools con las tools dadas registradas en el cliente principal. */
  static ToolCallingLoop loop(ChatModel chatModel, ChatProperties.Chat chat,
      ChatRateLimiter limiter, List<ToolCallback> tools) {
    return loop(chatModel, chat, limiter, tools, TOOLS);
  }

  /** Ciclo de tools con otras {@link ToolsProperties} (por ejemplo, {@link #TOOLS_PROMPT}). */
  static ToolCallingLoop loop(ChatModel chatModel, ChatProperties.Chat chat,
      ChatRateLimiter limiter, List<ToolCallback> tools, ToolsProperties properties) {
    ChatClient main = ChatClient.builder(chatModel).defaultToolCallbacks(tools).build();
    ToolCallingManager manager = ToolCallingManager.builder()
        .toolCallbackResolver(SafeToolCallback.unknownToolResolver())
        .build();
    return new ToolCallingLoop(main, manager, tools, limiter, properties, RATE_LIMIT, chat);
  }

  static ChatTurnService service(SessionStore sessions, QueryRewriter rewriter,
      ContextRetriever retriever, ChatProperties.Chat chat, ToolCallingLoop loop) {
    return new ChatTurnService(sessions, rewriter, retriever, systemPrompt(),
        new ReasoningPolicy(OpenAiChatOptions.builder().model("main").temperature(0.6).build(),
            chat),
        loop, TOOLS.maxToolCalls(), chat);
  }

  /** El turno sin tools, como en {@code add-assistant-chat}. */
  static ChatTurnService service(SessionStore sessions, QueryRewriter rewriter,
      ContextRetriever retriever, ChatModel chatModel, ChatProperties.Chat chat) {
    return service(sessions, rewriter, retriever, chat,
        loop(chatModel, chat, limiter(), List.of()));
  }
}
