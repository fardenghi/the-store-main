package com.amazon.sample.assistant.chat.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Reloj falso que solo avanza cuando el test lo pide. */
public class MutableClock extends Clock {

  private volatile Instant now;

  public MutableClock(Instant start) {
    this.now = start;
  }

  public void advance(Duration duration) {
    now = now.plus(duration);
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }
}
