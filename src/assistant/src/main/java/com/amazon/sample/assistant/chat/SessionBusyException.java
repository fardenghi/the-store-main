package com.amazon.sample.assistant.chat;

/** La sesión ya tiene un turno en curso ({@code 409 session-busy}). */
public class SessionBusyException extends RuntimeException {

  public SessionBusyException() {
    super("La sesión ya tiene un turno en curso; esperá a que termine");
  }
}
