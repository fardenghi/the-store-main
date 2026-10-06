package com.amazon.sample.assistant.chat;

import java.util.regex.Pattern;

/**
 * Detecta una respuesta que termina anunciando una acción que no hizo
 * (corrección posterior de {@code add-assistant-tools}): "Let me check our
 * inventory for that item.", "I need to search for it first." o la acotación
 * "*getting current price for the Aiden*". {@code nemotron-3-super} a veces
 * escribe el anuncio y cierra la vuelta sin pedir la tool, y el turno terminaba
 * sin hacer nada.
 *
 * <p>Solo mira la última oración. No cuentan las preguntas ni los
 * ofrecimientos que esperan al usuario ("let me know which one and I'll add
 * it", "once you confirm, I'll check").
 */
final class ActionAnnouncement {

  private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
      | Pattern.UNICODE_CHARACTER_CLASS;

  private static final String VERBS = "(check|search|look|pull|get|find|scan|verify|run|fetch"
      + "|retrieve|consult|access|query|add|buscar|busco|revisar|reviso|consultar|consulto"
      + "|verificar|verifico|agregar|agrego|añadir|añado|chequear|chequeo)";

  /** "let me check", "I'll search", "I need to get", "voy a buscar". */
  private static final Pattern ANNOUNCED = Pattern.compile(
      "\\b(let me|i['’]ll|i will|i need to|i['’]m going to|i am going to|allow me to"
          + "|stand by while i|déjame|dejame|voy a|necesito|primero)\\b[^.!?\\n]{0,40}?\\b"
          + VERBS + "\\w*\\b", FLAGS);

  /**
   * Una acotación final que narra una consulta: "*getting current price*",
   * "*searches catalog*". Las de color ("*checks watch*", "*adjusts earpiece*") no.
   */
  private static final Pattern STAGE_DIRECTION = Pattern.compile(
      "^\\*[^*\\n]*\\b(search\\w*|checking|pulls|pulling|getting|access\\w*|consult\\w*|scan\\w*"
          + "|quer\\w*|fetch\\w*|looking up|retriev\\w*)\\b[^*\\n]*\\*$", FLAGS);

  /** La oración espera algo del usuario: no es un anuncio pendiente. */
  private static final Pattern WAITS_FOR_USER = Pattern.compile(
      "\\b(let me know|which|if|once|when|whenever|ready|tell me|confirm|whether|prefer|want"
          + "|would you|decime|dime|avisame|avísame|cuál|cual|si|cuando)\\b", FLAGS);

  private ActionAnnouncement() {
  }

  /** Si el texto termina con una oración que anuncia una acción pendiente. */
  static boolean endsWithAnnouncement(CharSequence text) {
    String last = lastSentence(text.toString());
    if (last.isEmpty() || last.endsWith("?") || last.contains("¿")
        || WAITS_FOR_USER.matcher(last).find()) {
      return false;
    }
    return ANNOUNCED.matcher(last).find() || STAGE_DIRECTION.matcher(last).find();
  }

  private static String lastSentence(String text) {
    String trimmed = text.strip();
    int start = 0;
    for (int i = 0; i < trimmed.length() - 1; i++) {
      char c = trimmed.charAt(i);
      if (c == '\n' || ((c == '.' || c == '!' || c == '?')
          && Character.isWhitespace(trimmed.charAt(i + 1)))) {
        start = i + 1;
      }
    }
    // Una acotación final es su propia oración: "Let me see. *getting the price*".
    String last = trimmed.substring(start).strip();
    int stage = last.lastIndexOf('*', last.length() - 2);
    if (last.endsWith("*") && stage >= 0 && !last.startsWith("*")) {
      last = last.substring(stage).strip();
    }
    return last;
  }
}
