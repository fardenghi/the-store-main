package com.amazon.sample.assistant.chat.rewrite;

import java.util.Locale;

/** Intención del mensaje según la reescritura (D4). */
public enum Intent {
  /** Pide productos: se busca en el catálogo. */
  SEARCH,
  /** Pide comparar productos: se busca, se suman los del turno anterior y se razona (D7). */
  COMPARE,
  /** Saludos, agradecimientos o charla: no se busca. */
  OTHER;

  /** Valor tal como lo escribe el modelo y como se loguea. */
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** La intención con ese valor, o {@code null} si no es ninguna conocida. */
  public static Intent parse(String value) {
    if (value == null) {
      return null;
    }
    for (Intent intent : values()) {
      if (intent.value().equals(value.trim().toLowerCase(Locale.ROOT))) {
        return intent;
      }
    }
    return null;
  }
}
