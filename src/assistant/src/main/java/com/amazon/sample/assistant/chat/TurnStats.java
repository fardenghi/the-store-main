package com.amazon.sample.assistant.chat;

import com.amazon.sample.assistant.chat.context.Retrieval;
import com.amazon.sample.assistant.chat.rewrite.Rewrite;
import java.util.List;

/** Datos de un turno para su línea de log (D9). Los completa el pipeline. */
class TurnStats {

  final String sessionId;
  final String message;
  final long startNanos = System.nanoTime();
  volatile Rewrite rewrite;
  volatile Retrieval retrieval;
  volatile boolean reasoning;
  volatile long reasoningChars;
  volatile long firstFragmentMillis = -1;
  volatile long totalMillis = -1;
  volatile String outcome = "cancelled";
  volatile List<String> rawTopK;

  TurnStats(String sessionId, String message) {
    this.sessionId = sessionId;
    this.message = message;
  }

  long elapsedMillis() {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
