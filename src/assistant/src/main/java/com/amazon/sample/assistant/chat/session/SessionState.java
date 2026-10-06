package com.amazon.sample.assistant.chat.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Estado de una sesión de chat (D6): ventana de los últimos turnos, productos
 * del último turno con búsqueda y el lock que permite un solo turno a la vez
 * (D2).
 *
 * <p>Un turno se guarda solo con {@link #commit}, que el turno llama cuando el
 * stream terminó bien: un turno fallido o cancelado no deja rastro.
 */
public class SessionState {

  private final int maxTurns;
  private final Deque<Turn> turns = new ArrayDeque<>();
  private List<ShownProduct> lastProducts = List.of();
  private final AtomicBoolean busy = new AtomicBoolean();

  public SessionState(int maxTurns) {
    this.maxTurns = maxTurns;
  }

  /** Toma el lock del turno. Devuelve {@code false} si ya hay un turno en curso. */
  public boolean tryAcquire() {
    return busy.compareAndSet(false, true);
  }

  /** Libera el lock del turno. */
  public void release() {
    busy.set(false);
  }

  public boolean isBusy() {
    return busy.get();
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
}
