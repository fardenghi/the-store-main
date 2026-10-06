package com.amazon.sample.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amazon.sample.assistant.carts.CartsClient;
import com.amazon.sample.assistant.chat.context.ContextRetriever;
import com.amazon.sample.assistant.chat.context.Retrieval;
import com.amazon.sample.assistant.chat.llm.ChatRateLimiter;
import com.amazon.sample.assistant.chat.rewrite.CatalogTagsCache;
import com.amazon.sample.assistant.chat.rewrite.Intent;
import com.amazon.sample.assistant.chat.rewrite.QueryRewriter;
import com.amazon.sample.assistant.chat.rewrite.Rewrite;
import com.amazon.sample.assistant.chat.session.SessionStore;
import com.amazon.sample.assistant.chat.session.ShownProduct;
import com.amazon.sample.assistant.chat.session.Turn;
import com.amazon.sample.assistant.config.ChatProperties;
import com.amazon.sample.assistant.products.catalog.CatalogClient;
import com.amazon.sample.assistant.products.catalog.CatalogProduct;
import com.amazon.sample.assistant.products.search.ProductSearchService;
import com.amazon.sample.assistant.tools.SafeToolCallback;
import com.amazon.sample.assistant.tools.StoreTools;
import com.amazon.sample.assistant.tools.ToolArguments;
import com.amazon.sample.assistant.tools.TurnToolContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Ciclo de tool calling del turno (D1, D8, D9 y D7 de
 * {@code add-assistant-tools}) sobre un {@link ChatModel} mockeado.
 */
class ToolCallingLoopTest {

  private static final CatalogProduct LAMP = new CatalogProduct(
      UUID.nameUUIDFromBytes("lamp".getBytes()).toString(), "Ceramic Table Lamp",
      "A small ceramic lamp.", 45, List.of(new CatalogProduct.Tag("lighting", "Lighting")));

  private final ChatModel chatModel = mock(ChatModel.class);
  private final QueryRewriter rewriter = mock(QueryRewriter.class);
  private final ContextRetriever retriever = mock(ContextRetriever.class);
  private final CatalogClient catalog = mock(CatalogClient.class);
  private final CartsClient carts = mock(CartsClient.class);
  private final ProductSearchService search = mock(ProductSearchService.class);
  private final CatalogTagsCache tags = mock(CatalogTagsCache.class);
  private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
  private final ChatProperties.Chat chat = ChatTurnServiceTest.properties(false);
  private final SessionStore sessions = new SessionStore(10, Duration.ofMinutes(30), 100);
  private final List<ToolCallback> tools = SafeToolCallback.wrap(MethodToolCallbackProvider
      .builder().toolObjects(new StoreTools(catalog, carts, search,
          new ToolArguments(tags, ChatTestSupport.TOOLS), ChatTestSupport.TOOLS))
      .build().getToolCallbacks());
  private final ListAppender<ILoggingEvent> turnLog = new ListAppender<>();
  private final Logger turnLogger = (Logger) LoggerFactory.getLogger(TurnLogger.LOGGER_NAME);
  private ChatRateLimiter limiter = ChatTestSupport.limiter();

  @BeforeEach
  void setUp() {
    when(tags.tagNames()).thenReturn(List.of("lighting", "seating"));
    when(catalog.getProduct(LAMP.id())).thenReturn(Optional.of(LAMP));
    when(catalog.listProducts(anyList(), any(), anyInt(), anyInt())).thenReturn(List.of(LAMP));
    when(rewriter.rewrite(any(), any())).thenReturn(
        new Rewrite(Intent.OTHER, "", null, null, List.of(), false, 1, 5));
    when(retriever.retrieve(any(), any())).thenReturn(
        new Retrieval(false, false, List.of(), List.of(), false, 0));
    turnLog.start();
    turnLogger.addAppender(turnLog);
  }

  @AfterEach
  void tearDown() {
    turnLogger.detachAppender(turnLog);
  }

  private ChatTurnService service() {
    return ChatTestSupport.service(sessions, rewriter, retriever, chat,
        ChatTestSupport.loop(chatModel, chat, limiter, tools));
  }

