package com.amazon.sample.assistant.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

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

  @Autowired
  private OpenAiChatModel chatModel;

  private String mainModel;
  private Map<String, Object> mainOn;
  private Map<String, Object> mainOff;
  private String rewriteModel;
  private Map<String, Object> rewriteExtra;

  @BeforeAll
  void models() throws Exception {
    mainModel = System.getProperty("spike.main-model", "nvidia/nemotron-3-super-120b-a12b");
    mainOn = json(System.getProperty("spike.main-on", NEMOTRON_ON));
    mainOff = json(System.getProperty("spike.main-off", NEMOTRON_OFF));
    rewriteModel = System.getProperty("spike.rewrite-model", "nvidia/nemotron-3.5-lightning-30b-a3b");
    rewriteExtra = json(System.getProperty("spike.rewrite-extra", NEMOTRON_OFF));
  }

  @Test
  @Order(1)
  void mainStreamsWithReasoningOff() {
    StreamResult result = stream(mainOff, 1024, null,
        "Describe a mid-century velvet armchair for a reading corner in four sentences.");

    report("razonamiento off", result);
    assertThat(result.textChunks).isGreaterThan(1);
    assertThat(result.text.toString()).isNotBlank();
    assertThat(result.reasoningChars).isZero();
    assertThat(result.text.toString()).doesNotContain("<think>");
  }

  @Test
  @Order(2)
  void mainStreamsWithReasoningOn() {
    StreamResult result = stream(mainOn, 4096, null,
        "Compare two armchairs: Aiden velvet armchair $139 and Brooks leather armchair $249. "
            + "Give the price difference and recommend one depending on use. Three sentences.");

    report("razonamiento on", result);
    assertThat(result.text.toString()).isNotBlank();
    // El razonamiento tiene que llegar por algún lado: fuera del texto
    // (reasoning_content) o dentro (<think>). El README registra cuál.
    assertThat(result.reasoningChars > 0 || result.text.toString().contains("<think>")).isTrue();
  }

  @Test
  @Order(3)
  void mainCallsToolsWhileStreaming() {
    PriceTool tool = new PriceTool();
    StreamResult result = stream(mainOff, 1024, tool,
        "What is the current price of the Aiden Mid-Century Velvet Armchair? Use the tool and "
            + "answer with the price.");

    report("tool calling", result);
    System.out.printf("spike %s: tool invocada %d veces%n", mainModel, tool.calls.get());
    assertThat(tool.calls.get()).isGreaterThanOrEqualTo(1);
    assertThat(result.text.toString()).contains("139");
  }

  @Test
  @Order(4)
  void rewriteReturnsJsonQuickly() {
    BeanOutputConverter<SpikeRewrite> converter = new BeanOutputConverter<>(SpikeRewrite.class);
    ChatClient client = ChatClient.builder(chatModel)
        .defaultOptions(OpenAiChatOptions.builder().model(rewriteModel).temperature(0.0)
            .maxTokens(256).extraBody(rewriteExtra).build())
        .build();
    List<Long> latencies = new ArrayList<>();
    int invalid = 0;
    for (String message : REWRITE_MESSAGES) {
      long start = System.nanoTime();
      String raw;
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
        System.out.printf("spike %s: error %s%n", rewriteModel, e.getMessage());
      }
      long millis = (System.nanoTime() - start) / 1_000_000;
      latencies.add(millis);
      SpikeRewrite parsed = parse(converter, raw);
      if (parsed == null || parsed.query() == null || parsed.intent() == null) {
        invalid++;
      }
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
