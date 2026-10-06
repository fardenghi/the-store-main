package com.amazon.sample.assistant.carts;

/** El servicio {@code carts} no respondió o respondió un error. */
public class CartsUnavailableException extends RuntimeException {

  public CartsUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
