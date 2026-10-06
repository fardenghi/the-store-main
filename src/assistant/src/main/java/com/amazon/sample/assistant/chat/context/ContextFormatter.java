package com.amazon.sample.assistant.chat.context;

import com.amazon.sample.assistant.chat.session.ShownProduct;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Arma la sección de contexto del system prompt (D5): una línea por producto
 * con id, nombre, precio, tags y descripción, los productos del turno anterior
 * en una sección aparte y el aviso de catálogo no disponible.
 */
public final class ContextFormatter {

  static final int MAX_DESCRIPTION_LENGTH = 400;

  static final String UNAVAILABLE = """
      CATALOG UNAVAILABLE: the product catalog cannot be searched right now. Tell the user that \
      you cannot check the catalog at the moment and suggest trying again later. Do not name, \
      describe or recommend any product.""";

  private ContextFormatter() {
  }

  public static String format(Retrieval retrieval) {
    if (retrieval.catalogUnavailable()) {
      return UNAVAILABLE;
    }
    StringBuilder out = new StringBuilder();
    if (!retrieval.searched()) {
      out.append("No catalog search was made for this message.");
    } else if (retrieval.found().isEmpty()) {
      out.append("Products found for this message: none. No product in the catalog matches it.");
    } else {
      out.append("Products found for this message (most relevant first):\n");
      out.append(lines(retrieval.found()));
    }
    if (retrieval.previous().isEmpty()) {
      out.append("\n\nPreviously shown products: none.");
    } else {
      out.append("\n\nPreviously shown products (from the previous turn, in the order they "
          + "were shown):\n");
      out.append(lines(retrieval.previous()));
    }
    return out.toString();
  }

  static String line(ShownProduct p) {
    String description = p.description() == null ? "" : p.description().replace('\n', ' ');
    if (description.length() > MAX_DESCRIPTION_LENGTH) {
      description = description.substring(0, MAX_DESCRIPTION_LENGTH) + "…";
    }
    return "- [" + p.id() + "] " + p.name() + " | $" + p.price() + " | tags: "
        + String.join(", ", p.tags()) + " | " + description;
  }

  private static String lines(List<ShownProduct> products) {
    return products.stream().map(ContextFormatter::line).collect(Collectors.joining("\n"));
  }
}
