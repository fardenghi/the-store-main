package com.amazon.sample.assistant.products.search;

/** Un parámetro de la búsqueda o de los similares es inválido ({@code 400}). */
public class InvalidParameterException extends RuntimeException {

  private final String parameter;

  public InvalidParameterException(String parameter, String message) {
    super(message);
    this.parameter = parameter;
  }

  public String parameter() {
    return parameter;
  }
}
