package com.amazon.sample.assistant.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.assistant.chat.llm.ChatProviderErrors;
import com.amazon.sample.assistant.chat.llm.ChatProviderException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import reactor.core.publisher.Flux;

/**
 * Spike de modelos (D12): verifica contra NVIDIA, con Spring AI 1.1.8, lo que
 * el pipeline de chat da por hecho.
 *
 * <p>Modelo principal: streaming con {@code ChatClient.stream()}, razonamiento
 * activado y desactivado por request con {@code extraBody}, dónde llega el
 * razonamiento ({@code reasoning_content} o {@code <think>} en el texto) y tool
 * calling en streaming. Modelo de reescritura: 10 llamadas con el razonamiento
 * desactivado que tienen que devolver el JSON de D4, y su latencia mediana.
 *
 * <p>Los modelos y sus {@code extra-body} se eligen con propiedades de sistema,
 * para repetir el spike con el plan B sin tocar el código:
 * <pre>
 * ./mvnw -Psmoke verify -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dit.test=ModelSpikeSmokeIT \
 *   -Dspike.main-model=deepseek-ai/deepseek-v4.1-flash \
 *   -Dspike.main-on='{"chat_template_kwargs":{"thinking":true}}' \
 *   -Dspike.main-off='{"chat_template_kwargs":{"thinking":false}}' \
 *   -Dspike.rewrite-model=google/gemma-3-12b-it -Dspike.rewrite-extra='{}'
 * </pre>
 *
 * <p>Gasta 15 requests a NVIDIA: 1 sin razonamiento, 1 con razonamiento, 2 del
 * tool calling (la llamada con la tool y la respuesta final) y 10 de reescritura.
 *
 * <p>{@code add-assistant-tools} (tasks 1.1 y 1.2) suma dos casos que se corren
 * aparte, porque uno agota la cuota a propósito:
 * <ul>
 *   <li>{@code controlledToolCalling} (D1): tool calling con
 *       {@code internalToolExecutionEnabled=false} en streaming, ejecución con
 *       {@link ToolCallingManager} y una vuelta con {@code tool_choice: "none"}
 *       (3 requests);</li>
 *   <li>{@code quotaBurst} (D9): una ráfaga de 45 requests mínimas al modelo de
 *       reescritura para forzar un 429 y ver qué trae, más 1 request en
 *       streaming que tiene que llegar como {@code ChatProviderException}
 *       {@code QUOTA}. Habilitado solo con {@code -Dspike.quota-burst=true}.</li>
 * </ul>
 * <pre>
 * ./mvnw -Psmoke verify -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dit.test='ModelSpikeSmokeIT#controlledToolCalling+quotaBurst' -Dspike.quota-burst=true
 * </pre>
 *
 * <p>{@code select-assistant-models} (D4 y D6) agrega:
 * <ul>
 *   <li>una línea {@code bench.capability} por capacidad (incluida una vuelta de
 *       {@code controlledToolCalling} con {@code tool_choice: "required"}, que
 *       suma 1 request), una {@code bench.rewrite} por llamada de reescritura y
 *       una {@code bench.usage} con las requests de la corrida, que lee
 *       {@code scripts/model_bench_report.py};</li>
 *   <li>{@code thinkingProbe}: cómo se apaga (o se prende) el razonamiento de un
 *       modelo nuevo, con HTTP crudo para ver {@code reasoning_content} tal como
 *       llega. Manda una request sin streaming por variante de
 *       {@code -Dspike.probe-variants} (un arreglo JSON de {@code extra-body}),
 *       en orden, y se frena en la primera que cumple el criterio de
 *       {@code -Dspike.probe-mode} ({@code off} u {@code on}). Cada request
 *       tiene un timeout de 30 s y un único reintento a los 60 s; un segundo
 *       timeout, un 404 o un 403 descartan el modelo. Solo corre con
 *       {@code -Dspike.probe-variants}.</li>
 * </ul>
 * <pre>
 * ./mvnw -Psmoke verify -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dit.test='ModelSpikeSmokeIT#thinkingProbe' -Dspike.probe-model=meta/muse-glimmer-30b \
 *   -Dspike.probe-mode=off -Dspike.probe-max-tokens=64 \
 *   -Dspike.probe-variants='[{"chat_template_kwargs":{"enable_thinking":false}}]'
 * </pre>
 */
