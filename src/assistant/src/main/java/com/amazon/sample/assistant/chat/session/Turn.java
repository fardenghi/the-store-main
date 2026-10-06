package com.amazon.sample.assistant.chat.session;

import java.util.List;

/**
 * Un turno guardado en la memoria de la sesión: el mensaje del usuario, la
 * respuesta que vio y, si el turno usó tools, sus vueltas con tool calls y un
 * resultado compacto de cada una. Sin el razonamiento ni el contexto RAG (D6).
 *
 * <p>Las vueltas con tools se guardan desde la corrección posterior de D7 de
 * {@code add-assistant-tools}: sin ellas, el historial mostraba respuestas como
 * "added to your cart" sin ningún tool call, y el modelo las imitaba en los
 * turnos siguientes en lugar de llamar a {@code addToCart}.
 *
 * @param user mensaje del usuario
 * @param assistant texto completo que vio el usuario, incluido el de las vueltas con tools
 * @param toolRounds vueltas con tool calls, en orden (vacío si el turno no usó tools)
 */
public record Turn(String user, String assistant, List<ToolRound> toolRounds) {

  public Turn {
    toolRounds = List.copyOf(toolRounds);
  }

  /** Un turno sin tools. */
  public Turn(String user, String assistant) {
    this(user, assistant, List.of());
  }

  /** Texto de la respuesta final: el que siguió a la última vuelta con tools. */
  public String finalText() {
    int prefix = toolRounds.stream().mapToInt(round -> round.text().length()).sum();
    return prefix <= assistant.length() ? assistant.substring(prefix) : assistant;
  }
}
