package com.amazon.sample.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazon.sample.assistant.chat.context.ContextRetriever;
import com.amazon.sample.assistant.chat.context.Retrieval;
import com.amazon.sample.assistant.chat.rewrite.Intent;
import com.amazon.sample.assistant.chat.rewrite.QueryRewriter;
import com.amazon.sample.assistant.chat.rewrite.Rewrite;
import com.amazon.sample.assistant.chat.session.SessionStore;
import com.amazon.sample.assistant.chat.session.ShownProduct;
import com.amazon.sample.assistant.chat.session.Turn;
import com.amazon.sample.assistant.config.ChatProperties;
import com.amazon.sample.assistant.config.ChatProperties.ReasoningMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Pipeline de un turno (D2): orden de eventos, streaming, keepalive, cancelación y memoria. */
class ChatTurnServiceTest {

  private static final ShownProduct ARMCHAIR = new ShownProduct("a1",
      "Aiden Mid-Century Velvet Armchair", "Plush velvet armchair.", 139,
      List.of("seating", "velvet"));

  private final ChatModel chatModel = mock(ChatModel.class);
  private final QueryRewriter rewriter = mock(QueryRewriter.class);
  private final ContextRetriever retriever = mock(ContextRetriever.class);
  private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
  private SessionStore sessions;
  private ChatTurnService service;

  static ChatProperties.Chat properties(boolean compareRaw) {
    return new ChatProperties.Chat(5, 0, 1024, 4096, compareRaw,
        new ChatProperties.Reasoning(ReasoningMode.AUTO, null, null, false),
        new ChatProperties.Memory(10, Duration.ofMinutes(30), 10_000),
        new ChatProperties.Timeouts(Duration.ofSeconds(60), Duration.ofSeconds(20),
            Duration.ofSeconds(120), Duration.ofSeconds(10)));
  }

  static ChatResponse fragment(String text) {
    return reasoning(text, "");
  }