@Tag("smoke")
@EnabledIfEnvironmentVariable(named = "NVIDIA_API_KEY", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = WebEnvironment.NONE, properties = {
    "spring.ai.retry.max-attempts=1",
    "spring.ai.vectorstore.qdrant.port=1"
})
class ModelSpikeSmokeIT {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String NEMOTRON_ON = "{\"chat_template_kwargs\":{\"enable_thinking\":true}}";
  private static final String NEMOTRON_OFF = "{\"chat_template_kwargs\":{\"enable_thinking\":false}}";

  /** Tiempo límite de la reescritura en el {@code assistant}: más lento cuenta como fallback. */
  private static final Duration REWRITE_TIMEOUT = Duration.ofSeconds(12);

  /** Timeout de descarte de {@code thinkingProbe} (D4) y pausa antes del reintento. */
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration PROBE_RETRY_PAUSE = Duration.ofSeconds(60);

  private static final String PROBE_PROMPT = "Reply with exactly: OK";

  /** Mensajes de la demo para la reescritura, con el turno previo simulado en algunos. */
  private static final List<String> REWRITE_MESSAGES = List.of(
      "hey, so my reading corner is kinda sad, got anything comfy to sink into?",
      "I need a lamp for my desk",
      "[previous turn: Aiden Mid-Century Velvet Armchair $139] something similar but in leather",
      "[previous turn: Aiden Mid-Century Velvet Armchair $139] cheaper",
      "[previous turn: brass floor lamp $189, oak reading chair $249] not a lamp",
      "hi there!",
      "busco una alfombra para el living",
      "[previous turn: two armchairs] compare the first two",
      "a rug for the living room, nothing too expensive pls",
      "smth to put my books on, like a shelf i guess");

  /** Salida de D4 que tiene que devolver la reescritura. */
  record SpikeRewrite(String intent, String query, Integer minPrice, Integer maxPrice,
      List<String> excludeTags) {
  }

  /** Tool de prueba: precio de un producto por nombre. */
  static class PriceTool {

    final AtomicInteger calls = new AtomicInteger();

    @Tool(description = "Returns the current price in USD of a product of The Store by its name")
    int priceOf(@ToolParam(description = "Product name") String name) {
      calls.incrementAndGet();
      return 139;
    }
  }

  /**
   * Tool de prueba del ciclo controlado: recibe el contexto del turno por
   * {@link ToolContext}, que no forma parte del schema que ve el modelo.
   */
  static class ContextPriceTool {

    final AtomicInteger calls = new AtomicInteger();
    volatile Object sessionId;

    @Tool(description = "Returns the current price in USD of a product of The Store by its name")
    int currentPrice(@ToolParam(description = "Product name") String name, ToolContext context) {
      calls.incrementAndGet();
      sessionId = context.getContext().get("sessionId");
      return 139;
    }
  }

  @Autowired
  private OpenAiChatModel chatModel;

  private String mainModel;
  private Map<String, Object> mainOn;
  private Map<String, Object> mainOff;
  private String rewriteModel;
  private Map<String, Object> rewriteExtra;
  private int rewriteMaxTokens;

  /**
   * Con {@code -Dspike.main-off-reasoning=minimum}, {@code main-off} no apaga el
   * razonamiento sino que lo baja al mínimo (modelos como {@code muse-glimmer-30b},
   * que solo lo regulan): el texto tiene que llegar sin {@code <think>}, con el
   * razonamiento fuera del texto.
   */
  private boolean offIsMinimum;

  /** Requests a NVIDIA de la corrida, para la línea {@code bench.usage}. */
  private final AtomicInteger requests = new AtomicInteger();

  @BeforeAll
  void models() throws Exception {
    mainModel = System.getProperty("spike.main-model", "nvidia/nemotron-3-super-120b-a12b");
    mainOn = json(System.getProperty("spike.main-on", NEMOTRON_ON));
    mainOff = json(System.getProperty("spike.main-off", NEMOTRON_OFF));
    rewriteModel = System.getProperty("spike.rewrite-model", "nvidia/nemotron-3.5-lightning-30b-a3b");
    rewriteExtra = json(System.getProperty("spike.rewrite-extra", NEMOTRON_OFF));
    rewriteMaxTokens = Integer.getInteger("spike.rewrite-max-tokens", 256);
    offIsMinimum = "minimum".equals(System.getProperty("spike.main-off-reasoning"));
  }

  @AfterAll
  void usage() {
    BenchReporter.usage("ModelSpikeSmokeIT", requests.get(), 0);
  }

