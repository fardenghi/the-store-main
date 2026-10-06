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
import com.amazon.sample.assistant.chat.session.ToolRound;
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
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
  void productsFromToolsAreRememberedAndToolCallsAreStoredCompact() {
    ContextRetriever realRetriever = new ContextRetriever(search, 5, 0);
    ChatTurnService service = ChatTestSupport.service(sessions, rewriter, realRetriever, chat,
        ChatTestSupport.loop(chatModel, chat, limiter, tools));
    ChatResponse search = toolCall("searchProducts", "{\"tags\":[\"lighting\"]}");
    String callId = search.getResult().getOutput().getToolCalls().get(0).id();
    model(() -> Flux.just(ChatTurnServiceTest.fragment("Checking the lamps. "), search),
        () -> Flux.just(ChatTurnServiceTest.fragment("Try the Ceramic Table Lamp ($45).")),
        () -> Flux.just(ChatTurnServiceTest.fragment("Still $45.")));

    service.open("s1", "lamps please").blockLast(Duration.ofSeconds(10));
    service.open("s1", "is it still available?").blockLast(Duration.ofSeconds(10));

    String compact = "{\"products\":[{\"id\":\"" + LAMP.id()
        + "\",\"name\":\"Ceramic Table Lamp\",\"price\":45}]}";
    assertThat(sessions.get("s1").turns()).containsExactly(
        new Turn("lamps please", "Checking the lamps. Try the Ceramic Table Lamp ($45).",
            List.of(new ToolRound("Checking the lamps. ", List.of(new ToolRound.Call(callId,
                "searchProducts", "{\"tags\":[\"lighting\"]}", compact))))),
        new Turn("is it still available?", "Still $45."));
    // El historial del turno siguiente muestra el tool call y su resultado compacto.
    Prompt second = prompts.get(2);
    assertThat(second.getInstructions()).extracting(m -> m.getMessageType())
        .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT,
            MessageType.TOOL, MessageType.ASSISTANT, MessageType.USER);
    AssistantMessage withCalls = (AssistantMessage) second.getInstructions().get(2);
    assertThat(withCalls.getText()).isEqualTo("Checking the lamps. ");
    assertThat(withCalls.getToolCalls()).singleElement().satisfies(call -> {
      assertThat(call.id()).isEqualTo(callId);
      assertThat(call.name()).isEqualTo("searchProducts");
    });
    ToolResponseMessage responses = (ToolResponseMessage) second.getInstructions().get(3);
    assertThat(responses.getResponses()).singleElement().satisfies(response -> {
      assertThat(response.id()).isEqualTo(callId);
      assertThat(response.responseData()).isEqualTo(compact);
    });
    assertThat(second.getInstructions().get(4).getText())
        .isEqualTo("Try the Ceramic Table Lamp ($45).");
    assertThat(second.getInstructions().get(0).getText())
        .contains("Previously shown products")
        .contains("[" + LAMP.id() + "] Ceramic Table Lamp | $45");
    verify(this.search, never()).search(any(), anyList(), any(), any(), any());
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

  // Correcciones posteriores: confirmación fiel de los agregados al carrito

  private static String texts(List<ServerSentEvent<?>> events) {
    StringBuilder text = new StringBuilder();
    events.stream().filter(ToolCallingLoopTest::isText)
        .forEach(event -> text.append(((Map<?, ?>) event.data()).get("text")));
    return text.toString();
  }

  @Test
  void falseAddClaimIsDroppedAndTheCorrectiveRoundCallsAddToCart() {
    when(carts.getCart("s1")).thenReturn(new CartsClient.Cart("s1",
        List.of(new CartsClient.Item(LAMP.id(), 2, 45))));
    model(() -> Flux.just(ChatTurnServiceTest.fragment("Right away, Operative. "),
            ChatTurnServiceTest.fragment("Two Ceramic Table Lamps have been added to your cart.")),
        () -> Flux.just(toolCall("addToCart",
            "{\"productId\":\"" + LAMP.id() + "\",\"quantity\":2}")),
        () -> Flux.just(ChatTurnServiceTest.fragment(" Done: two lamps added to your cart.")));

    List<ServerSentEvent<?>> events = service().open("s1", "add two lamps to my cart")
        .collectList().block(Duration.ofSeconds(10));

    assertThat(texts(events)).isEqualTo("Right away, Operative. Done: two lamps added to your "
        + "cart.");
    assertThat(events).filteredOn(event -> isEvent(event, "cart-updated")).hasSize(1);
    verify(carts).addItem("s1", LAMP.id(), 2, 45);
    // La vuelta correctiva lleva lo que dijo el modelo y el aviso de la tienda.
    assertThat(prompts).hasSize(3);
    List<org.springframework.ai.chat.messages.Message> corrective =
        prompts.get(1).getInstructions();
    assertThat(corrective.get(corrective.size() - 2).getText())
        .contains("have been added to your cart");
    assertThat(corrective.get(corrective.size() - 1).getText())
        .isEqualTo(ToolCallingLoop.CORRECTION_NOTE);
    // El usuario pidió agregar: la vuelta correctiva obliga a pedir una tool.
    assertThat(options(prompts.get(1)).getToolChoice()).isEqualTo("required");
    assertThat(options(prompts.get(2)).getToolChoice()).isNull();
    assertThat(turnLine()).contains("tools=addToCart:ok", "claimGuard=dropped:1+retry",
        "corrections=claim+required", "modelCalls=3");
    // La memoria guarda lo que vio el usuario, con el tool call, y no el aviso.
    Turn stored = sessions.get("s1").turns().get(0);
    assertThat(stored.assistant()).doesNotContain("have been added")
        .doesNotContain("Store system note");
    assertThat(stored.toolRounds()).singleElement().satisfies(round ->
        assertThat(round.calls()).extracting(ToolRound.Call::name).containsExactly("addToCart"));
  }

  @Test
  void modelThatKeepsClaimingWithoutAddToCartEndsWithTheNotAddedText() {
    model(() -> Flux.just(ChatTurnServiceTest.fragment("*adds two lamps to cart* "),
        ChatTurnServiceTest.fragment("Consider it done, Operative.")));

    List<ServerSentEvent<?>> events = service().open("s1", "add two lamps to my cart")
        .collectList().block(Duration.ofSeconds(10));

    // La acotación no termina en punto: forma una oración con la siguiente y se
    // descartan juntas, en las dos vueltas.
    assertThat(texts(events)).isEqualTo(ToolCallingLoop.NOT_ADDED_TEXT);
    assertThat(events).filteredOn(event -> isEvent(event, "cart-updated")).isEmpty();
    assertThat(events.get(events.size() - 1).event()).isEqualTo("done");
    verify(carts, never()).addItem(any(), any(), anyInt(), anyInt());
    // Dos vueltas correctivas como máximo por turno.
    assertThat(prompts).hasSize(1 + ToolCallingLoop.MAX_CORRECTIONS);
    assertThat(turnLine()).contains("tools=-", "claimGuard=dropped:3+retry+notice",
        "corrections=claim+required,claim+required");
    assertThat(sessions.get("s1").turns().get(0).assistant())
        .endsWith(ToolCallingLoop.NOT_ADDED_TEXT).doesNotContain("adds two lamps");
  }

  // Segundo intento de select-assistant-models: vuelta correctiva sin tool_choice required

  private ChatTurnService promptService() {
    return ChatTestSupport.service(sessions, rewriter, retriever, chat,
        ChatTestSupport.loop(chatModel, chat, limiter, tools, ChatTestSupport.TOOLS_PROMPT));
  }

  @Test
  void withThePromptModeTheCorrectiveRoundAsksForTheToolCallWithoutToolChoice() {
    when(carts.getCart("s1")).thenReturn(new CartsClient.Cart("s1",
        List.of(new CartsClient.Item(LAMP.id(), 2, 45))));
    model(() -> Flux.just(ChatTurnServiceTest.fragment("Right away, Operative. "),
            ChatTurnServiceTest.fragment("Two Ceramic Table Lamps have been added to your cart.")),
        () -> Flux.just(toolCall("addToCart",
            "{\"productId\":\"" + LAMP.id() + "\",\"quantity\":2}")),
        () -> Flux.just(ChatTurnServiceTest.fragment(" Done: two lamps added to your cart.")));

    List<ServerSentEvent<?>> events = promptService().open("s1", "add two lamps to my cart")
        .collectList().block(Duration.ofSeconds(10));

    assertThat(texts(events)).isEqualTo("Right away, Operative. Done: two lamps added to your "
        + "cart.");
    verify(carts).addItem("s1", LAMP.id(), 2, 45);
    assertThat(prompts).hasSize(3);
    // Ningún request lleva tool_choice: el aviso pide el tool call.
    assertThat(prompts).allSatisfy(prompt -> assertThat(options(prompt).getToolChoice()).isNull());
    List<org.springframework.ai.chat.messages.Message> corrective =
        prompts.get(1).getInstructions();
    assertThat(corrective.get(corrective.size() - 1).getText())
        .isEqualTo(ToolCallingLoop.CORRECTION_NOTE + " " + ToolCallingLoop.TOOL_CALL_NOTE);
    assertThat(turnLine()).contains("tools=addToCart:ok", "claimGuard=dropped:1+retry",
        "corrections=claim+prompt", "modelCalls=3");
  }

  @Test
  void withThePromptModeFalseClaimsAreStillBlocked() {
    // Un modelo que ignora el aviso y vuelve a afirmar el agregado.
    model(() -> Flux.just(ChatTurnServiceTest.fragment("*adds two lamps to cart* "),
        ChatTurnServiceTest.fragment("Consider it done, Operative.")));

    List<ServerSentEvent<?>> events = promptService().open("s1", "add two lamps to my cart")
        .collectList().block(Duration.ofSeconds(10));

    assertThat(texts(events)).isEqualTo(ToolCallingLoop.NOT_ADDED_TEXT);
    assertThat(events).filteredOn(event -> isEvent(event, "cart-updated")).isEmpty();
    verify(carts, never()).addItem(any(), any(), anyInt(), anyInt());
    assertThat(prompts).hasSize(1 + ToolCallingLoop.MAX_CORRECTIONS);
    assertThat(prompts).allSatisfy(prompt -> assertThat(options(prompt).getToolChoice()).isNull());
    assertThat(turnLine()).contains("claimGuard=dropped:3+retry+notice",
        "corrections=claim+prompt,claim+prompt");
  }

  @Test
  void withThePromptModeAnAnnouncementOutsideACartRequestKeepsThePlainNote() {
    model(() -> Flux.just(ChatTurnServiceTest.fragment("Let me check the price for you.")),
        () -> Flux.just(ChatTurnServiceTest.fragment("It costs $45.")));

    promptService().open("s1", "how much is the lamp").collectList()
        .block(Duration.ofSeconds(10));

    assertThat(prompts).hasSize(2);
    List<org.springframework.ai.chat.messages.Message> corrective =
        prompts.get(1).getInstructions();
    assertThat(corrective.get(corrective.size() - 1).getText())
        .isEqualTo(ToolCallingLoop.ANNOUNCED_NOTE);
    assertThat(turnLine()).contains("corrections=announce");
  }

  @Test
  void firstReasoningFragmentIsLoggedApartFromTheFirstText() {
    model(() -> Flux.just(
        new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
            .properties(Map.of("reasoningContent", "The user wants a lamp.")).build()))),
        ChatTurnServiceTest.fragment("Here is a lamp.")));

    service().open("s1", "hi").blockLast(Duration.ofSeconds(10));

    assertThat(turnLine()).containsPattern("firstReasoningMs=\\d+")
        .containsPattern("firstFragmentMs=\\d+").contains("reasoningChars=22");
  }

  @Test
  void aModelThatIgnoresRequiredIsLoggedAndTheGuardStillHolds() {
    ListAppender<ILoggingEvent> loopLog = new ListAppender<>();
    Logger loopLogger = (Logger) LoggerFactory.getLogger(ToolCallingLoop.class);
    loopLog.start();
    loopLogger.addAppender(loopLog);
    try {
      model(() -> Flux.just(ChatTurnServiceTest.fragment("The lamp has been added to your cart.")),
          () -> Flux.just(ChatTurnServiceTest.fragment("Hello! How can I help you today?")));

      List<ServerSentEvent<?>> events = service().open("s1", "add the lamp to my cart")
          .collectList().block(Duration.ofSeconds(10));

      assertThat(options(prompts.get(1)).getToolChoice()).isEqualTo("required");
      assertThat(texts(events)).doesNotContain("has been added")
          .endsWith(ToolCallingLoop.NOT_ADDED_TEXT);
      assertThat(loopLog.list).extracting(ILoggingEvent::getFormattedMessage)
          .anyMatch(message -> message.contains("ignoró tool_choice required"));
    } finally {
      loopLogger.detachAppender(loopLog);
    }
  }

  @Test
  void claimInTheLastPossibleRoundEndsWithTheNoticeWithoutACorrectiveRound() {
    model(() -> Flux.just(toolCall("getProductDetails", "{\"productId\":\"" + LAMP.id() + "\"}")),
        () -> Flux.just(toolCall("getProductDetails", "{\"productId\":\"" + LAMP.id() + "\"}")),
        () -> Flux.just(toolCall("getProductDetails", "{\"productId\":\"" + LAMP.id() + "\"}")),
        () -> Flux.just(ChatTurnServiceTest.fragment("The lamp has been added to your cart.")));

    List<ServerSentEvent<?>> events = service().open("s1", "add the lamp")
        .collectList().block(Duration.ofSeconds(10));

    assertThat(prompts).hasSize(4);
    assertThat(texts(events)).isEqualTo(ToolCallingLoop.NOT_ADDED_TEXT);
    assertThat(turnLine()).contains("claimGuard=dropped:1+notice");
  }

  @Test
  void legitimateCartTextWithoutAddToCartIsNotTouched() {
    model(() -> Flux.just(ChatTurnServiceTest.fragment("Which lamp should I add to your cart? "),
        ChatTurnServiceTest.fragment("You can also add it from the product page.")));

    List<ServerSentEvent<?>> events = service().open("s1", "add the lamp")
        .collectList().block(Duration.ofSeconds(10));

    assertThat(texts(events)).isEqualTo("Which lamp should I add to your cart? You can also add "
        + "it from the product page.");
    assertThat(prompts).hasSize(1);
    assertThat(turnLine()).contains("claimGuard=-", "corrections=-");
  }

  /**
   * Regresión de la sesión {@code 9952aae8} del reporte: después de un agregado
   * correcto, el historial del turno siguiente muestra el tool call
   * {@code addToCart} y su resultado, no solo el texto "added".
   */
  @Test
  void historyOfASecondAddShowsTheFirstAddToCartCall() {
    when(carts.getCart("s1")).thenReturn(new CartsClient.Cart("s1",
        List.of(new CartsClient.Item(LAMP.id(), 2, 45))));
    model(() -> Flux.just(toolCall("addToCart",
            "{\"productId\":\"" + LAMP.id() + "\",\"quantity\":2}")),
        () -> Flux.just(ChatTurnServiceTest.fragment("Two lamps added to your cart.")),
        () -> Flux.just(ChatTurnServiceTest.fragment("Which one?")));

    service().open("s1", "add two of the first one to my cart").blockLast(Duration.ofSeconds(10));
    service().open("s1", "add one more lamp too").blockLast(Duration.ofSeconds(10));

    List<org.springframework.ai.chat.messages.Message> history = prompts.get(2).getInstructions();
    assertThat(history).extracting(m -> m.getMessageType())
        .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT,
            MessageType.TOOL, MessageType.ASSISTANT, MessageType.USER);
    assertThat(((AssistantMessage) history.get(2)).getToolCalls()).singleElement()
        .satisfies(call -> assertThat(call.name()).isEqualTo("addToCart"));
    assertThat(((ToolResponseMessage) history.get(3)).getResponses()).singleElement()
        .satisfies(response -> assertThat(response.responseData()).isEqualTo(
            "{\"added\":{\"id\":\"" + LAMP.id() + "\",\"name\":\"Ceramic Table Lamp\","
                + "\"quantity\":2,\"unitPrice\":45},\"cartItemCount\":2}"));
    assertThat(history.get(4).getText()).isEqualTo("Two lamps added to your cart.");
  }

  /**
   * Regresión de la segunda corrida de {@code MultiTurnCartSmokeIT}: la respuesta
   * termina anunciando la búsqueda ("Let me check our inventory…") sin pedir la
   * tool. La vuelta correctiva la pide y el turno termina con el agregado.
   */
  @Test
  void replyThatEndsAnnouncingAnActionGetsACorrectiveRound() {
    when(carts.getCart("s1")).thenReturn(new CartsClient.Cart("s1",
        List.of(new CartsClient.Item(LAMP.id(), 1, 45))));
    model(() -> Flux.just(ChatTurnServiceTest.fragment(
            "I need to check the current details for the Ceramic Table Lamp first.")),
        () -> Flux.just(toolCall("addToCart", "{\"productId\":\"" + LAMP.id() + "\"}")),
        () -> Flux.just(ChatTurnServiceTest.fragment("Done: one lamp added to your cart.")));

    List<ServerSentEvent<?>> events = service().open("s1", "add one ceramic lamp too")
        .collectList().block(Duration.ofSeconds(10));

    assertThat(texts(events)).isEqualTo("I need to check the current details for the Ceramic "
        + "Table Lamp first. Done: one lamp added to your cart.");
    assertThat(events).filteredOn(event -> isEvent(event, "cart-updated")).hasSize(1);
    List<org.springframework.ai.chat.messages.Message> corrective =
        prompts.get(1).getInstructions();
    assertThat(corrective.get(corrective.size() - 1).getText())
        .isEqualTo(ToolCallingLoop.ANNOUNCED_NOTE);
    assertThat(turnLine()).contains("tools=addToCart:ok", "claimGuard=-",
        "corrections=announce");
  }

  /**
   * Regresión de la quinta corrida de {@code MultiTurnCartSmokeIT}: el modelo
   * escribió {@code [addToCart: {…}]} como texto, sin tool calls. Se ejecuta como
   * tool call y el JSON no llega al usuario.
   */
  @Test
  void toolCallWrittenAsTextIsExecutedAndNotShown() {
    when(carts.getCart("s1")).thenReturn(new CartsClient.Cart("s1",
        List.of(new CartsClient.Item(LAMP.id(), 2, 45))));
    model(() -> Flux.just(ChatTurnServiceTest.fragment("On it, Operative.\n"),
            ChatTurnServiceTest.fragment("[addToCart: {\"productId\": \"" + LAMP.id()
                + "\", \"quantity\": 2}]")),
        () -> Flux.just(ChatTurnServiceTest.fragment("Two lamps added to your cart.")));

    List<ServerSentEvent<?>> events = service().open("s1", "add two of the first one")
        .collectList().block(Duration.ofSeconds(10));

    assertThat(texts(events)).isEqualTo("On it, Operative.\nTwo lamps added to your cart.");
    assertThat(events).filteredOn(event -> isEvent(event, "cart-updated")).hasSize(1);
    verify(carts).addItem("s1", LAMP.id(), 2, 45);
    assertThat(turnLine()).contains("tools=addToCart:ok", "claimGuard=-",
        "corrections=textual");
    assertThat(sessions.get("s1").turns().get(0).toolRounds()).singleElement()
        .satisfies(round -> assertThat(round.calls()).extracting(ToolRound.Call::name)
            .containsExactly("addToCart"));
  }

  @Test
  void correctiveRoundWithoutACartRequestDoesNotForceATool() {
    model(() -> Flux.just(ChatTurnServiceTest.fragment("Two lamps have been added to your cart.")),
        () -> Flux.just(ChatTurnServiceTest.fragment("Here are some lamps, Operative.")));

    service().open("s1", "show me some lamps").collectList().block(Duration.ofSeconds(10));

    assertThat(prompts).hasSize(2);
    assertThat(options(prompts.get(1)).getToolChoice()).isNull();
    assertThat(turnLine()).contains("corrections=claim ", "claimGuard=dropped:1+retry+notice");
  }

  @Test
  void cartRequests() {
    assertThat(ToolCallingLoop.isCartRequest("add two of the first one to my cart")).isTrue();
    assertThat(ToolCallingLoop.isCartRequest("Add one Adjustable Pharmacy Desk Lamp to my cart "
        + "too")).isTrue();
    assertThat(ToolCallingLoop.isCartRequest("agregá dos lámparas al carrito")).isTrue();
    assertThat(ToolCallingLoop.isCartRequest("show me some lamps")).isFalse();
    assertThat(ToolCallingLoop.isCartRequest("what's in my cart?")).isFalse();
    assertThat(ToolCallingLoop.isCartRequest(null)).isFalse();
  }

  /**
   * Regresión de la octava corrida de {@code MultiTurnCartSmokeIT}: el modelo pide
   * una aclaración y a la vez afirma un agregado. La vuelta correctiva no se fuerza,
   * para que pueda preguntar en lugar de elegir.
   */
  @Test
  void correctiveRoundIsNotForcedWhenTheModelIsAskingTheUser() {
    model(() -> Flux.just(ChatTurnServiceTest.fragment("Let me clarify which lamp you mean. "),
            ChatTurnServiceTest.fragment("Two lamps have been added to your cart.")),
        () -> Flux.just(ChatTurnServiceTest.fragment("Which of the three lamps do you want?")));

    List<ServerSentEvent<?>> events = service().open("s1", "add two of them to my cart")
        .collectList().block(Duration.ofSeconds(10));

    assertThat(options(prompts.get(1)).getToolChoice()).isNull();
    assertThat(texts(events)).startsWith("Let me clarify which lamp you mean. Which of the three")
        .endsWith(ToolCallingLoop.NOT_ADDED_TEXT);
    verify(carts, never()).addItem(any(), any(), anyInt(), anyInt());
    assertThat(turnLine()).contains("corrections=claim ");
  }
}
