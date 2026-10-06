package com.amazon.sample.assistant.products.catalog;

/** La lectura del catálogo falló sin posibilidad de reintento (por ejemplo, un 4xx). */
public class CatalogReadException extends RuntimeException {

  public CatalogReadException(String message, Throwable cause) {
    super(message, cause);
  }
}