  @Test
  @Order(1)
  void mainStreamsWithReasoningOff() {
    StreamResult result = capturing("streaming", () -> stream(mainOff, 1024, null,
        "Describe a mid-century velvet armchair for a reading corner in four sentences."));

    report("razonamiento off", result);
    String text = result.text.toString();
    capability("streaming", result.textChunks > 1 && !text.isBlank(),
        "chunks=" + result.textChunks + " firstFragmentMs=" + result.firstTextMillis);
    capability("reasoning-off", !text.isBlank() && !text.contains("<think>")
            && (offIsMinimum || result.reasoningChars == 0),
        (offIsMinimum ? "nivel=minimo " : "") + "reasoningChars=" + result.reasoningChars);
    assertThat(result.textChunks).isGreaterThan(1);
    assertThat(result.text.toString()).isNotBlank();
    if (!offIsMinimum) {
      assertThat(result.reasoningChars).isZero();
    }
    assertThat(result.text.toString()).doesNotContain("<think>");
  }

  @Test
  @Order(2)
  void mainStreamsWithReasoningOn() {
    StreamResult result = capturing("reasoning-on", () -> stream(mainOn, 4096, null,
        "Compare two armchairs: Aiden velvet armchair $139 and Brooks leather armchair $249. "
            + "Give the price difference and recommend one depending on use. Three sentences."));

    report("razonamiento on", result);
    boolean think = result.text.toString().contains("<think>");
    capability("reasoning-on", !result.text.toString().isBlank()
            && (result.reasoningChars > 0 || think),
        "where=" + (result.reasoningChars > 0 ? "reasoning_content" : think ? "think" : "none")
            + " firstFragmentMs=" + result.firstTextMillis);
    assertThat(result.text.toString()).isNotBlank();
    // El razonamiento tiene que llegar por algún lado: fuera del texto
    // (reasoning_content) o dentro (<think>). El README registra cuál.
    assertThat(result.reasoningChars > 0 || result.text.toString().contains("<think>")).isTrue();
  }

  @Test
  @Order(3)
  void mainCallsToolsWhileStreaming() {
    PriceTool tool = new PriceTool();
    StreamResult result = capturing("tool-calling-stream", () -> stream(mainOff, 1024, tool,
        "What is the current price of the Aiden Mid-Century Velvet Armchair? Use the tool and "
            + "answer with the price."));
    // Una vuelta con el tool call y otra con el resultado (Spring AI ejecuta la tool).
    requests.addAndGet(tool.calls.get() > 0 ? 1 : 0);

    report("tool calling", result);
    System.out.printf("spike %s: tool invocada %d veces%n", mainModel, tool.calls.get());
    capability("tool-calling-stream", tool.calls.get() >= 1
        && result.text.toString().contains("139"), "toolCalls=" + tool.calls.get());
    assertThat(tool.calls.get()).isGreaterThanOrEqualTo(1);
    assertThat(result.text.toString()).contains("139");
  }

  @Test
  @Order(4)
  void rewriteReturnsJsonQuickly() {
    BeanOutputConverter<SpikeRewrite> converter = new BeanOutputConverter<>(SpikeRewrite.class);
    ChatClient client = ChatClient.builder(chatModel)
        .defaultOptions(OpenAiChatOptions.builder().model(rewriteModel).temperature(0.0)
            .maxTokens(rewriteMaxTokens).extraBody(rewriteExtra).build())
        .build();
    List<Long> latencies = new ArrayList<>();
    int invalid = 0;
    for (int i = 0; i < REWRITE_MESSAGES.size(); i++) {
      String message = REWRITE_MESSAGES.get(i);
      long start = System.nanoTime();
      String raw;
      boolean error = false;
      requests.incrementAndGet();
      try {
        raw = client.prompt()
            .system("""
                You rewrite messages of a furniture and home decor store chat into a search query.
                intent: "other" for greetings, thanks or small talk; "compare" when the user asks
                to compare products; otherwise "search". query: short self-contained English
                search query without filler words. maxPrice/minPrice: integers or null ("cheaper"
                means the referred product price minus 1). excludeTags: tags to exclude, like
                "lighting" for "not a lamp", or an empty list.
                """ + converter.getFormat())
            .user(message)
            .call()
            .content();
      } catch (RuntimeException e) {
        raw = null;
        error = true;
        System.out.printf("spike %s: error %s%n", rewriteModel, e.getMessage());
      }
      long millis = (System.nanoTime() - start) / 1_000_000;
      latencies.add(millis);
      SpikeRewrite parsed = parse(converter, raw);
      boolean valid = parsed != null && parsed.query() != null && parsed.intent() != null;
      if (!valid) {
        invalid++;
      }
      // Como en el assistant: un error o más de 12 s es fallback; una salida
      // que no parsea, inválida.
      String outcome = error || millis > REWRITE_TIMEOUT.toMillis() ? "fallback"
          : valid ? "ok" : "invalid";
      System.out.printf("bench.rewrite smoke=ModelSpikeSmokeIT id=spike-%d outcome=%s "
          + "latencyMs=%d%n", i + 1, outcome, millis);
      System.out.printf("spike %s: %d ms \"%s\" -> %s%n", rewriteModel, millis, message,
          parsed != null ? parsed : "JSON inválido: " + raw);
    }
    long median = latencies.stream().sorted().toList().get(latencies.size() / 2);
    System.out.printf("spike %s: mediana %d ms, JSON inválido %d de %d%n", rewriteModel, median,
        invalid, REWRITE_MESSAGES.size());
    // Criterio de D12 para quedarse con el modelo.
    assertThat(invalid).isLessThanOrEqualTo(1);
    assertThat(Duration.ofMillis(median)).isLessThanOrEqualTo(Duration.ofSeconds(2));
  }

