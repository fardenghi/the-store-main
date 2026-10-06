package com.amazon.sample.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amazon.sample.assistant.chat.context.ContextRetriever;
import com.amazon.sample.assistant.chat.context.Retrieval;
import com.amazon.sample.assistant.chat.rewrite.Intent;
import com.amazon.sample.assistant.chat.rewrite.QueryRewriter;
import com.amazon.sample.assistant.chat.rewrite.Rewrite;
import com.amazon.sample.assistant.chat.session.SessionStore;
import com.amazon.sample.assistant.chat.session.ShownProduct;
import com.amazon.sample.assistant.config.ChatProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** Línea de log por turno y top-k crudo en segundo plano (D9). */
class TurnLogTest {

  private static ShownProduct product(String id) {
    return new ShownProduct(id, "Product " + id, "desc", 100, List.of("seating"));
  }

  private final ChatModel chatModel = mock(ChatModel.class);
  private final QueryRewriter rewriter = mock(QueryRewriter.class);
  private final ContextRetriever retriever = mock(ContextRetriever.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(TurnLogger.LOGGER_NAME);

  @BeforeEach
  void setUp() {
    appender.start();
    logger.addAppender(appender);
    when(rewriter.rewrite(any(), any())).thenReturn(new Rewrite(Intent.SEARCH,
        "cozy reading armchair", null, 138, List.of("lighting"), false, 1, 42));
    when(retriever.retrieve(any(), any())).thenReturn(new Retrieval(true, false,
        List.of(product("a"), product("b"), product("c")), List.of(), false, 7));
    when(chatModel.stream(any(Prompt.class)))
        .thenReturn(Flux.just(ChatTurnServiceTest.fragment("Here you go.")));
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
  }

  private ChatTurnService service(boolean compareRaw) {
    ChatProperties.Chat chat = ChatTurnServiceTest.properties(compareRaw);
    return ChatTestSupport.service(new SessionStore(10, Duration.ofMinutes(30), 100), rewriter,
        retriever, chatModel, chat);
  }

  private String turnLine() {
    await().atMost(Duration.ofSeconds(5)).until(() -> !appender.list.isEmpty());
    assertThat(appender.list).hasSize(1);
    return appender.list.get(0).getFormattedMessage();
  }

  @Test
  void lineHasAllFieldsAndBothTopK() {
    when(retriever.search(eq("hey, something comfy to read in?"), isNull(), isNull(), anyList()))
        .thenReturn(List.of(product("b"), product("x"), product("a")));

    service(true).open("0123456789abcdef", "hey, something comfy to read in?")
        .collectList().block();

    String line = turnLine();
    assertThat(line)
        .contains("session=01234567 ")
        .doesNotContain("0123456789abcdef")
        .contains("outcome=done")
        .contains("intent=search")
        .contains("rewrite=ok")
        .contains("raw=\"hey, something comfy to read in?\"")
        .contains("query=\"cozy reading armchair\"")
        .contains("minPrice=-")
        .contains("maxPrice=138")
        .contains("excludeTags=[lighting]")
        .contains("reasoning=off")
        .contains("topk=[a,b,c]")
        .contains("rawTopk=[b,x,a]")
        .contains("overlap=2")
        .contains("nvidiaRequests=2")
        .contains("claimGuard=-")
        .contains("rewriteMs=42")
        .contains("retrievalMs=7")
        .containsPattern("firstFragmentMs=\\d+")
        .contains("firstReasoningMs=-")
        .containsPattern("totalMs=\\d+");
  }

  @Test
  void withoutComparisonThereIsNoRawSearch() {
    service(false).open("s1", "hey, something comfy to read in?").collectList().block();

    String line = turnLine();
    assertThat(line).contains("rawTopk=-").contains("overlap=-").contains("topk=[a,b,c]");
    verify(retriever, never()).search(any(), any(), any(), any());
  }

  @Test
  void simpleTurnMakesTwoProviderRequests() {
    service(false).open("s1", "a rug for the living room").collectList().block();

    assertThat(turnLine()).contains("nvidiaRequests=2").contains("reasoning=off");
  }

  @Test
  void comparisonLogsReasoningOn() {
    when(rewriter.rewrite(any(), any())).thenReturn(new Rewrite(Intent.COMPARE,
        "armchairs", null, null, List.of(), false, 1, 10));

    service(false).open("s1", "compare the first two").collectList().block();

    assertThat(turnLine()).contains("intent=compare").contains("reasoning=on");
  }
}
