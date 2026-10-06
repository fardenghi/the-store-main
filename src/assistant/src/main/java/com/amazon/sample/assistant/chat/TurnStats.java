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
  /** Solicitudes al modelo principal (vueltas del ciclo de tools, con sus reintentos). */
  volatile int modelCalls;
  /** Espera total en el limitador de NVIDIA. */
  volatile long limiterWaitMillis;
  /** Reintentos por 429 del modelo principal. */
  volatile int retries429;
  /** Resultado de cada tool, por ejemplo {@code searchProducts:ok}. */
  volatile List<String> tools = List.of();

  TurnStats(String sessionId, String message) {
    this.sessionId = sessionId;
    this.message = message;
  }

  long elapsedMillis() {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
