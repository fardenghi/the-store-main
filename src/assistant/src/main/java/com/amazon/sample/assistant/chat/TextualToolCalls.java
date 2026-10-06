package com.amazon.sample.assistant.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * Tool calls que el modelo escribió como texto en lugar de pedirlos como tool
 * calls (corrección posterior de {@code add-assistant-tools}):
 * {@code nemotron-3-super} a veces responde
 * {@code [addToCart: {"productId": "…", "quantity": 2}]} en el contenido y
 * cierra la vuelta sin {@code tool_calls}. El turno terminaba sin ejecutar nada
 * y el usuario veía el JSON.
 *
 * <p>Una oración con ese formato, de una tool que existe y con argumentos JSON
 * válidos, se saca del texto y se convierte en un tool call con un id generado.
 * El ciclo lo ejecuta como cualquier otro.
 */
final class TextualToolCalls {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** {@code [nombre: {…}]} o {@code [nombre({…})]}, con el JSON en una sola línea. */
  private static final Pattern CALL = Pattern.compile(
      "\\[\\s*([A-Za-z]+)\\s*(?::|\\()\\s*(\\{[^\\n]*?\\})\\s*\\)?\\s*\\]");

  private final Set<String> toolNames;
  private final List<AssistantMessage.ToolCall> calls = new ArrayList<>();

  TextualToolCalls(Set<String> toolNames) {
    this.toolNames = Set.copyOf(toolNames);
  }

  /**
   * Saca de la oración los tool calls escritos como texto y devuelve lo que
   * queda (vacío si la oración era solo el tool call).
   */
  String extract(String sentence) {
    Matcher matcher = CALL.matcher(sentence);
    StringBuilder rest = new StringBuilder();
    int last = 0;
    boolean found = false;
    while (matcher.find()) {
      String name = matcher.group(1);
      String arguments = matcher.group(2);
      if (!toolNames.contains(name) || !isJsonObject(arguments)) {
        continue;
      }
      found = true;
      calls.add(new AssistantMessage.ToolCall("text-call-" + UUID.randomUUID(), "function", name,
          arguments));
      rest.append(sentence, last, matcher.start());
      last = matcher.end();
    }
    if (!found) {
      return sentence;
    }
    rest.append(sentence.substring(last));
    return rest.toString().isBlank() ? (sentence.endsWith("\n") ? "\n" : "") : rest.toString();
  }

  /** Tool calls encontrados, en orden. */
  List<AssistantMessage.ToolCall> calls() {
    return List.copyOf(calls);
  }

  private static boolean isJsonObject(String text) {
    try {
      JsonNode node = JSON.readTree(text);
      return node != null && node.isObject();
    } catch (Exception e) {
      return false;
    }
  }
}