  /** Respuestas del modelo, una por llamada; la última se repite. */
  @SafeVarargs
  private void model(Supplier<Flux<ChatResponse>>... responses) {
    AtomicInteger call = new AtomicInteger();
    when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
      prompts.add(invocation.getArgument(0));
      int n = Math.min(call.getAndIncrement(), responses.length - 1);
      return Flux.defer(responses[n]);
    });
  }

  static ChatResponse toolCall(String name, String arguments) {
    return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
        .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + UUID.randomUUID(), "function",
            name, arguments)))
        .build())));
  }

  private static OpenAiChatOptions options(Prompt prompt) {
    return (OpenAiChatOptions) prompt.getOptions();
  }

  private static boolean isEvent(ServerSentEvent<?> event, String name) {
    return name.equals(event.event());
  }

  private static boolean isText(ServerSentEvent<?> event) {
    return event.event() == null && event.comment() == null
        && event.data() instanceof Map<?, ?> data && data.containsKey("text");
  }

  private static WebClientResponseException quota(String retryAfter) {
    HttpHeaders headers = new HttpHeaders();
    if (retryAfter != null) {
      headers.add(HttpHeaders.RETRY_AFTER, retryAfter);
    }
    return WebClientResponseException.create(429, "Too Many Requests", headers, new byte[0],
        null);
  }

  private String turnLine() {
    await().atMost(Duration.ofSeconds(5)).until(() -> !turnLog.list.isEmpty());
    return turnLog.list.get(turnLog.list.size() - 1).getFormattedMessage();
  }

  // 6.1 Ciclo acotado

  @Test
  void modelThatAlwaysAsksForToolsMakesAtMostFourCallsAndTheLastHasToolChoiceNone() {
    model(() -> Flux.just(toolCall("getProductDetails",
        "{\"productId\":\"" + LAMP.id() + "\"}")));

    List<ServerSentEvent<?>> events = service().open("s1", "how much is the lamp?")
        .collectList().block(Duration.ofSeconds(10));

    assertThat(prompts).hasSize(4);
    assertThat(prompts).allSatisfy(prompt ->
        assertThat(options(prompt).getInternalToolExecutionEnabled()).isFalse());
    assertThat(prompts.subList(0, 3)).allSatisfy(prompt ->
        assertThat(options(prompt).getToolChoice()).isNull());
    assertThat(options(prompts.get(3)).getToolChoice()).isEqualTo("none");
    // Las tools van en todas las vueltas, también en la última.
    assertThat(prompts).allSatisfy(prompt -> assertThat(options(prompt).getToolCallbacks())
        .extracting(callback -> callback.getToolDefinition().name())
        .containsExactlyInAnyOrder("searchProducts", "getProductDetails", "addToCart"));
    // Se ejecutaron las tools de las 3 primeras vueltas; la cuarta se ignoró.
    assertThat(events).filteredOn(event -> isEvent(event, "tool")).hasSize(3);
    assertThat(events).filteredOn(ToolCallingLoopTest::isText).singleElement()
        .satisfies(event -> assertThat(event.data())
            .isEqualTo(Map.of("text", ToolCallingLoop.NO_ANSWER_TEXT)));
    assertThat(events.get(events.size() - 1).event()).isEqualTo("done");
  }

  @Test
  void toolResultGoesBackToTheModelAndTheTurnEndsWithItsText() {
    model(() -> Flux.just(toolCall("getProductDetails", "{\"productId\":\"" + LAMP.id() + "\"}")),
        () -> Flux.just(ChatTurnServiceTest.fragment("The lamp is $45 right now.")));

    StepVerifier.create(service().open("s1", "how much is the lamp right now?"))
        .expectNextMatches(event -> isEvent(event, "products"))
        .expectNextMatches(event -> isEvent(event, "tool")
            && event.data().equals(Map.of("tool", "getProductDetails", "ok", true, "products",
                List.of(Map.of("id", LAMP.id(), "name", LAMP.name(), "price", 45L)))))
        .expectNextMatches(event -> isText(event))
        .expectNextMatches(event -> isEvent(event, "done"))
        .verifyComplete();

    assertThat(prompts).hasSize(2);
    assertThat(prompts.get(1).getInstructions()).extracting(m -> m.getMessageType())
        .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT,
            MessageType.TOOL);
    verify(catalog).getProduct(LAMP.id());
  }

  @Test
  void malformedArgumentsAndUnknownToolsGoBackToTheModelAsErrors() {
    model(() -> Flux.just(toolCall("getProductDetails", "{not json"),
            toolCall("deleteCart", "{}")),
        () -> Flux.just(ChatTurnServiceTest.fragment("Sorry, Operative.")));

    List<ServerSentEvent<?>> events = service().open("s1", "lamp price")
        .collectList().block(Duration.ofSeconds(10));

    assertThat(events.get(events.size() - 1).event()).isEqualTo("done");
    var toolMessage = (org.springframework.ai.chat.messages.ToolResponseMessage)
        prompts.get(1).getInstructions().get(3);
    assertThat(toolMessage.getResponses()).extracting(r -> r.responseData())
        .allSatisfy(data -> assertThat(data).contains("invalid-argument"));
  }

  // 6.2 Limitador y tiempos límite

  @Test
  void limiterWaitDoesNotCountForTheFirstFragmentTimeout() {
    limiter = mock(ChatRateLimiter.class);
    when(limiter.reserve(any())).thenReturn(
        new ChatRateLimiter.Reservation(true, Duration.ofSeconds(15)));
    // El primer fragmento llega 18 s después de obtener el lugar: 33 s desde el inicio.
    model(() -> Flux.just(ChatTurnServiceTest.fragment("Here."))
        .delayElements(Duration.ofSeconds(18)));

    StepVerifier.withVirtualTime(this::openTurn)
        .expectNextMatches(event -> isEvent(event, "products"))
        .thenAwait(Duration.ofSeconds(33))
        .thenConsumeWhile(event -> "keepalive".equals(event.comment()))
        .expectNextMatches(ToolCallingLoopTest::isText)
        .expectNextMatches(event -> isEvent(event, "done"))
        .verifyComplete();

    assertThat(turnLine()).contains("limiterWaitMs=15000");
  }

  private Flux<ServerSentEvent<?>> openTurn() {
    return service().open("s1", "hi");
  }

  @Test
  void waitLongerThanMaxWaitEndsWithQuotaErrorWithoutCallingTheModel() {
    limiter = mock(ChatRateLimiter.class);
    when(limiter.reserve(any())).thenReturn(
        new ChatRateLimiter.Reservation(false, Duration.ofSeconds(45)));
    model(() -> Flux.just(ChatTurnServiceTest.fragment("never")));

    StepVerifier.create(service().open("s1", "hi"))
        .expectNextMatches(event -> isEvent(event, "products"))
        .expectNextMatches(event -> isEvent(event, "error")
            && event.data() instanceof Map<?, ?> data
            && "llm-quota-exceeded".equals(data.get("type"))
            && Long.valueOf(45).equals(data.get("retryAfterSeconds")))
        .verifyComplete();

    verify(chatModel, never()).stream(any(Prompt.class));
    assertThat(sessions.get("s1").turns()).isEmpty();
  }

  // 6.3 Reintento ante 429

  @Test
  void transientQuotaErrorWaitsTheRetryAfterAndRetries() {
    model(() -> Flux.error(quota("3")),
        () -> Flux.just(ChatTurnServiceTest.fragment("Back in business.")));

    StepVerifier.withVirtualTime(this::openTurn)
        .expectNextMatches(event -> isEvent(event, "products"))
        .expectNoEvent(Duration.ofMillis(2_999))
        .thenAwait(Duration.ofMillis(1))
        .expectNextMatches(ToolCallingLoopTest::isText)
        .expectNextMatches(event -> isEvent(event, "done"))
        .verifyComplete();

    assertThat(prompts).hasSize(2);
    assertThat(sessions.get("s1").turns()).hasSize(1);
    assertThat(turnLine()).contains("retries429=1", "modelCalls=2", "limiterWaitMs=3000");
  }

  @Test
  void persistentQuotaErrorEndsWithQuotaErrorAndDoesNotStoreTheTurn() {
    model(() -> Flux.error(quota(null)));

    StepVerifier.withVirtualTime(this::openTurn)
        .expectNextMatches(event -> isEvent(event, "products"))
        // Sin Retry-After: 5 s de pausa por defecto, dos veces.
        .thenAwait(Duration.ofSeconds(10))
        .thenConsumeWhile(event -> "keepalive".equals(event.comment()))
        .expectNextMatches(event -> isEvent(event, "error")
            && "llm-quota-exceeded".equals(((Map<?, ?>) event.data()).get("type")))
        .verifyComplete();

    assertThat(prompts).hasSize(3);
    assertThat(sessions.get("s1").turns()).isEmpty();
  }

  @Test
  void quotaErrorAfterAFragmentIsNotRetried() {
    model(() -> Flux.concat(Flux.just(ChatTurnServiceTest.fragment("Partial")),
        Flux.error(quota("1"))));

    StepVerifier.create(service().open("s1", "hi"))
        .expectNextMatches(event -> isEvent(event, "products"))
        .expectNextMatches(ToolCallingLoopTest::isText)
        .expectNextMatches(event -> isEvent(event, "error"))
        .verifyComplete();

    assertThat(prompts).hasSize(1);
  }

  @Test
  void duringThePauseNoOtherSessionCanCallTheProvider() {
    model(() -> Flux.error(quota("5")), () -> Flux.never());

    service().open("s1", "hi").subscribe();

    await().atMost(Duration.ofSeconds(5)).until(() -> prompts.size() == 1
        && !limiter.reserve(Duration.ZERO).granted());
    ChatRateLimiter.Reservation otherSession = limiter.reserve(Duration.ofSeconds(30));
    assertThat(otherSession.delay()).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(5));
  }

  // 6.4 Memoria y línea del turno

  @Test
  void productsFromToolsAreRememberedAndToolMessagesAreNotStored() {
    ContextRetriever realRetriever = new ContextRetriever(search, 5, 0);
    ChatTurnService service = ChatTestSupport.service(sessions, rewriter, realRetriever, chat,
        ChatTestSupport.loop(chatModel, chat, limiter, tools));
    model(() -> Flux.just(toolCall("searchProducts", "{\"tags\":[\"lighting\"]}")),
        () -> Flux.just(ChatTurnServiceTest.fragment("Try the Ceramic Table Lamp ($45).")),
        () -> Flux.just(ChatTurnServiceTest.fragment("Still $45.")));

    service.open("s1", "lamps please").blockLast(Duration.ofSeconds(10));
    service.open("s1", "is it still available?").blockLast(Duration.ofSeconds(10));

    assertThat(sessions.get("s1").turns()).containsExactly(
        new Turn("lamps please", "Try the Ceramic Table Lamp ($45)."),
        new Turn("is it still available?", "Still $45."));
    Prompt second = prompts.get(2);
    assertThat(second.getInstructions()).extracting(m -> m.getMessageType())
        .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT,
            MessageType.USER);
    assertThat(second.getInstructions().get(0).getText())
        .contains("Previously shown products")
        .contains("[" + LAMP.id() + "] Ceramic Table Lamp | $45");
    verify(search, never()).search(any(), anyList(), any(), any(), any());
    verify(catalog).listProducts(List.of("lighting"), null, 1, 50);
  }

  @Test
  void turnLineHasModelCallsToolsLimiterWaitAndRetries() {
    when(carts.getCart("s1")).thenReturn(new CartsClient.Cart("s1", List.of()));
    model(() -> Flux.just(toolCall("searchProducts", "{\"tags\":[\"lighting\"]}")),
        () -> Flux.just(toolCall("addToCart", "{\"productId\":\"" + LAMP.id() + "\"}")),
        () -> Flux.just(ChatTurnServiceTest.fragment("Added.")));

    service().open("s1", "add a lamp").blockLast(Duration.ofSeconds(10));

    assertThat(turnLine()).contains("outcome=done", "nvidiaRequests=4", "modelCalls=3",
        "tools=searchProducts:ok,addToCart:ok", "limiterWaitMs=0", "retries429=0");
    verify(carts).addItem("s1", LAMP.id(), 1, 45);
  }

  @Test
  void shownProductsPutToolProductsFirstWithoutDuplicatesUpToTen() {
    List<ShownProduct> retrieved = IntStream.range(0, 8)
        .mapToObj(i -> new ShownProduct("r" + i, "R" + i, "", 10, List.of())).toList();
    TurnToolContext toolTurn = new TurnToolContext("s1",
        reactor.core.publisher.Sinks.many().replay().all(), 6);
    toolTurn.addShown(List.of(new ShownProduct("t1", "T1", "", 1, List.of()),
        new ShownProduct("r0", "R0", "", 10, List.of()),
        new ShownProduct("t2", "T2", "", 2, List.of())));

    List<ShownProduct> shown = ChatTurnService.shownProducts(
        new Retrieval(true, false, retrieved, List.of(), false, 0), toolTurn);

    assertThat(shown).extracting(ShownProduct::id)
        .containsExactly("t1", "r0", "t2", "r1", "r2", "r3", "r4", "r5", "r6", "r7");
    assertThat(ChatTurnService.shownProducts(new Retrieval(false, false, List.of(), List.of(),
        false, 0), new TurnToolContext("s1", reactor.core.publisher.Sinks.many().replay().all(),
        6))).isNull();
  }

  @Test
  void rewriteIsNotCountedAsAToolNorAsAModelCall() {
    model(() -> Flux.just(ChatTurnServiceTest.fragment("Hello, Operative.")));

    service().open("s1", "hi").blockLast(Duration.ofSeconds(10));

    assertThat(turnLine()).contains("nvidiaRequests=2", "modelCalls=1", "tools=-");
    verify(catalog, never()).listProducts(anyList(), isNull(), anyInt(), anyInt());
  }
}
