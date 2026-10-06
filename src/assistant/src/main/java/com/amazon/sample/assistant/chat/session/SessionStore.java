package com.amazon.sample.assistant.chat.session;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;

/**
 * Sesiones de chat en memoria del proceso (D6): una por {@code X-Session-ID},
 * descartada después de {@code idle-ttl} sin uso y con un máximo de sesiones.
 * La memoria se pierde al reiniciar, que es aceptable para el POC con una
 * réplica.
 */
public class SessionStore {

  private final Cache<String, SessionState> sessions;
  private final int maxTurns;

  public SessionStore(int maxTurns, Duration idleTtl, long maxSessions) {
    this(maxTurns, idleTtl, maxSessions, Ticker.systemTicker());
  }

  /** Con un {@link Ticker} propio, para probar la expiración sin esperar. */
  public SessionStore(int maxTurns, Duration idleTtl, long maxSessions, Ticker ticker) {
    this.maxTurns = maxTurns;
    this.sessions = Caffeine.newBuilder()
        .expireAfterAccess(idleTtl)
        .maximumSize(maxSessions)
        .ticker(ticker)
        .build();
  }

  /** La sesión con ese id; si no existe o expiró, una vacía. */
  public SessionState get(String sessionId) {
    return sessions.get(sessionId, id -> new SessionState(maxTurns));
  }
}
