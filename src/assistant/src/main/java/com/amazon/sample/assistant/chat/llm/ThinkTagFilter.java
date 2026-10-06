package com.amazon.sample.assistant.chat.llm;

import reactor.core.publisher.Flux;

/**
 * Descarta los bloques {@code <think>…</think>} de un stream de fragmentos de
 * texto, aunque las etiquetas lleguen partidas entre fragmentos (D7). Solo se
 * aplica con modelos que mezclan el razonamiento en el texto: Nemotron lo
 * devuelve aparte, en {@code reasoning_content} (spike, D12).
 *
 * <p>Cada suscripción tiene su propio estado.
 */
public final class ThinkTagFilter {

  static final String OPEN = "<think>";
  static final String CLOSE = "</think>";

  private boolean inside;
  private final StringBuilder pending = new StringBuilder();

  private ThinkTagFilter() {
  }

  public static Flux<String> apply(Flux<String> fragments) {
    return Flux.defer(() -> {
      ThinkTagFilter filter = new ThinkTagFilter();
      return fragments.map(filter::next)
          .concatWith(Flux.defer(() -> Flux.just(filter.flush())))
          .filter(text -> !text.isEmpty());
    });
  }

  /** Procesa un fragmento y devuelve el texto visible que ya se puede emitir. */
  String next(String fragment) {
    pending.append(fragment);
    StringBuilder out = new StringBuilder();
    while (true) {
      String tag = inside ? CLOSE : OPEN;
      int index = pending.indexOf(tag);
      if (index >= 0) {
        if (!inside) {
          out.append(pending, 0, index);
        }
        pending.delete(0, index + tag.length());
        inside = !inside;
        continue;
      }
      // Sin etiqueta completa: se retiene solo el sufijo que podría ser el
      // comienzo de una etiqueta partida.
      int keep = partialSuffix(pending, tag);
      int emit = pending.length() - keep;
      if (!inside) {
        out.append(pending, 0, emit);
      }
      pending.delete(0, emit);
      return out.toString();
    }
  }

  /** Texto retenido al terminar el stream: si quedó una etiqueta a medias, era texto. */
  String flush() {
    String rest = inside ? "" : pending.toString();
    pending.setLength(0);
    return rest;
  }

  private static int partialSuffix(CharSequence text, String tag) {
    int max = Math.min(text.length(), tag.length() - 1);
    for (int length = max; length > 0; length--) {
      boolean matches = true;
      for (int i = 0; i < length; i++) {
        if (text.charAt(text.length() - length + i) != tag.charAt(i)) {
          matches = false;
          break;
        }
      }
      if (matches) {
        return length;
      }
    }
    return 0;
  }
}
