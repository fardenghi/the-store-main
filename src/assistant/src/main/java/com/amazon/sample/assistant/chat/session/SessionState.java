package com.amazon.sample.assistant.chat.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Estado de una sesión de chat (D6): ventana de los últimos turnos, productos
 * del último turno con búsqueda y el lock que permite un solo turno a la vez
 * (D2).
 *
 * <p>Un turno se guarda solo con {@link #commit}, que el turno llama cuando el
 * stream terminó bien: un turno fallido o cancelado no deja rastro.
 *
 * <p>El lock tiene dueño (el turno que lo tomó). Un turno cancelado libera el
 * lock apenas se detecta el corte, aunque su llamada al modelo siga en vuelo;
 * con {@link #commitIfOwner} ese turno ya no puede escribir en la memoria ni
 * liberar el lock del turno siguiente.
 */
public class SessionState {

  private final int maxTurns;
  private final Deque<Turn> turns = new ArrayDeque<>();
  private List<ShownProduct> lastProducts = List.of();
  private final AtomicReference<Object> owner = new AtomicReference<>();

  public SessionState(int maxTurns) {
    this.maxTurns = maxTurns;
  }

  /**
   * Toma el lock para un turno.
   *
   * @param turn el dueño del lock
   * @return {@code false} si ya hay un turno en curso
   */
  public boolean tryAcquire(Object turn) {
    return owner.compareAndSet(null, turn);
  }

  /** Libera el lock si lo tiene ese turno; si ya es de otro, no hace nada. */
  public synchronized void release(Object turn) {
    owner.compareAndSet(turn, null);
  }

  public boolean isBusy() {
    return owner.get() != null;
  }

  /** Turnos guardados, del más viejo al más nuevo. */
  public synchronized List<Turn> turns() {
    return List.copyOf(turns);
  }

  /** Los últimos {@code n} turnos, del más viejo al más nuevo. */
  public synchronized List<Turn> lastTurns(int n) {
    List<Turn> all = new ArrayList<>(turns);
    return List.copyOf(all.subList(Math.max(0, all.size() - n), all.size()));
  }

  /** Productos mostrados en el último turno con búsqueda (vacío si no hubo). */
  public synchronized List<ShownProduct> lastProducts() {
    return lastProducts;
  }

  /**
   * Guarda un turno terminado. Si pasa de la ventana, descarta el más viejo.
   *
   * @param turn mensaje del usuario y respuesta final
   * @param products productos mostrados en el turno, o {@code null} si el turno
   *     no hizo búsqueda (se conservan los del turno anterior)
   */
  public synchronized void commit(Turn turn, List<ShownProduct> products) {
    turns.addLast(turn);
    while (turns.size() > maxTurns) {
      turns.removeFirst();
    }
    if (products != null) {
      lastProducts = List.copyOf(products);
    }
  }

  /**
   * Igual que {@link #commit}, pero solo si el turno todavía tiene el lock. Es
   * atómico respecto de {@link #release}: un turno cancelado (que ya lo
   * liberó) no guarda nada aunque su respuesta termine después.
   *
   * @return {@code false} si el turno ya no tiene el lock y no se guardó
   */
  public synchronized boolean commitIfOwner(Object owner, Turn turn,
      List<ShownProduct> products) {
    if (this.owner.get() != owner) {
      return false;
    }
    commit(turn, products);
    return true;
  }
}
