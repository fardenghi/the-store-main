package com.amazon.sample.assistant.chat.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** Lock de turno y commit explícito (D2, D6). */
class SessionStateTest {

  @RepeatedTest(20)
  void onlyOneOfTwoConcurrentAcquiresOnTheSameSessionWins() throws Exception {
    SessionState session = new SessionState(10);

    List<Boolean> results = race(() -> session.tryAcquire(new Object()),
        () -> session.tryAcquire(new Object()));

    assertThat(results).containsExactlyInAnyOrder(true, false);
  }

  @RepeatedTest(20)
  void distinctSessionsAcquireInParallel() throws Exception {
    SessionState s1 = new SessionState(10);
    SessionState s2 = new SessionState(10);

    List<Boolean> results = race(() -> s1.tryAcquire(new Object()),
        () -> s2.tryAcquire(new Object()));

    assertThat(results).containsExactly(true, true);
  }

  @Test
  void releaseAllowsTheNextTurn() {
    SessionState session = new SessionState(10);
    Object first = new Object();
    assertThat(session.tryAcquire(first)).isTrue();
    assertThat(session.tryAcquire(new Object())).isFalse();

    session.release(first);

    assertThat(session.tryAcquire(new Object())).isTrue();
  }

  @Test
  void aTurnThatNoLongerOwnsTheLockCannotReleaseItNorCommit() {
    SessionState session = new SessionState(10);
    Object cancelled = new Object();
    Object next = new Object();
    session.tryAcquire(cancelled);
    session.release(cancelled);
    session.tryAcquire(next);

    session.release(cancelled);
    boolean stored = session.commitIfOwner(cancelled, new Turn("old", "late answer"), null);

    assertThat(stored).isFalse();
    assertThat(session.isBusy()).as("el lock sigue siendo del turno nuevo").isTrue();
    assertThat(session.turns()).isEmpty();
    assertThat(session.commitIfOwner(next, new Turn("new", "answer"), null)).isTrue();
    assertThat(session.turns()).containsExactly(new Turn("new", "answer"));
  }

  @Test
  void nothingIsStoredWithoutCommit() {
    SessionState session = new SessionState(10);
    Object turn = new Object();
    session.tryAcquire(turn);
    session.release(turn);

    assertThat(session.turns()).isEmpty();

    session.commit(new Turn("hi", "hello"), null);
    assertThat(session.turns()).containsExactly(new Turn("hi", "hello"));
  }

  private static List<Boolean> race(Callable<Boolean> a, Callable<Boolean> b) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Boolean>> futures = new ArrayList<>();
      for (Callable<Boolean> task : List.of(a, b)) {
        futures.add(executor.submit(() -> {
          start.await();
          return task.call();
        }));
      }
      start.countDown();
      List<Boolean> results = new ArrayList<>();
      for (Future<Boolean> future : futures) {
        results.add(future.get());
      }
      return results;
    } finally {
      executor.shutdownNow();
    }
  }
}