  static ChatResponse reasoning(String text, String reasoning) {
    return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text)
        .properties(Map.of("reasoningContent", reasoning)).build())));
  }

  @BeforeEach
  void setUp() {
    sessions = new SessionStore(10, Duration.ofMinutes(30), 100);
    ChatProperties.Chat chat = properties(false);
    service = ChatTestSupport.service(sessions, rewriter, retriever, chatModel, chat);
    when(rewriter.rewrite(any(), any())).thenReturn(
        new Rewrite(Intent.SEARCH, "velvet armchair", null, null, List.of(), false, 1, 5));
    when(retriever.retrieve(any(), any())).thenReturn(
        new Retrieval(true, false, List.of(ARMCHAIR), List.of(), false, 3));
  }

  /**
   * Respuesta del modelo. Se arma recién cuando el turno llama al modelo, para
   * que sus demoras usen el scheduler virtual de {@code StepVerifier}.
   */
  private void model(Supplier<Flux<ChatResponse>> responses) {
    when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
      prompts.add(invocation.getArgument(0));
      return Flux.defer(responses);
    });
  }

  private static boolean isProducts(ServerSentEvent<?> event) {
    return "products".equals(event.event());
  }

  private static boolean isText(ServerSentEvent<?> event, String text) {
    return event.event() == null && event.comment() == null
        && Map.of("text", text).equals(event.data());
  }

  private static boolean isKeepalive(ServerSentEvent<?> event) {
    return "keepalive".equals(event.comment()) && event.data() == null;
  }

  @Test
  void emitsProductsThenFragmentsThenDone() {
    model(() -> Flux.just(fragment("Ah, Operative."), fragment(" The Aiden is $139."))
        .delayElements(Duration.ofSeconds(1)));

    StepVerifier.withVirtualTime(() -> service.open("s1", "a velvet armchair"))
        .expectNextMatches(event -> isProducts(event)
            && event.data().equals(List.of(new ChatTurnService.ProductEvent("a1",
                "Aiden Mid-Century Velvet Armchair", 139))))
        .thenAwait(Duration.ofSeconds(1))
        .expectNextMatches(event -> isText(event, "Ah, Operative."))
        .thenAwait(Duration.ofSeconds(1))
        .expectNextMatches(event -> isText(event, " The Aiden is $139."))
        .expectNextMatches(event -> "done".equals(event.event()))
        .verifyComplete();

    assertThat(sessions.get("s1").turns())
        .containsExactly(new Turn("a velvet armchair", "Ah, Operative. The Aiden is $139."));
    assertThat(sessions.get("s1").lastProducts()).containsExactly(ARMCHAIR);
    assertThat(sessions.get("s1").isBusy()).isFalse();
  }

  @Test
  void firstFragmentArrivesBeforeTheModelFinishes() {
    // El segundo fragmento llega 25 s después del primero.
    model(() -> Flux.concat(Flux.just(fragment("First sentence.")).delayElements(Duration.ofSeconds(1)),
        Flux.just(fragment(" Second sentence.")).delayElements(Duration.ofSeconds(25))));

    StepVerifier.withVirtualTime(() -> service.open("s1", "tell me more"))
        .expectNextMatches(ChatTurnServiceTest::isProducts)
        .thenAwait(Duration.ofSeconds(1))
        .expectNextMatches(event -> isText(event, "First sentence."))
        // Todavía no terminó: hasta el segundo fragmento solo hay keepalives.
        .thenAwait(Duration.ofSeconds(10))
        .expectNextMatches(ChatTurnServiceTest::isKeepalive)
        .thenAwait(Duration.ofSeconds(10))
        .expectNextMatches(ChatTurnServiceTest::isKeepalive)
        .thenAwait(Duration.ofSeconds(5))
        .expectNextMatches(event -> isText(event, " Second sentence."))
        .expectNextMatches(event -> "done".equals(event.event()))
        .verifyComplete();
  }

  @Test
  void keepaliveEveryTenSecondsWithoutEvents() {
    // Razonando: 15 s sin fragmentos (dentro del límite de 20 s).
    model(() -> Flux.just(fragment("Done thinking.")).delayElements(Duration.ofSeconds(15)));

    StepVerifier.withVirtualTime(() -> service.open("s1", "a velvet armchair"))
        .expectNextMatches(ChatTurnServiceTest::isProducts)
        .expectNoEvent(Duration.ofSeconds(10).minusMillis(1))
        .thenAwait(Duration.ofMillis(1))
        .expectNextMatches(ChatTurnServiceTest::isKeepalive)
        .thenAwait(Duration.ofSeconds(5))
        .expectNextMatches(event -> isText(event, "Done thinking."))
        .expectNextMatches(event -> "done".equals(event.event()))
        .verifyComplete();
  }

  @Test
  void cancellationReleasesTheLockAndDoesNotStoreTheTurn() {
    AtomicBoolean modelCancelled = new AtomicBoolean();
    model(() -> Flux.concat(Flux.just(fragment("Partial")).delayElements(Duration.ofSeconds(1)),
            Flux.<ChatResponse>never())
        .doOnCancel(() -> modelCancelled.set(true)));

    StepVerifier.withVirtualTime(() -> service.open("s1", "a velvet armchair"))
        .expectNextMatches(ChatTurnServiceTest::isProducts)
        .thenAwait(Duration.ofSeconds(1))
        .expectNextMatches(event -> isText(event, "Partial"))
        .thenCancel()
        .verify();

    assertThat(modelCancelled).isTrue();
    assertThat(sessions.get("s1").isBusy()).isFalse();
    assertThat(sessions.get("s1").turns()).isEmpty();
    assertThat(sessions.get("s1").lastProducts()).isEmpty();
  }

  @Test
  void busySessionIsRejectedAndOtherSessionsAreNot() {
    model(() -> Flux.never());

    service.open("s1", "first").subscribe();

    assertThatThrownBy(() -> service.open("s1", "second"))
        .isInstanceOf(SessionBusyException.class);
    assertThat(service.open("s2", "other session")).isNotNull();
  }

  @Test
  void storedHistoryHasNoReasoningNorContext() {
    model(() -> Flux.just(reasoning("", "The user wants a chair; compare prices first."),
        reasoning("", " Aiden is cheaper."), fragment("The Aiden is $139.")));

    StepVerifier.create(service.open("s1", "compare them"))
        .expectNextMatches(ChatTurnServiceTest::isProducts)
        .expectNextMatches(event -> isText(event, "The Aiden is $139."))
        .expectNextMatches(event -> "done".equals(event.event()))
        .verifyComplete();

    Turn stored = sessions.get("s1").turns().get(0);
    assertThat(stored).isEqualTo(new Turn("compare them", "The Aiden is $139."));
    assertThat(stored.assistant()).doesNotContain("compare prices", "PRODUCT CONTEXT", "[a1]");
  }

  @Test
  void historyAndContextGoToTheModel() {
    model(() -> Flux.just(fragment("Here.")));
    StepVerifier.create(service.open("s1", "a velvet armchair")).thenConsumeWhile(e -> true)
        .verifyComplete();
    StepVerifier.create(service.open("s1", "cheaper")).thenConsumeWhile(e -> true)
        .verifyComplete();

    Prompt second = prompts.get(1);
    assertThat(second.getInstructions()).extracting(m -> m.getMessageType())
        .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT,
            MessageType.USER);
    assertThat(second.getInstructions().get(0).getText())
        .contains("You are A.G.E.N.T.")
        .contains("[a1] Aiden Mid-Century Velvet Armchair | $139");
    assertThat(second.getInstructions().get(1).getText()).isEqualTo("a velvet armchair");
    assertThat(second.getInstructions().get(2).getText()).isEqualTo("Here.");
    assertThat(second.getInstructions().get(3).getText()).isEqualTo("cheaper");
  }

  @Test
  void greetingEmitsEmptyProductsAndKeepsPreviousProducts() {
    sessions.get("s1").commit(new Turn("armchairs", "here"), List.of(ARMCHAIR));
    when(rewriter.rewrite(any(), any())).thenReturn(
        new Rewrite(Intent.OTHER, "", null, null, List.of(), false, 1, 5));
    when(retriever.retrieve(any(), any())).thenReturn(
        new Retrieval(false, false, List.of(), List.of(ARMCHAIR), false, 0));
    model(() -> Flux.just(fragment("Greetings, Operative.")));

    StepVerifier.create(service.open("s1", "thanks!"))
        .expectNextMatches(event -> isProducts(event) && ((List<?>) event.data()).isEmpty())
        .expectNextMatches(event -> isText(event, "Greetings, Operative."))
        .expectNextMatches(event -> "done".equals(event.event()))
        .verifyComplete();

    assertThat(sessions.get("s1").lastProducts()).containsExactly(ARMCHAIR);
  }

  private static boolean isError(ServerSentEvent<?> event, String type) {
    return "error".equals(event.event()) && event.data() instanceof Map<?, ?> data
        && type.equals(data.get("type")) && data.get("detail") instanceof String;
  }

  @Test
  void quotaErrorEndsWithErrorEventAndRetryAfterWithoutStoringTheTurn() {
    // add-assistant-tools (D9): el 429 se reintenta 2 veces respetando el
    // Retry-After; si persiste, el turno termina como antes.
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.RETRY_AFTER, "12");
    model(() -> Flux.error(WebClientResponseException.create(429, "Too Many Requests", headers,
        new byte[0], null)));

    StepVerifier.withVirtualTime(() -> service.open("s1", "a velvet armchair"))
        .expectNextMatches(ChatTurnServiceTest::isProducts)
        .thenAwait(Duration.ofSeconds(24))
        .thenConsumeWhile(ChatTurnServiceTest::isKeepalive)
        .expectNextMatches(event -> isError(event, "llm-quota-exceeded")
            && Long.valueOf(12).equals(((Map<?, ?>) event.data()).get("retryAfterSeconds")))
        .verifyComplete();

    assertThat(prompts).hasSize(3);
    assertThat(sessions.get("s1").turns()).isEmpty();
    assertThat(sessions.get("s1").isBusy()).isFalse();
  }

  @Test
  void unauthorizedErrorAfterSomeFragmentsDoesNotStoreTheTurn() {
    model(() -> Flux.concat(Flux.just(fragment("Partial")), Flux.error(
        WebClientResponseException.create(401, "Unauthorized", new HttpHeaders(), new byte[0],
            null))));

    StepVerifier.create(service.open("s1", "a velvet armchair"))
        .expectNextMatches(ChatTurnServiceTest::isProducts)
        .expectNextMatches(event -> isText(event, "Partial"))
        .expectNextMatches(event -> isError(event, "llm-provider-unauthorized")
            && !((Map<?, ?>) event.data()).containsKey("retryAfterSeconds"))
        .verifyComplete();

    assertThat(sessions.get("s1").turns()).isEmpty();
  }

  @Test
  void serverErrorIsUnavailable() {
    model(() -> Flux.error(WebClientResponseException.create(503, "Unavailable",
        new HttpHeaders(), new byte[0], null)));

    StepVerifier.create(service.open("s1", "a velvet armchair"))
        .expectNextMatches(ChatTurnServiceTest::isProducts)
        .expectNextMatches(event -> isError(event, "llm-provider-unavailable"))
        .verifyComplete();
  }

  @Test
  void firstFragmentTimeoutWithoutReasoningIs20Seconds() {
    model(() -> Flux.just(fragment("too late")).delayElements(Duration.ofSeconds(25)));

    StepVerifier.withVirtualTime(() -> service.open("s1", "a velvet armchair"))
        .expectNextMatches(ChatTurnServiceTest::isProducts)
        .thenAwait(Duration.ofSeconds(10))
        .expectNextMatches(ChatTurnServiceTest::isKeepalive)
        .expectNoEvent(Duration.ofSeconds(10).minusMillis(1))
        .thenAwait(Duration.ofMillis(1))
        .expectNextMatches(event -> isError(event, "llm-provider-unavailable"))
        .verifyComplete();

    assertThat(sessions.get("s1").turns()).isEmpty();
    assertThat(sessions.get("s1").isBusy()).isFalse();
  }

  @Test
  void firstFragmentTimeoutWithReasoningIs60Seconds() {
    when(rewriter.rewrite(any(), any())).thenReturn(
        new Rewrite(Intent.COMPARE, "armchairs", null, null, List.of(), false, 1, 5));
    model(() -> Flux.just(fragment("Comparison.")).delayElements(Duration.ofSeconds(45)));

    // Con razonamiento, 45 s hasta el primer fragmento está dentro del límite.
    StepVerifier.withVirtualTime(() -> service.open("s1", "compare the first two"))
        .expectNextMatches(ChatTurnServiceTest::isProducts)
        .thenAwait(Duration.ofSeconds(45))
        .expectNextCount(4)
        .expectNextMatches(event -> isText(event, "Comparison."))
        .expectNextMatches(event -> "done".equals(event.event()))
        .verifyComplete();

    model(() -> Flux.just(fragment("Comparison.")).delayElements(Duration.ofSeconds(65)));
    StepVerifier.withVirtualTime(() -> service.open("s2", "compare the first two"))
        .expectNextMatches(ChatTurnServiceTest::isProducts)
        .thenAwait(Duration.ofSeconds(60))
        // Keepalives a los 10, 20, 30, 40 y 50 s; a los 60 s, el error.
        .expectNextCount(5)
        .expectNextMatches(event -> isError(event, "llm-provider-unavailable"))
        .verifyComplete();
  }

  @Test
  void turnTimeoutIs120Seconds() {
    // Un fragmento cada 15 s: nunca se excede el límite del primero, pero sí el del turno.
    model(() -> Flux.interval(Duration.ofSeconds(15)).map(i -> fragment("tick ")));

    StepVerifier.withVirtualTime(() -> service.open("s1", "a velvet armchair"))
        .expectNextMatches(ChatTurnServiceTest::isProducts)
        .thenAwait(Duration.ofSeconds(120))
        .thenConsumeWhile(event -> !"error".equals(event.event()),
            event -> assertThat(isText(event, "tick ") || isKeepalive(event)).isTrue())
        .expectNextMatches(event -> isError(event, "llm-provider-unavailable"))
        .verifyComplete();

    assertThat(sessions.get("s1").turns()).isEmpty();
    assertThat(sessions.get("s1").isBusy()).isFalse();
  }
}
