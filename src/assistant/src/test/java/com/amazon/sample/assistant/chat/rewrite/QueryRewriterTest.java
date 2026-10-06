package com.amazon.sample.assistant.chat.rewrite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazon.sample.assistant.chat.llm.ChatRateLimiter;
import com.amazon.sample.assistant.chat.llm.MutableClock;
import com.amazon.sample.assistant.chat.session.SessionState;
import com.amazon.sample.assistant.chat.session.ShownProduct;
import com.amazon.sample.assistant.chat.session.Turn;
import com.amazon.sample.assistant.config.ChatProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;

/** Prompt, salida estructurada, validación y fallback de la reescritura (D4). */
class QueryRewriterTest {

  private static final String RAW = "hey, got anything comfy to sink into?";

  private final ChatModel chatModel = mock(ChatModel.class);
  private final CatalogTagsCache tags = mock(CatalogTagsCache.class);
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-06T12:00:00Z"));
  private final ChatRateLimiter limiter = new ChatRateLimiter(36, Duration.ofSeconds(5), clock);
  private SessionState session;

  @BeforeEach
  void setUp() {
    when(tags.tagNames()).thenReturn(List.of("seating", "lighting", "velvet", "leather"));
    session = new SessionState(10);
  }

  private QueryRewriter rewriter(Duration timeout) {
    return new QueryRewriter(ChatClient.builder(chatModel).build(), tags,
        new ChatProperties.Rewrite(timeout, 3, null), new ClassPathResource("prompts/rewrite.st"),
        limiter);
  }

  private void respond(String json) {
    when(chatModel.call(any(Prompt.class))).thenReturn(
        new ChatResponse(List.of(new Generation(new AssistantMessage(json)))));
  }

