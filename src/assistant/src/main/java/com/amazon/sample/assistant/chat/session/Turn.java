package com.amazon.sample.assistant.chat.session;

/**
 * Un turno guardado en la memoria de la sesión: el mensaje del usuario y la
 * respuesta final del asistente, sin el razonamiento ni el contexto RAG (D6).
 */
public record Turn(String user, String assistant) {
}