  /**
   * D1: el {@code assistant} controla el ciclo de tools. Con
   * {@code internalToolExecutionEnabled=false}, el stream tiene que devolver los
   * tool calls completos sin ejecutarlos; {@link ToolCallingManager} los ejecuta
   * y arma el historial para una segunda vuelta que responde en texto; y una
   * vuelta con las tools definidas y {@code tool_choice: "none"} no puede pedir
   * tools. Gasta 3 requests.
   */
  @Test
  @Order(5)
  void controlledToolCalling() {
    ContextPriceTool tool = new ContextPriceTool();
    var callbacks = List.of(MethodToolCallbackProvider.builder().toolObjects(tool).build()
        .getToolCallbacks());
    OpenAiChatOptions options = OpenAiChatOptions.builder().model(mainModel).temperature(0.6)
        .maxTokens(1024).extraBody(mainOff).toolCallbacks(callbacks)
        .internalToolExecutionEnabled(false)
        .toolContext(Map.of("sessionId", "spike-session")).build();
    String schema = callbacks.get(0).getToolDefinition().inputSchema();
    System.out.printf("spike %s [tools controladas]: schema de la tool %s%n", mainModel, schema);
    assertThat(schema).doesNotContain("sessionId").doesNotContain("context");

    // Vuelta 1: tiene que pedir la tool, sin que Spring AI la ejecute.
    Prompt first = new Prompt(List.of(new UserMessage(
        "What is the current price of the Aiden Mid-Century Velvet Armchair? Use the tool.")),
        options);
    Round round1 = capturing("controlled-tool-calls", () -> round(first));
    System.out.printf("spike %s [tools controladas] vuelta 1: %d respuestas, %d tool calls %s, "
            + "texto \"%s\", %d ms%n", mainModel, round1.responses, round1.toolCalls.size(),
        round1.toolCalls, round1.text, round1.millis);
    capability("controlled-tool-calls", tool.calls.get() == 0 && !round1.toolCalls.isEmpty()
            && "currentPrice".equals(round1.toolCalls.get(0).name())
            && round1.toolCalls.get(0).id() != null && !round1.toolCalls.get(0).id().isBlank()
            && round1.toolCalls.get(0).arguments().contains("name"),
        "toolCalls=" + round1.toolCalls.size() + " ms=" + round1.millis);
    assertThat(tool.calls.get()).as("la ejecución interna está desactivada").isZero();
    assertThat(round1.toolCalls).isNotEmpty();
    AssistantMessage.ToolCall call = round1.toolCalls.get(0);
    assertThat(call.name()).isEqualTo("currentPrice");
    assertThat(call.id()).isNotBlank();
    assertThat(parseArguments(call.arguments())).containsKey("name");

    // El loop ejecuta los tool calls con ToolCallingManager.
    ChatResponse aggregated = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
        .content(round1.text.toString()).toolCalls(round1.toolCalls).build())));
    ToolExecutionResult executed = ToolCallingManager.builder().build()
        .executeToolCalls(first, aggregated);
    List<Message> history = executed.conversationHistory();
    assertThat(tool.calls.get()).isEqualTo(round1.toolCalls.size());
    assertThat(tool.sessionId).isEqualTo("spike-session");
    assertThat(history).extracting(Message::getMessageType)
        .containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL);
    assertThat(((ToolResponseMessage) history.get(2)).getResponses()).hasSize(
        round1.toolCalls.size());

    // Vuelta 2: con el resultado de la tool, responde en texto.
    Round round2 = capturing("second-round", () -> round(new Prompt(history, options)));
    System.out.printf("spike %s [tools controladas] vuelta 2: %d fragmentos, %d tool calls, "
            + "texto \"%s\", %d ms%n", mainModel, round2.responses, round2.toolCalls.size(),
        round2.text.toString().replace('\n', ' '), round2.millis);
    capability("second-round", round2.toolCalls.isEmpty()
        && round2.text.toString().contains("139"), "ms=" + round2.millis);
    assertThat(round2.toolCalls).isEmpty();
    assertThat(round2.text.toString()).contains("139");

    // Vuelta final de D1: las mismas tools, con tool_choice "none".
    OpenAiChatOptions none = OpenAiChatOptions.fromOptions(options);
    none.setToolChoice("none");
    Round round3 = capturing("tool-choice-none", () -> round(new Prompt(List.of(new UserMessage(
        "What is the current price of the Brooks leather armchair? Use the tool.")), none)));
    System.out.printf("spike %s [tools controladas] vuelta con tool_choice none: %d tool calls, "
            + "texto \"%s\", %d ms%n", mainModel, round3.toolCalls.size(),
        round3.text.toString().replace('\n', ' '), round3.millis);
    capability("tool-choice-none", round3.toolCalls.isEmpty()
        && !round3.text.toString().isBlank(), "ms=" + round3.millis);

    // Vuelta correctiva de add-assistant-tools: tool_choice "required" obliga a
    // pedir una tool aunque el mensaje no la necesite (select-assistant-models).
    OpenAiChatOptions required = OpenAiChatOptions.fromOptions(options);
    required.setToolChoice("required");
    Round round4 = capturing("tool-choice-required", () -> round(new Prompt(List.of(
        new UserMessage("Hi! Just say hello.")), required)));
    System.out.printf("spike %s [tools controladas] vuelta con tool_choice required: %d tool "
            + "calls %s, texto \"%s\", %d ms%n", mainModel, round4.toolCalls.size(),
        round4.toolCalls, round4.text.toString().replace('\n', ' '), round4.millis);
    capability("tool-choice-required", !round4.toolCalls.isEmpty(), "ms=" + round4.millis);

    assertThat(round3.toolCalls).as("tool_choice none").isEmpty();
    assertThat(round3.text.toString()).isNotBlank();
    assertThat(round4.toolCalls).as("tool_choice required").isNotEmpty();
  }

  /**
   * D4 de {@code select-assistant-models}: con qué {@code extra-body} se apaga
   * ({@code -Dspike.probe-mode=off}, el default) o se prende ({@code on}) el
   * razonamiento de {@code -Dspike.probe-model}. Una request sin streaming por
   * variante, en orden, hasta la primera que cumple el criterio:
   * <ul>
   *   <li>apagado: {@code content} trae "OK", sin {@code reasoning_content} ni
   *       {@code <think>}, {@code finish_reason=stop} y menos de 20 tokens de salida;</li>
   *   <li>prendido: el razonamiento llega en {@code reasoning_content} o como
   *       {@code <think>} en el texto (que se filtra con {@code strip-think-tags}).</li>
   * </ul>
   * Un timeout de 30 s se reintenta una vez a los 60 s; un segundo timeout, un
   * 404 o un 403 descartan el modelo y cortan el sondeo.
   */
  @Test
  @Order(7)
  void thinkingProbe() throws Exception {
    String variantsJson = System.getProperty("spike.probe-variants");
    org.junit.jupiter.api.Assumptions.assumeTrue(variantsJson != null && !variantsJson.isBlank(),
        "sondeo deshabilitado (usar -Dspike.probe-variants)");
    String model = System.getProperty("spike.probe-model", mainModel);
    boolean on = "on".equals(System.getProperty("spike.probe-mode", "off"));
    int maxTokens = Integer.getInteger("spike.probe-max-tokens", on ? 1024 : 64);
    List<Map<String, Object>> variants = JSON.readValue(variantsJson, new TypeReference<>() { });
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    String capability = on ? "thinking-on" : "thinking-off";

    for (Map<String, Object> extra : variants) {
      ProbeResult result = probe(http, model, extra, maxTokens);
      System.out.printf("bench.probe model=%s mode=%s extra=%s status=%d latencyMs=%d "
              + "finishReason=%s completionTokens=%d reasoningChars=%d think=%s attempts=%d "
              + "content=\"%s\"%n", model, on ? "on" : "off", JSON.writeValueAsString(extra),
          result.status, result.millis, result.finishReason, result.completionTokens,
          result.reasoningChars, result.think, result.attempts,
          result.content == null ? "null" : result.content.replace("\n", "\\n"));
      if (result.discarded != null) {
        capability(capability, false, "model=" + model + " discarded=" + result.discarded);
        throw new AssertionError("Modelo descartado: " + result.discarded);
      }
      boolean accepted = on
          ? result.reasoningChars > 0 || result.think
          : result.content != null && result.content.contains("OK") && result.reasoningChars == 0
              && !result.think && "stop".equals(result.finishReason)
              && result.completionTokens >= 0 && result.completionTokens < 20;
      if (accepted) {
        capability(capability, true, "model=" + model + " extra=" + JSON.writeValueAsString(extra)
            + " where=" + (result.reasoningChars > 0 ? "reasoning_content"
                : result.think ? "think" : "none"));
        return;
      }
    }
    capability(capability, false, "model=" + model + " ninguna variante cumple el criterio");
    throw new AssertionError("Ninguna variante cumple el criterio de " + capability);
  }

  /** Resultado de una variante de {@link #thinkingProbe}. */
  static class ProbeResult {
    int status = -1;
    long millis;
    int attempts;
    String content;
    int reasoningChars;
    boolean think;
    String finishReason;
    int completionTokens = -1;
    /** Motivo del descarte ({@code timeout}, {@code 404}, {@code 403}), o {@code null}. */
    String discarded;
  }

  private ProbeResult probe(HttpClient http, String model, Map<String, Object> extra,
      int maxTokens) throws Exception {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("model", model);
    body.put("messages", List.of(Map.of("role", "user", "content", PROBE_PROMPT)));
    body.put("max_tokens", maxTokens);
    body.put("temperature", 0);
    body.put("stream", false);
    body.putAll(extra);
    HttpRequest request = HttpRequest.newBuilder(
            URI.create("https://integrate.api.nvidia.com/v1/chat/completions"))
        .timeout(PROBE_TIMEOUT)
        .header("Authorization", "Bearer " + System.getenv("NVIDIA_API_KEY"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
        .build();
    ProbeResult result = new ProbeResult();
    HttpResponse<String> response = null;
    for (int attempt = 1; attempt <= 2 && response == null; attempt++) {
      if (attempt == 2) {
        System.out.printf("spike sondeo %s: timeout de %d s, reintento en %d s%n", model,
            PROBE_TIMEOUT.toSeconds(), PROBE_RETRY_PAUSE.toSeconds());
        Thread.sleep(PROBE_RETRY_PAUSE.toMillis());
      }
      result.attempts = attempt;
      requests.incrementAndGet();
      long start = System.nanoTime();
      try {
        response = http.send(request, HttpResponse.BodyHandlers.ofString());
      } catch (java.net.http.HttpTimeoutException e) {
        response = null;
      }
      result.millis = (System.nanoTime() - start) / 1_000_000;
    }
    if (response == null) {
      result.discarded = "timeout";
      return result;
    }
    result.status = response.statusCode();
    if (result.status == 404 || result.status == 403) {
      result.discarded = Integer.toString(result.status);
      System.out.printf("spike sondeo %s: %s%n", model, response.body().length() > 300
          ? response.body().substring(0, 300) : response.body());
      return result;
    }
    if (result.status != 200) {
      System.out.printf("spike sondeo %s: status %d %s%n", model, result.status,
          response.body().length() > 300 ? response.body().substring(0, 300) : response.body());
      return result;
    }
    var root = JSON.readTree(response.body());
    var choice = root.path("choices").path(0);
    var message = choice.path("message");
    result.content = message.path("content").isTextual() ? message.path("content").asText() : null;
    String reasoning = message.path("reasoning_content").isTextual()
        ? message.path("reasoning_content").asText()
        : message.path("reasoning").isTextual() ? message.path("reasoning").asText() : "";
    result.reasoningChars = reasoning.length();
    result.think = result.content != null && result.content.contains("<think>");
    result.finishReason = choice.path("finish_reason").asText(null);
    result.completionTokens = root.path("usage").path("completion_tokens").asInt(-1);
    return result;
  }

  /** Línea {@code bench.capability} que lee el reporte del benchmark (M1). */
  private void capability(String name, boolean ok, String detail) {
    System.out.printf("bench.capability model=%s capability=%s ok=%s %s%n", mainModel, name, ok,
        detail);
  }

  /**
   * Corre una vuelta contra el modelo y cuenta la request. Si falla, deja la
   * capacidad registrada como no cumplida antes de propagar el error.
   */
  private <T> T capturing(String name, java.util.function.Supplier<T> call) {
    requests.incrementAndGet();
    try {
      return call.get();
    } catch (RuntimeException e) {
      capability(name, false, "error=\"" + String.valueOf(e.getMessage()).replace('"', '\'')
          .replace('\n', ' ') + "\"");
      throw e;
    }
  }

  /**
   * D9: qué responde NVIDIA ante un 429. Manda 45 requests mínimas (1 token de
   * salida) en paralelo al modelo de reescritura, más que los 40 RPM de la
   * cuenta, y reporta los status y los headers de los 429. Después hace 1
   * request en streaming con el cliente de Spring AI, que tiene que llegar como
   * {@link ChatProviderException} {@code QUOTA} con el {@code Retry-After} (si
   * NVIDIA lo manda) en {@code retryAfter}. Si ninguna request de la ráfaga
   * responde 429, lo reporta y termina sin fallar. Agota la cuota de chat durante un
   * minuto, así que no corre salvo con {@code -Dspike.quota-burst=true}.
   */
  @Test
  @Order(6)
  void quotaBurst() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("spike.quota-burst"),
        "ráfaga deshabilitada (usar -Dspike.quota-burst=true)");
    String body = JSON.writeValueAsString(Map.of(
        "model", rewriteModel,
        "messages", List.of(Map.of("role", "user", "content", "hi")),
        "max_tokens", 1,
        "chat_template_kwargs", Map.of("enable_thinking", false)));
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    HttpRequest request = HttpRequest.newBuilder(
            URI.create("https://integrate.api.nvidia.com/v1/chat/completions"))
        .timeout(Duration.ofSeconds(60))
        .header("Authorization", "Bearer " + System.getenv("NVIDIA_API_KEY"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    long start = System.nanoTime();
    List<CompletableFuture<HttpResponse<String>>> burst = new ArrayList<>();
    for (int i = 0; i < 45; i++) {
      burst.add(http.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
    }
    Map<Integer, Integer> statuses = new TreeMap<>();
    HttpResponse<String> rejected = null;
    for (CompletableFuture<HttpResponse<String>> future : burst) {
      try {
        HttpResponse<String> response = future.get();
        statuses.merge(response.statusCode(), 1, Integer::sum);
        if (response.statusCode() == 429 && rejected == null) {
          rejected = response;
        }
      } catch (Exception e) {
        statuses.merge(-1, 1, Integer::sum);
      }
    }
    System.out.printf("spike 429: ráfaga de 45 en %d ms, status %s%n",
        (System.nanoTime() - start) / 1_000_000, statuses);
    if (rejected == null) {
      // Resultado del 2026-10-06: las 45 respondieron 200 (NVIDIA las demoró en
      // lugar de rechazarlas). Sin 429 no hay header que inspeccionar: queda el
      // default-retry-after de D9 y el parseo se prueba con tests unitarios.
      System.out.println("spike 429: ninguna request de la ráfaga respondió 429");
      return;
    }
    Map<String, List<String>> headers = new TreeMap<>(rejected.headers().map());
    headers.remove("set-cookie");
    System.out.printf("spike 429: headers %s%n  cuerpo: %s%n", headers,
        rejected.body().length() > 300 ? rejected.body().substring(0, 300) : rejected.body());
    System.out.printf("spike 429: Retry-After = %s%n",
        rejected.headers().firstValue("Retry-After").orElse("(ausente)"));

    // La misma cuota, ahora por el cliente de Spring AI en streaming.
    OpenAiChatOptions options = OpenAiChatOptions.builder().model(rewriteModel).maxTokens(1)
        .extraBody(rewriteExtra).build();
    Throwable error = null;
    try {
      chatModel.stream(new Prompt("hi", options)).blockLast(Duration.ofSeconds(60));
    } catch (RuntimeException e) {
      error = e;
    }
    assertThat(error).as("el request en streaming después de la ráfaga").isNotNull();
    ChatProviderException translated = ChatProviderErrors.translate(error);
    System.out.printf("spike 429: streaming -> %s (%s), retryAfter %s%n",
        translated.reason(), translated.getMessage(), translated.retryAfter()
            .map(Duration::toString).orElse("(vacío)"));
    assertThat(translated.reason()).isEqualTo(ChatProviderException.Reason.QUOTA);
    rejected.headers().firstValue("Retry-After").ifPresent(header ->
        assertThat(translated.retryAfter()).isPresent());
  }

  /** Una vuelta en streaming: texto concatenado y tool calls de todas las respuestas. */
  private Round round(Prompt prompt) {
    Round round = new Round();
    long start = System.nanoTime();
    Flux<ChatResponse> stream = chatModel.stream(prompt);
    stream.doOnNext(response -> {
      round.responses++;
      if (response.getResult() == null) {
        return;
      }
      AssistantMessage output = response.getResult().getOutput();
      if (output.getText() != null) {
        round.text.append(output.getText());
      }
      if (output.hasToolCalls()) {
        round.toolCalls.addAll(output.getToolCalls());
      }
    }).blockLast(Duration.ofSeconds(120));
    round.millis = (System.nanoTime() - start) / 1_000_000;
    return round;
  }

  private static Map<String, Object> parseArguments(String arguments) {
    try {
      return JSON.readValue(arguments, new TypeReference<Map<String, Object>>() { });
    } catch (Exception e) {
      throw new AssertionError("Argumentos del tool call que no son JSON: " + arguments, e);
    }
  }

  static class Round {
    final StringBuilder text = new StringBuilder();
    final List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
    int responses;
    long millis;
  }

  private StreamResult stream(Map<String, Object> extraBody, int maxTokens, Object tool,
      String message) {
    // defaultOptions como los del cliente principal (D3) y opciones por request
    // con el extraBody del razonamiento, armadas como copia de los defaults.
    OpenAiChatOptions defaults = OpenAiChatOptions.builder().model(mainModel).temperature(0.6)
        .maxTokens(1024).build();
    OpenAiChatOptions perRequest = OpenAiChatOptions.fromOptions(defaults);
    perRequest.setMaxTokens(maxTokens);
    perRequest.setExtraBody(extraBody);
    ChatClient client = ChatClient.builder(chatModel).defaultOptions(defaults).build();

    ChatClient.ChatClientRequestSpec request = client.prompt().user(message).options(perRequest);
    if (tool != null) {
      request = request.tools(tool);
    }
    StreamResult result = new StreamResult();
    long start = System.nanoTime();
    request.stream().chatResponse()
        .doOnNext(response -> {
          if (response.getResult() == null) {
            return;
          }
          var output = response.getResult().getOutput();
          String text = output.getText();
          if (text != null && !text.isEmpty()) {
            if (result.textChunks == 0) {
              result.firstTextMillis = (System.nanoTime() - start) / 1_000_000;
            }
            result.textChunks++;
            result.text.append(text);
          }
          Object reasoning = output.getMetadata().get("reasoningContent");
          if (reasoning instanceof String r) {
            result.reasoningChars += r.length();
          }
        })
        .blockLast(Duration.ofSeconds(120));
    result.millis = (System.nanoTime() - start) / 1_000_000;
    return result;
  }

  private void report(String label, StreamResult result) {
    System.out.printf("spike %s [%s]: %d fragmentos de texto, %d caracteres de razonamiento en "
            + "reasoning_content, <think> en el texto: %s, primer fragmento a los %d ms, %d ms en total%n  texto: %s%n",
        mainModel, label, result.textChunks, result.reasoningChars,
        result.text.toString().contains("<think>"), result.firstTextMillis, result.millis,
        result.text.toString().replace('\n', ' '));
  }

  private static SpikeRewrite parse(BeanOutputConverter<SpikeRewrite> converter, String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return converter.convert(raw);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static Map<String, Object> json(String value) throws Exception {
    return JSON.readValue(value, new TypeReference<Map<String, Object>>() { });
  }

  static class StreamResult {
    final StringBuilder text = new StringBuilder();
    int textChunks;
    int reasoningChars;
    long millis;
    long firstTextMillis;
  }
}
