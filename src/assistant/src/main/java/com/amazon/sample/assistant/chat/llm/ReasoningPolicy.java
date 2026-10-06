package com.amazon.sample.assistant.chat.llm;

import com.amazon.sample.assistant.chat.rewrite.Intent;
import com.amazon.sample.assistant.config.ChatProperties;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Decide por turno si el modelo principal razona y arma las opciones del
 * request (D7): en modo {@code auto}, el razonamiento se activa solo en las
 * comparaciones. Activado lleva el {@code on-extra-body} y
 * {@code max-tokens-reasoning}, porque los tokens del razonamiento cuentan
 * dentro del límite; desactivado, el {@code off-extra-body} y
 * {@code max-tokens}.
 *
 * <p>Las opciones del request reemplazan a las {@code defaultOptions} del
 * {@code ChatClient} (no se combinan campo a campo), así que se arman como una
 * copia de las opciones base del cliente principal con el {@code extraBody} y
 * el {@code maxTokens} del turno.
 */
public class ReasoningPolicy {

  /** Opciones del turno y si el razonamiento quedó activado. */
  public record TurnOptions(boolean reasoning, OpenAiChatOptions options) {
  }

  private final OpenAiChatOptions baseOptions;
  private final ChatProperties.Chat properties;

  public ReasoningPolicy(OpenAiChatOptions baseOptions, ChatProperties.Chat properties) {
    this.baseOptions = baseOptions;
    this.properties = properties;
  }

  public boolean reasoningFor(Intent intent) {
    return switch (properties.reasoning().mode()) {
      case ALWAYS -> true;
      case NEVER -> false;
      case AUTO -> intent == Intent.COMPARE;
    };
  }

  public TurnOptions optionsFor(Intent intent) {
    boolean reasoning = reasoningFor(intent);
    OpenAiChatOptions options = OpenAiChatOptions.fromOptions(baseOptions);
    options.setMaxTokens(reasoning ? properties.maxTokensReasoning() : properties.maxTokens());
    options.setExtraBody(new java.util.LinkedHashMap<>(reasoning
        ? properties.reasoning().onExtraBody() : properties.reasoning().offExtraBody()));
    return new TurnOptions(reasoning, options);
  }
}