  @Test
  void promptContainsTruncatedHistoryPreviousProductsAndTags() {
    session.commit(new Turn("oldest question", "oldest answer"), null);
    session.commit(new Turn("q2", "a2"), null);
    session.commit(new Turn("q3", "a3"), null);
    String longAnswer = "x".repeat(499) + "TAIL-THAT-IS-CUT" + "y".repeat(100);
    session.commit(new Turn("velvet armchair please", longAnswer),
        List.of(new ShownProduct("p1", "Aiden Mid-Century Velvet Armchair", "desc", 139,
            List.of("seating", "velvet"))));
    respond("""
        {"intent":"search","query":"leather armchair","minPrice":null,"maxPrice":138,
         "excludeTags":["lighting"]}""");

    Rewrite rewrite = rewriter(Duration.ofSeconds(5)).rewrite("cheaper, in leather", session);

    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(captor.capture());
    Prompt sent = captor.getValue();
    assertThat(sent.getInstructions().get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
    assertThat(sent.getUserMessage().getText()).startsWith("cheaper, in leather");
    String prompt = sent.getContents();
    assertThat(prompt).contains("User: velvet armchair please")
        .contains("x".repeat(499))
        .doesNotContain("TAIL-THAT-IS-CUT")
        .doesNotContain("oldest question")
        .contains("q2", "q3")
        .contains("Aiden Mid-Century Velvet Armchair | $139 | tags: seating, velvet")
        .contains("Catalog tags: seating, lighting, velvet, leather")
        .contains("cheaper, in leather");

    assertThat(rewrite.fallback()).isFalse();
    assertThat(rewrite.intent()).isEqualTo(Intent.SEARCH);
    assertThat(rewrite.query()).isEqualTo("leather armchair");
    assertThat(rewrite.minPrice()).isNull();
    assertThat(rewrite.maxPrice()).isEqualTo(138);
    assertThat(rewrite.excludeTags()).containsExactly("lighting");
    assertThat(rewrite.providerRequests()).isEqualTo(1);
  }

  @Test
  void greetingIsOtherWithoutQuery() {
    respond("""
        {"intent":"other","query":"","minPrice":null,"maxPrice":null,"excludeTags":[]}""");

    Rewrite rewrite = rewriter(Duration.ofSeconds(5)).rewrite("hi there!", session);

    assertThat(rewrite.fallback()).isFalse();
    assertThat(rewrite.intent()).isEqualTo(Intent.OTHER);
  }

  @Test
  void emptyQueryFallsBack() {
    assertFallback("""
        {"intent":"search","query":"  ","minPrice":null,"maxPrice":null,"excludeTags":[]}""");
  }

  @Test
  void tooLongQueryFallsBack() {
    assertFallback("{\"intent\":\"search\",\"query\":\"" + "a".repeat(201)
        + "\",\"excludeTags\":[]}");
  }

  @Test
  void negativePriceFallsBack() {
    assertFallback("""
        {"intent":"search","query":"armchair","minPrice":-1,"maxPrice":null,"excludeTags":[]}""");
  }

  @Test
  void invertedPriceRangeFallsBack() {
    assertFallback("""
        {"intent":"search","query":"armchair","minPrice":300,"maxPrice":100,"excludeTags":[]}""");
  }

  @Test
  void unknownExcludedTagFallsBack() {
    assertFallback("""
        {"intent":"search","query":"armchair","excludeTags":["vehicles"]}""");
  }

  @Test
  void unknownIntentFallsBack() {
    assertFallback("""
        {"intent":"buy","query":"armchair","excludeTags":[]}""");
  }

  @Test
  void invalidJsonFallsBack() {
    assertFallback("Sure! Here is a comfy armchair query: comfy armchair");
  }

  @Test
  void providerErrorFallsBack() {
    when(chatModel.call(any(Prompt.class)))
        .thenThrow(new NonTransientAiException("401 - Unauthorized"));

    assertIsFallback(rewriter(Duration.ofSeconds(5)).rewrite(RAW, session));
  }

  @Test
  void slowModelFallsBackWithinTheTimeout() {
    when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
      Thread.sleep(5_000);
      return new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))));
    });
    Duration timeout = Duration.ofMillis(300);

    long start = System.nanoTime();
    Rewrite rewrite = rewriter(timeout).rewrite(RAW, session);
    long elapsed = (System.nanoTime() - start) / 1_000_000;

    assertIsFallback(rewrite);
    assertThat(elapsed).isLessThan(timeout.toMillis() + 1_000);
  }

  @Test
  void excludedTagsAreIgnoredWhenTheTagListIsUnavailable() {
    when(tags.tagNames()).thenReturn(List.of());
    respond("""
        {"intent":"search","query":"reading nook","excludeTags":["lighting"]}""");

    Rewrite rewrite = rewriter(Duration.ofSeconds(5)).rewrite("not a lamp", session);

    assertThat(rewrite.fallback()).isFalse();
    assertThat(rewrite.query()).isEqualTo("reading nook");
    assertThat(rewrite.excludeTags()).isEmpty();
  }

  private void assertFallback(String json) {
    respond(json);
    assertIsFallback(rewriter(Duration.ofSeconds(5)).rewrite(RAW, session));
  }

  private static void assertIsFallback(Rewrite rewrite) {
    assertThat(rewrite.fallback()).isTrue();
    assertThat(rewrite.intent()).isEqualTo(Intent.SEARCH);
    assertThat(rewrite.query()).isEqualTo(RAW);
    assertThat(rewrite.minPrice()).isNull();
    assertThat(rewrite.maxPrice()).isNull();
    assertThat(rewrite.excludeTags()).isEmpty();
  }

  @Test
  void fullLimiterSkipsTheModelAndUsesTheRawMessage() {
    for (int i = 0; i < 36; i++) {
      limiter.reserve(Duration.ZERO);
    }

    Rewrite rewrite = rewriter(Duration.ofSeconds(5)).rewrite(RAW, session);

    verify(chatModel, never()).call(any(Prompt.class));
    assertThat(rewrite.query()).isEqualTo(RAW);
    assertThat(rewrite.fallback()).isTrue();
    assertThat(rewrite.rateLimited()).isTrue();
    assertThat(rewrite.providerRequests()).isZero();
  }

  @Test
  void rewriteReservesItsPlaceInTheLimiter() {
    respond("""
        {"intent":"search","query":"armchair","minPrice":null,"maxPrice":null,"excludeTags":[]}""");

    rewriter(Duration.ofSeconds(5)).rewrite(RAW, session);

    assertThat(limiter.windowCount()).isEqualTo(1);
  }

  @Test
  void quotaErrorWithRetryAfterPausesTheLimiterAndFallsBack() {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.RETRY_AFTER, "7");
    when(chatModel.call(any(Prompt.class))).thenThrow(HttpClientErrorException.create(
        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", headers,
        new byte[0], null));

    Rewrite rewrite = rewriter(Duration.ofSeconds(5)).rewrite(RAW, session);

    assertIsFallback(rewrite);
    assertThat(rewrite.rateLimited()).isFalse();
    // Todas las sesiones esperan los 7 s del Retry-After.
    assertThat(limiter.reserve(Duration.ofSeconds(30)).delay()).isEqualTo(Duration.ofSeconds(7));
  }

  @Test
  void quotaErrorWithoutRetryAfterPausesForTheDefault() {
    when(chatModel.call(any(Prompt.class))).thenThrow(
        new NonTransientAiException("429 - {\"status\":429,\"title\":\"Too Many Requests\"}"));

    assertIsFallback(rewriter(Duration.ofSeconds(5)).rewrite(RAW, session));
    assertThat(limiter.reserve(Duration.ofSeconds(30)).delay()).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void promptClassifiesCartRequestsAndPriceQuestionsAsOther() {
    String prompt = rewriter(Duration.ofSeconds(5)).renderPrompt(session, List.of("seating"));

    // add-assistant-tools (D10): sin búsqueda ni embedding para estos pedidos.
    assertThat(prompt).contains("Requests to add a product to the cart")
        .contains("A question about the current price or details of a product is \"other\" only "
            + "if that product is\n  in the list of products shown in the previous turn")
        .contains("If the list is \"(none)\" or the product is not in it")
        .contains("\"add that one to my cart\" -> intent other, empty")
        .doesNotContainPattern("\\{[a-zA-Z_]+\\}");
  }
}
