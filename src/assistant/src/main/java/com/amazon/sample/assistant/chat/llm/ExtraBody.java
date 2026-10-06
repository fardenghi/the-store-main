package com.amazon.sample.assistant.chat.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;

/**
 * Aplica un {@code extra-body} configurado a las opciones de un request, con
 * cada parámetro por un solo camino ({@code select-assistant-models}, segundo
 * intento).
 *
 * <p>Spring AI 1.1.8 manda el {@code extraBody} al nivel raíz del JSON, pero en
 * los requests con tools vuelve a pasar el request por
 * {@code ModelOptionsUtils.merge}: una clave del {@code extraBody} que coincide
 * con un campo propio de {@link ChatCompletionRequest} se copia a ese campo y
 * se serializa dos veces (NVIDIA responde 400 "duplicate field"). Por eso
 * {@code reasoning_effort} se saca del mapa y va por la opción nativa
 * {@link OpenAiChatOptions#setReasoningEffort}; cualquier otro campo propio del
 * request en un {@code extra-body} es un error de configuración.
 */
public final class ExtraBody {

  /** Nivel de razonamiento de los modelos que lo regulan por request. */
  public static final String REASONING_EFFORT = "reasoning_effort";

  /** Campos propios del request de Spring AI (los que no pueden ir en el extra-body). */
  private static final Set<String> NATIVE_FIELDS = Arrays
      .stream(ChatCompletionRequest.class.getDeclaredFields())
      .map(field -> field.getAnnotation(JsonProperty.class))
      .filter(property -> property != null && !"extra_body".equals(property.value()))
      .map(JsonProperty::value)
      .collect(Collectors.toUnmodifiableSet());

  private ExtraBody() {
  }

  /**
   * Pone en {@code options} el {@code extraBody} sin los campos nativos y el
   * {@code reasoningEffort} del mapa (o ninguno, si el mapa no lo trae).
   *
   * @throws IllegalArgumentException si el mapa trae otro campo propio del request
   */
  public static void apply(OpenAiChatOptions options, Map<String, Object> extraBody) {
    Map<String, Object> rest = new LinkedHashMap<>(extraBody);
    Object effort = rest.remove(REASONING_EFFORT);
    Set<String> collisions = rest.keySet().stream().filter(NATIVE_FIELDS::contains)
        .collect(Collectors.toCollection(java.util.TreeSet::new));
    if (!collisions.isEmpty()) {
      throw new IllegalArgumentException("El extra-body " + extraBody + " trae campos propios "
          + "del request de chat " + collisions + ": van por las opciones de "
          + "spring.ai.openai.chat.options, no por el extra-body");
    }
    options.setReasoningEffort(effort == null ? null : effort.toString());
    options.setExtraBody(rest);
  }
}
