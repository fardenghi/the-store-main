package com.amazon.sample.ui.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class SpringAiChatStreamServiceTests {

  @Test
  void mockModelStreamsTextThenDone() {
    var service = new SpringAiChatStreamService(
      ChatClient.create(new MockChatModel())
    );

    StepVerifier.create(service.stream("session", "hello"))
      .assertNext(event -> {
        assertThat(event.event()).isNull();
        assertThat(event.data()).isEqualTo(
          "{\"text\":\"This is a mock response\"}"
        );
      })
      .assertNext(event -> assertThat(event.event()).isEqualTo("done"))
      .verifyComplete();
  }

  @Test
  void sendsOnlyTheUserMessageWithoutSystemPrompt() {
    var captured = new AtomicReference<Prompt>();
    ChatModel capturing = new MockChatModel() {
      @Override
      public Flux<ChatResponse> stream(Prompt prompt) {
        captured.set(prompt);
        return super.stream(prompt);
      }
    };

    var service = new SpringAiChatStreamService(ChatClient.create(capturing));

    StepVerifier.create(service.stream("session", "I need a lamp"))
      .expectNextCount(2)
      .verifyComplete();

    var messages = captured.get().getInstructions();
    assertThat(messages).noneMatch(m -> m instanceof SystemMessage);
    assertThat(messages).hasSize(1);
    assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
    assertThat(List.of(messages.get(0).getText())).containsExactly(
      "I need a lamp"
    );
  }
}
