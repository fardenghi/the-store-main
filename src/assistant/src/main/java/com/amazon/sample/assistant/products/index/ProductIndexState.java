package com.amazon.sample.assistant.products.index;

import java.time.Instant;

/**
 * Estado en memoria de la indexación (D7). Lo actualiza
 * {@link ProductIndexer} y lo expone {@link ProductIndexHealthIndicator}.
 */
public class ProductIndexState {

  /** Fase de la sincronización. */
  public enum Phase { NOT_STARTED, SYNCING, READY, FAILED }

  /** Vista inmutable del estado en un momento dado. */
  public record Snapshot(
      Phase phase,
      long points,
      Instant lastSync,
      SyncReport lastReport,
      String error) {
  }

  private volatile Snapshot snapshot = new Snapshot(Phase.NOT_STARTED, 0, null, null, null);

  public Snapshot snapshot() {
    return snapshot;
  }

  public Phase phase() {
    return snapshot.phase();
  }

  synchronized void syncing() {
    Snapshot current = snapshot;
    snapshot = new Snapshot(Phase.SYNCING, current.points(), current.lastSync(),
        current.lastReport(), null);
  }

  synchronized void ready(long points, Instant at, SyncReport report) {
    snapshot = new Snapshot(Phase.READY, points, at, report, null);
  }

  synchronized void failed(String error) {
    Snapshot current = snapshot;
    snapshot = new Snapshot(Phase.FAILED, current.points(), current.lastSync(),
        current.lastReport(), error);
  }
}
