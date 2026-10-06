package com.amazon.sample.assistant.chat.context;

import com.amazon.sample.assistant.chat.session.ShownProduct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resultado del retrieval de un turno (D5).
 *
 * @param searched si el turno buscó en el catálogo (no lo hace con intent {@code other})
 * @param catalogUnavailable si la búsqueda falló por el índice o por los embeddings
 * @param found productos encontrados en este turno, ya filtrados
 * @param previous productos mostrados en el turno anterior
 * @param includePrevious si los productos previos se suman a los del turno (comparaciones)
 * @param latencyMillis duración de la búsqueda
 */
public record Retrieval(
    boolean searched,
    boolean catalogUnavailable,
    List<ShownProduct> found,
    List<ShownProduct> previous,
    boolean includePrevious,
    long latencyMillis) {

  public Retrieval {
    found = List.copyOf(found);
    previous = List.copyOf(previous);
  }

  /**
   * Productos del evento {@code products} y de la memoria del turno: los
   * encontrados y, en las comparaciones, primero los del turno anterior, sin
   * repetidos.
   */
  public List<ShownProduct> products() {
    if (!includePrevious) {
      return found;
    }
    Map<String, ShownProduct> merged = new LinkedHashMap<>();
    previous.forEach(p -> merged.put(p.id(), p));
    found.forEach(p -> merged.putIfAbsent(p.id(), p));
    return List.copyOf(new ArrayList<>(merged.values()));
  }
}
