package com.amazon.sample.assistant.products.index;

import com.amazon.sample.assistant.products.catalog.CatalogProduct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;

/**
 * Texto que se embebe por producto y su hash de contenido (D3).
 *
 * <pre>
 * &lt;name&gt;
 * Tags: &lt;displayName 1&gt;, &lt;displayName 2&gt;, ...
 * &lt;description&gt;
 * </pre>
 *
 * <p>Versión 2: los tags van antes de la descripción. Con la versión 1 (tags
 * al final) los similares de "Velvet Texture Throw Pillow" no compartían su
 * tag de tipo; ver el resultado del smoke en el README.
 *
 * <p>El precio no forma parte del texto ni del hash: cambiarlo no requiere
 * volver a embeber.
 */
public final class EmbeddingText {

  /**
   * Versión de la plantilla (1: tags al final; 2: tags antes de la descripción). Subirla invalida todos los vectores en la
   * siguiente sincronización, sin borrar la colección a mano.
   */
  public static final String TEMPLATE_VERSION = "2";

  private EmbeddingText() {
  }

  public static String of(CatalogProduct product) {
    String tags = product.tags().stream()
        .map(CatalogProduct.Tag::displayName)
        .collect(Collectors.joining(", "));
    return product.name() + "\nTags: " + tags + "\n" + product.description();
  }

  /** {@code sha256(modelo + ":" + dimensiones + ":" + versión + "\n" + texto)} en hexadecimal. */
  public static String contentHash(String model, int dimensions, String text) {
    return contentHash(model, dimensions, TEMPLATE_VERSION, text);
  }

  static String contentHash(String model, int dimensions, String templateVersion, String text) {
    String input = model + ":" + dimensions + ":" + templateVersion + "\n" + text;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 no disponible", e);
    }
  }
}
