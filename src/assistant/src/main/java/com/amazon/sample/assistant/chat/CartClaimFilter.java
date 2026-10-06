package com.amazon.sample.assistant.chat;

import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Salvaguarda del servidor para la spec "Confirmación fiel de las acciones"
 * (corrección posterior de {@code add-assistant-tools}): el texto del modelo
 * pasa por acá oración por oración, y una oración que afirma que algo se agregó
 * al carrito se descarta si en el turno no hubo un {@code addToCart} correcto.
 *
 * <p>Cada oración se retiene hasta que termina ({@code .}, {@code !} o
 * {@code ?} seguidos de un espacio o al final del fragmento, o un salto de
 * línea), así que el streaming pasa a ser por oración. Una afirmación es una
 * cláusula con un verbo de agregado consumado o en curso ("have been added",
 * "adding … to your cart", "agregué", "*adds two lamps to cart*"), que no es
 * una pregunta y no está negada, condicionada ni ofrecida ("I didn't add
 * anything", "if you want", "you can add it to your cart"). Las reglas son
 * conservadoras: ante la duda, el texto pasa.
 */
final class CartClaimFilter {

  private static final Logger log = LoggerFactory.getLogger(CartClaimFilter.class);

  /** Largo máximo de la oración descartada en el log. */
  private static final int LOGGED_CHARS = 160;

  private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
      | Pattern.UNICODE_CHARACTER_CLASS;

  private static final String APOSTROPHE = "['’]";

  /** El carrito, en inglés, en castellano o con la jerga de la persona. */
  private static final Pattern CART = Pattern.compile(
      "\\b(cart|basket|inventory|carrito|canasta|cesta|inventario)\\b", FLAGS);

  /**
   * "added" como acción consumada sin necesidad de nombrar el carrito: con un
   * auxiliar ("have been added"), al final de la cláusula ("two lamps added."),
   * al principio ("Added!") o el nombre de la tool.
   */
  private static final Pattern ADDED = Pattern.compile(
      "\\b(been|has|have|had|i" + APOSTROPHE + "ve|we" + APOSTROPHE + "ve|successfully|just|all"
          + "|was|were|is|are)\\s+(\\w+\\s+)?added\\b"
          + "|\\badded\\s*([.!—–-]|$)|^\\W*added\\b|\\baddtocart\\b", FLAGS);

  /** "added" que solo afirma un agregado si la oración nombra el carrito ("I added … to cart"). */
  private static final Pattern ADDED_TO = Pattern.compile(
      "\\b(i|we|now)\\s+(\\w+\\s+)?added\\b|\\badded\\s+to\\b", FLAGS);

  /** Pretéritos y participios en castellano. */
  private static final Pattern AGREGADO = Pattern.compile(
      "\\b(agregu[eé]|agregó|agregamos|añad[ií]|añadi[oó]|sum[eé]|sumó"
          + "|(fue|fueron|ha|han|he|hemos|ya|quedó|quedaron|está|están)\\s+(\\w+\\s+)?"
          + "(agregad[oa]s?|añadid[oa]s?|sumad[oa]s?))\\b", FLAGS);

  /**
   * Verbos que solo afirman un agregado si la oración nombra el carrito. Las
   * formas futuras ("I'll add it", "agregaré") no están: son ofrecimientos, como
   * "tell me which one and I'll add it to your cart".
   */
  private static final Pattern ADDING = Pattern.compile(
      "\\b(adding|adds|deployed|secured|stashed|loaded|placed|now in|already in|agregando"
          + "|añadiendo|ya est[aá]n? en)\\b", FLAGS);

  /** Una acotación de la persona que narra el agregado: "*adds two lamps to cart*". */
  private static final Pattern STAGE_DIRECTION = Pattern.compile(
      "(?<!\\*)\\*(?!\\*)[^*\\n]*\\b(adds|adding|added|agrega|agregando|añade|añadiendo)\\b"
          + "[^*\\n]*(?<!\\*)\\*(?!\\*)", FLAGS);

  /** Negaciones, condiciones y ofrecimientos: la cláusula no afirma nada. */
  private static final Pattern NOT_A_CLAIM = Pattern.compile(
      "\\b(not|never|nothing|none|unable|cannot|can" + APOSTROPHE + "t|couldn" + APOSTROPHE + "t"
          + "|didn" + APOSTROPHE + "t|wasn" + APOSTROPHE + "t|weren" + APOSTROPHE + "t"
          + "|haven" + APOSTROPHE + "t|hasn" + APOSTROPHE + "t|won" + APOSTROPHE + "t"
          + "|isn" + APOSTROPHE + "t|aren" + APOSTROPHE + "t|don" + APOSTROPHE + "t"
          + "|doesn" + APOSTROPHE + "t|failed|without|before|after|if|once|when|whenever|can|could|may"
          + "|might|would|should|want|wanna|ready to|shall|let me know|please|specify|which"
          + "|choose|pick|prefer|like|proceed|need you|need to know"
          + "|nada|nunca|ningún|ninguno|ninguna|sin|tampoco|si|cuando|apenas|podés|puedes"
          + "|podrías|podría|querés|quieres|quisieras|avisame|avísame)\\b"
          + "|(^|[^\\p{L}])no(?![\\p{L}])(?!\\s+(problem|worries)\\b)", FLAGS);

  /** Una oración condicional: "If so, I'll proceed with adding them to your cart." */
  private static final Pattern CONDITIONAL = Pattern.compile(
      "^\\W*(if|once|when|whenever|unless|si|cuando|apenas|una vez)\\b", FLAGS);

  /** Separadores de cláusulas dentro de una oración. */
  private static final Pattern CLAUSES = Pattern.compile("[,;:—–]|\\s-\\s|\\band\\b|\\by\\b",
      FLAGS);

  private final BooleanSupplier addedInTurn;
  private final UnaryOperator<String> intercept;
  private final StringBuilder pending = new StringBuilder();
  private int dropped;

  /**
   * @param addedInTurn si ya hubo un {@code addToCart} correcto en el turno; con
   *     eso, las afirmaciones de agregado pasan
   */
  CartClaimFilter(BooleanSupplier addedInTurn) {
    this(addedInTurn, UnaryOperator.identity());
  }

  /**
   * @param intercept se aplica a cada oración antes de juzgarla; el ciclo lo usa
   *     para sacar los tool calls escritos como texto ({@link TextualToolCalls})
   */
  CartClaimFilter(BooleanSupplier addedInTurn, UnaryOperator<String> intercept) {
    this.addedInTurn = addedInTurn;
    this.intercept = intercept;
  }

  /** Suma un fragmento y devuelve el texto de las oraciones completas que se pueden emitir. */
  String accept(String fragment) {
    pending.append(fragment);
    StringBuilder out = new StringBuilder();
    int end;
    while ((end = sentenceEnd(pending)) > 0) {
      String sentence = pending.substring(0, end);
      pending.delete(0, end);
      out.append(judge(sentence));
    }
    return out.toString();
  }

  /** Fin de la vuelta: juzga lo que quedó retenido y lo devuelve si se puede emitir. */
  String flush() {
    String rest = pending.toString();
    pending.setLength(0);
    return judge(rest);
  }

  /** Oraciones descartadas por afirmar un agregado que no ocurrió. */
  int dropped() {
    return dropped;
  }

  private String judge(String original) {
    String sentence = original.isEmpty() ? original : intercept.apply(original);
    if (!sentence.isBlank() && !addedInTurn.getAsBoolean() && isClaim(sentence)) {
      dropped++;
      String logged = sentence.strip().replace('\n', ' ');
      log.info("Oración descartada por afirmar un agregado sin addToCart: \"{}\"",
          logged.length() <= LOGGED_CHARS ? logged : logged.substring(0, LOGGED_CHARS) + "…");
      return sentence.endsWith("\n") ? "\n" : "";
    }
    return sentence;
  }

  /** Si la oración afirma que se agregó (o se está agregando) algo al carrito. */
  static boolean isClaim(String sentence) {
    String text = sentence.strip();
    if (text.isEmpty() || text.contains("¿") || text.replaceAll("[\\s*_\"')\\]]+$", "")
        .endsWith("?")) {
      return false;
    }
    if (STAGE_DIRECTION.matcher(text).find()) {
      return true;
    }
    if (CONDITIONAL.matcher(text).find()) {
      return false;
    }
    boolean mentionsCart = CART.matcher(text).find();
    for (String clause : CLAUSES.split(text)) {
      if (NOT_A_CLAIM.matcher(clause).find()) {
        continue;
      }
      String trimmed = clause.strip();
      if (ADDED.matcher(trimmed).find() || AGREGADO.matcher(trimmed).find()
          || (mentionsCart && (ADDED_TO.matcher(trimmed).find()
              || ADDING.matcher(trimmed).find()))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Fin de la primera oración completa del texto (índice después del
   * terminador), o {@code -1} si todavía no terminó ninguna. Un punto seguido de
   * un dígito ({@code $1.5}) no termina la oración. Uno al final del texto sí,
   * para no demorar el fragmento hasta que llegue el siguiente.
   */
  static int sentenceEnd(CharSequence text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n') {
        return i + 1;
      }
      if (c == '.' || c == '!' || c == '?') {
        int j = i + 1;
        while (j < text.length() && "\"')]*_”’".indexOf(text.charAt(j)) >= 0) {
          j++;
        }
        if (j == text.length() || Character.isWhitespace(text.charAt(j))) {
          return j;
        }
      }
    }
    return -1;
  }
}
