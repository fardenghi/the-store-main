package com.amazon.sample.assistant.chat.session;

import java.util.List;

/**
 * Una vuelta del modelo con tool calls, tal como queda en la memoria de la
 * sesión (corrección posterior de D7 de {@code add-assistant-tools}): el texto
 * que el usuario vio antes de los tool calls, los tool calls con sus ids y
 * argumentos, y un resultado compacto de cada tool.
 *
 * <p>Se reenvía al modelo en los turnos siguientes como un
 * {@code AssistantMessage} con los tool calls y un {@code ToolResponseMessage},
 * para que el historial muestre qué acciones se hicieron de verdad.
 *
 * @param text texto emitido en la vuelta antes de pedir las tools (puede ser vacío)
 * @param calls tool calls de la vuelta, en orden
 */
public record ToolRound(String text, List<Call> calls) {

  public ToolRound {
    text = text == null ? "" : text;
    calls = List.copyOf(calls);
  }

  /**
   * Un tool call y su resultado compacto.
   *
   * @param id id del tool call que generó el modelo
   * @param name nombre de la tool
   * @param arguments argumentos en JSON, como los mandó el modelo
   * @param result resultado compacto en JSON (ids, nombres, precios, cantidades o error)
   */
  public record Call(String id, String name, String arguments, String result) {
  }

  /** Caracteres de los tool calls (id, nombre, argumentos) y sus resultados compactos. */
  public int toolChars() {
    return calls.stream().mapToInt(call -> call.id().length() + call.name().length()
        + call.arguments().length() + call.result().length()).sum();
  }
}
