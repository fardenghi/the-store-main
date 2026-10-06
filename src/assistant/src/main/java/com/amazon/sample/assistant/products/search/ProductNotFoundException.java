package com.amazon.sample.assistant.products.search;

/** El id no corresponde a un producto indexado ({@code 404}). */
public class ProductNotFoundException extends RuntimeException {

  public ProductNotFoundException(String id) {
    super("No hay un producto indexado con id " + id);
  }
}
