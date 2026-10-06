package com.amazon.sample.assistant.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Chat del asistente: reescritura de consulta ({@code retail.assistant.rewrite})
 * y turno de conversación ({@code retail.assistant.chat}) (D11).
 *
 * <p>Los {@code extra-body} son mapas anidados que se mandan tal cual en el
 * cuerpo del request a NVIDIA (salvo {@code reasoning_effort}, que va por la
 * opción nativa, ver {@code ExtraBody}). Sus defaults (los de
 * {@code meta/muse-glimmer-30b}, elegido en {@code select-assistant-models}) están acá y no
 * en {@code application.yml}: Spring Boot combina las claves de un mapa
 * definido en varias fuentes, así que un default en el YAML no se podría
 * reemplazar por completo desde {@code SPRING_APPLICATION_JSON} (plan B). Un
 * mapa vacío explícito manda el request sin campos extra. Como las variables de entorno y algunas fuentes
 * de configuración llegan como texto, los valores {@code "true"}/{@code "false"}
 * y los números se convierten a su tipo JSON: si no, NVIDIA recibiría
 * {@code "enable_thinking": "false"}, que el chat template evalúa como verdadero.
 *
 * @param rewrite reescritura de consulta (D4)
 * @param chat turno de conversación (D5, D6, D7, D10)
 */
@Validated
@ConfigurationProperties("retail.assistant")
public record ChatProperties(@NotNull @Valid Rewrite rewrite, @NotNull @Valid Chat chat) {

  /**
   * @param timeout tiempo límite de la llamada de reescritura; si se excede, fallback
   * @param historyTurns turnos de la sesión que recibe el modelo de reescritura
   * @param maxTokens límite de tokens de salida de la reescritura; incluye el
   *     razonamiento en los modelos que no lo pueden apagar ({@code select-assistant-models})
   * @param extraBody campos extra del request de reescritura (thinking desactivado)
   */
  public record Rewrite(
      @NotNull Duration timeout,
      @Min(0) @Max(10) int historyTurns,
      @Min(1) int maxTokens,
      Map<String, Object> extraBody) {

    public Rewrite {
      extraBody = normalize(extraBody == null ? REASONING_LOW : extraBody);
    }
  }

  /**
   * @param retrievalK productos que recibe el modelo principal como contexto
   * @param minScore umbral de score de la búsqueda (0 lo desactiva)
   * @param maxTokens límite de tokens de salida con el razonamiento desactivado
   * @param maxTokensReasoning límite con el razonamiento activado (incluye sus tokens)
   * @param compareRawRetrieval si se calcula el top-k de la consulta cruda para el log (D9)
   * @param reasoning cuándo y cómo se activa el razonamiento (D7)
   * @param memory memoria por sesión (D6)
   * @param timeouts tiempos límite del modelo principal (D10)
   */
  public record Chat(
      @Min(1) @Max(20) int retrievalK,
      @Min(0) double minScore,
      @Min(1) int maxTokens,
      @Min(1) int maxTokensReasoning,
      boolean compareRawRetrieval,
      @NotNull @Valid Reasoning reasoning,
      @NotNull @Valid Memory memory,
      @NotNull @Valid Timeouts timeouts) {
  }

  /** {@code auto}: activado solo en las comparaciones. */
  public enum ReasoningMode { AUTO, ALWAYS, NEVER }

  /**
   * @param mode {@code auto}, {@code always} o {@code never}
   * @param onExtraBody campos extra del request con el razonamiento activado
   * @param offExtraBody campos extra del request con el razonamiento desactivado
   * @param stripThinkTags si se descartan bloques {@code <think>} del texto; solo
   *     hace falta con modelos que mezclan el razonamiento en la respuesta (D7)
   */
  public record Reasoning(
      @NotNull ReasoningMode mode,
      Map<String, Object> onExtraBody,
      Map<String, Object> offExtraBody,
      boolean stripThinkTags) {

    public Reasoning {
      onExtraBody = normalize(onExtraBody == null ? REASONING_HIGH : onExtraBody);
      offExtraBody = normalize(offExtraBody == null ? REASONING_LOW : offExtraBody);
    }
  }

  /**
   * @param maxTurns turnos (mensaje + respuesta) que se recuerdan por sesión
   * @param idleTtl inactividad después de la cual se descarta la sesión
   * @param maxSessions máximo de sesiones en memoria
   */
  public record Memory(
      @Min(1) int maxTurns,
      @NotNull Duration idleTtl,
      @Min(1) long maxSessions) {
  }

  /**
   * @param firstTokenReasoning espera máxima del primer fragmento con razonamiento
   * @param firstToken espera máxima del primer fragmento sin razonamiento
   * @param turn duración máxima de un turno completo
   * @param keepalive intervalo del comentario {@code :keepalive} sin otros eventos
   */
  public record Timeouts(
      @NotNull Duration firstTokenReasoning,
      @NotNull Duration firstToken,
      @NotNull Duration turn,
      @NotNull Duration keepalive) {
  }

  /**
   * Razonamiento alto de {@code meta/muse-glimmer-30b} (default de
   * {@code reasoning.on-extra-body}, en las comparaciones).
   */
  public static final Map<String, Object> REASONING_HIGH = Map.of("reasoning_effort", "high");

  /**
   * Esfuerzo mínimo de {@code meta/muse-glimmer-30b}, que no permite apagar el
   * razonamiento: es su "sin razonamiento" (default de los demás {@code extra-body}).
   */
  public static final Map<String, Object> REASONING_LOW = Map.of("reasoning_effort", "low");

  /** Razonamiento activado en Nemotron (plan B, {@code reasoning.on-extra-body}). */
  public static final Map<String, Object> NEMOTRON_THINKING_ON =
      Map.of("chat_template_kwargs", Map.of("enable_thinking", true));

  /** Razonamiento desactivado en Nemotron (plan B, los demás {@code extra-body}). */
  public static final Map<String, Object> NEMOTRON_THINKING_OFF =
      Map.of("chat_template_kwargs", Map.of("enable_thinking", false));

  private static final Pattern INTEGER = Pattern.compile("-?\\d{1,18}");
  private static final Pattern DECIMAL = Pattern.compile("-?\\d+\\.\\d+");

  static Map<String, Object> normalize(Map<String, Object> map) {
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach((key, value) -> result.put(key, normalizeValue(value)));
    return java.util.Collections.unmodifiableMap(result);
  }

  @SuppressWarnings("unchecked")
  private static Object normalizeValue(Object value) {
    if (value instanceof Map<?, ?> nested) {
      return normalize((Map<String, Object>) nested);
    }
    if (value instanceof List<?> list) {
      return list.stream().map(ChatProperties::normalizeValue).toList();
    }
    if (value instanceof CharSequence text) {
      String s = text.toString().trim();
      if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
        return Boolean.valueOf(s);
      }
      if (INTEGER.matcher(s).matches()) {
        return Long.valueOf(s);
      }
      if (DECIMAL.matcher(s).matches()) {
        return Double.valueOf(s);
      }
      return s;
    }
    return value;
  }
}
