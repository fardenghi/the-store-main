package com.amazon.sample.assistant.chat.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Memoria por sesión (D6): ventana de turnos, expiración por inactividad y aislamiento. */
class SessionStoreTest {

  private final AtomicLong nanos = new AtomicLong();
  private final SessionStore store =
      new SessionStore(10, Duration.ofMinutes(30), 10_000, nanos::get);

  private static ShownProduct product(String id, long price) {
    return new ShownProduct(id, "Product " + id, "desc", price, List.of("seating"));
  }

  @Test
  void keepsTheLastTenTurns() {
    SessionState session = store.get("s1");
    IntStream.rangeClosed(1, 11).forEach(i ->
        session.commit(new Turn("user " + i, "answer " + i), null));

    List<Turn> turns = store.get("s1").turns();
    assertThat(turns).hasSize(10);
    assertThat(turns.get(0).user()).isEqualTo("user 2");
    assertThat(turns.get(9).user()).isEqualTo("user 11");
    assertThat(store.get("s1").lastTurns(3)).extracting(Turn::user)
        .containsExactly("user 9", "user 10", "user 11");
  }

  @Test
  void keepsProductsOfTheLastTurnWithSearch() {
    SessionState session = store.get("s1");
    session.commit(new Turn("armchairs", "here"), List.of(product("a", 139)));
    session.commit(new Turn("thanks", "you're welcome"), null);

    assertThat(store.get("s1").lastProducts()).extracting(ShownProduct::id).containsExactly("a");

    session.commit(new Turn("rugs", "here"), List.of(product("r", 99)));
    assertThat(store.get("s1").lastProducts()).extracting(ShownProduct::id).containsExactly("r");
  }

  @Test
  void idleSessionComesBackEmpty() {
    store.get("s1").commit(new Turn("hi", "hello"), List.of(product("a", 1)));

    nanos.addAndGet(Duration.ofMinutes(29).toNanos());
    assertThat(store.get("s1").turns()).hasSize(1);

    // 29 minutos después del último acceso todavía vive; 31 minutos sin uso, no.
    nanos.addAndGet(Duration.ofMinutes(31).toNanos());
    SessionState expired = store.get("s1");
    assertThat(expired.turns()).isEmpty();
    assertThat(expired.lastProducts()).isEmpty();
  }

  @Test
  void distinctIdsDoNotShareState() {
    store.get("s1").commit(new Turn("armchairs", "here"), List.of(product("a", 139)));

    SessionState other = store.get("s2");
    assertThat(other.turns()).isEmpty();
    assertThat(other.lastProducts()).isEmpty();
    assertThat(other).isNotSameAs(store.get("s1"));
  }
}
