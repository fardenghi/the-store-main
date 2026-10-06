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

    List<Boolean> results = race(session::tryAcquire, session::tryAcquire);

    assertThat(results).containsExactlyInAnyOrder(true, false);
  }

  @RepeatedTest(20)
  void distinctSessionsAcquireInParallel() throws Exception {
    SessionState s1 = new SessionState(10);
    SessionState s2 = new SessionState(10);

    List<Boolean> results = race(s1::tryAcquire, s2::tryAcquire);

    assertThat(results).containsExactly(true, true);
  }

  @Test
  void releaseAllowsTheNextTurn() {
    SessionState session = new SessionState(10);
    assertThat(session.tryAcquire()).isTrue();
    assertThat(session.tryAcquire()).isFalse();

    session.release();

    assertThat(session.tryAcquire()).isTrue();
  }

  @Test
  void nothingIsStoredWithoutCommit() {
    SessionState session = new SessionState(10);
    session.tryAcquire();
    session.release();

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
