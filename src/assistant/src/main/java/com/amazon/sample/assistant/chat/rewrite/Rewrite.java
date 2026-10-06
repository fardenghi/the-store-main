package com.amazon.sample.assistant.chat.rewrite;

import java.util.List;

/**
 * Reescritura validada que usa el turno (D4).
 *
 * @param intent intención del mensaje
 * @param query consulta de búsqueda; con {@code fallback}, el mensaje crudo
 * @param minPrice precio mínimo inclusivo, o {@code null}
 * @param maxPrice precio máximo inclusivo, o {@code null}
 * @param excludeTags tags cuyos productos se excluyen de los resultados
 * @param fallback si se usó el mensaje crudo porque la reescritura falló
 * @param providerRequests solicitudes hechas al modelo de reescritura (0 o 1)
 * @param latencyMillis duración de la reescritura
 */
public record Rewrite(
    Intent intent,
    String query,
    Integer minPrice,
    Integer maxPrice,
    List<String> excludeTags,
    boolean fallback,
    int providerRequests,
    long latencyMillis) {

  public Rewrite {
    excludeTags = excludeTags == null ? List.of() : List.copyOf(excludeTags);
  }

  /** El mensaje crudo como consulta, sin filtros. */
  public static Rewrite fallback(String message, int providerRequests, long latencyMillis) {
    return new Rewrite(Intent.SEARCH, message, null, null, List.of(), true, providerRequests,
        latencyMillis);
  }
}
