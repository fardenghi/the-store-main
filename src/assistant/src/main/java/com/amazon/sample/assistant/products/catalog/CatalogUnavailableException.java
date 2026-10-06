package com.amazon.sample.assistant.products.catalog;

/**
 * Una lectura de las tools al {@code catalog} falló aun con su reintento (5xx,
 * timeout o error de red) o con un error que no se reintenta (D5 de
 * {@code add-assistant-tools}).
 */
public class CatalogUnavailableException extends RuntimeException {

  public CatalogUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
