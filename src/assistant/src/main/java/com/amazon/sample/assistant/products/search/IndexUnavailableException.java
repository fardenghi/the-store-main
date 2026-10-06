package com.amazon.sample.assistant.products.search;

/** La colección no existe o está vacía ({@code 503 index-unavailable}). */
public class IndexUnavailableException extends RuntimeException {

  public IndexUnavailableException() {
    super("El índice de productos no está disponible: la sincronización todavía no terminó "
        + "o falló");
  }
}
