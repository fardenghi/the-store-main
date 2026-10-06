package com.amazon.sample.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Salvaguarda de agregados al carrito (corrección posterior de
 * {@code add-assistant-tools}): qué oraciones cuentan como afirmación de un
 * agregado y cómo se retiene el texto por oración.
 */
class CartClaimFilterTest {

  /** Afirmaciones reales del reporte de {@code integrate-ui-assistant} y variantes. */
  @ParameterizedTest
  @ValueSource(strings = {
      "Two Aiden Mid-Century Velvet Armchairs have been successfully added.",
      "Two Curved Brass and Walnut Desk Lamps at $149 each have been added to your mission "
          + "inventory.",
      "*adds two Curved Brass and Walnut Desk Lamps to cart*",
      "executes addToCart command",
      "One Adjustable Pharmacy Desk Lamp ($219) has been added to your mission inventory.",
      "Mission accomplished, Operative. Two lamps have been successfully deployed to your cart.",
      "Consider it done: two lamps added.",
      "Added! Your lair just got brighter.",
      "I added two lamps to your cart.",
      "I'm adding the Aiden to your mission inventory now.",
      "Adding two lamps to your cart now.",
      "No problem, Operative: the Aiden is now in your cart.",
      "Listo, agregué dos lámparas a tu carrito.",
      "Las dos lámparas ya fueron agregadas al carrito.",
      "Ya están en tu carrito, Operativo.",
      "Añadí el sillón Aiden a tu inventario de misión."
  })
  void claimsOfAnAddAreDetected(String sentence) {
    assertThat(CartClaimFilter.isClaim(sentence)).as(sentence).isTrue();
  }

  /** Texto legítimo que no puede borrarse (condición del coordinador). */
  @ParameterizedTest
  @ValueSource(strings = {
      "You can add it to your cart from the product page.",
      "I didn't add anything to your cart.",
      "Nothing was added to your cart in this turn.",
      "The cart service is down, so the lamp was not added.",
      "Want me to add the Aiden to your mission inventory?",
      "Shall I add two of them to your cart, Operative?",
      "Ready to add this to your mission inventory, or shall we continue reconnaissance?",
      "If so, I'll proceed with adding them to your mission inventory.",
      "Once you confirm which lamp, I'll add it to your cart.",
      "Tell me which one and I'll add it to your cart.",
      "Please specify which one you'd like deployed to your cart.",
      "To proceed with adding a lamp to your cart, I need you to specify which of the three "
          + "lamps you'd like.",
      "Just name the lamp, Operative, and I'll add it to your mission inventory right away.",
      "Once you identify the specific product, I'll initiate the cart update procedure "
          + "immediately.",
      "Decime cuál y la agrego a tu carrito.",
      "Decime cuál y lo agregaré al carrito.",
      "For added comfort, it comes with two bolster pillows.",
      "The Aiden adds a pop of color to any reading corner.",
      "It would be a great addition to your cart.",
      "Earlier you added two lamps to your cart.",
      "I added a note about deployment times below.",
      "Let me know which one you want in your cart.",
      "Your cart is empty, Operative.",
      "¿Querés que lo agregue al carrito?",
      "Podés agregarlo al carrito desde la ficha del producto.",
      "No agregué nada al carrito.",
      "No se pudo agregar la lámpara: el carrito no responde.",
      "Si querés, lo agrego a tu carrito.",
      "Las lámparas que agregaste en el turno anterior siguen en tu carrito."
  })
  void legitimateTextIsNotAClaim(String sentence) {
    assertThat(CartClaimFilter.isClaim(sentence)).as(sentence).isFalse();
  }

  @Test
  void claimSentencesAreDroppedWhileTheRestStreamsBySentence() {
    CartClaimFilter filter = new CartClaimFilter(() -> false);

    StringBuilder out = new StringBuilder();
    out.append(filter.accept("Very well, Operative. Two lamps have "));
    assertThat(out.toString()).as("la primera oración sale apenas termina")
        .isEqualTo("Very well, Operative.");
    out.append(filter.accept("been added to your cart. Anything "));
    out.append(filter.accept("else for your lair?"));
    out.append(filter.flush());

    assertThat(out.toString()).isEqualTo("Very well, Operative. Anything else for your lair?");
    assertThat(filter.dropped()).isEqualTo(1);
  }

  @Test
  void claimsPassOnceAnAddToCartSucceededInTheTurn() {
    AtomicBoolean added = new AtomicBoolean();
    CartClaimFilter filter = new CartClaimFilter(added::get);

    String before = filter.accept("Adding the lamp to your cart now. ");
    added.set(true);
    String after = filter.accept("Done: the lamp has been added to your cart.") + filter.flush();

    assertThat(before).isEmpty();
    assertThat(after).isEqualTo(" Done: the lamp has been added to your cart.");
    assertThat(filter.dropped()).isEqualTo(1);
  }

  @Test
  void listLinesAndPricesKeepTheirFormat() {
    CartClaimFilter filter = new CartClaimFilter(() -> false);
    String text = "Options:\n- Aiden Mid-Century Velvet Armchair | $139\n- Allie Velvet Dining "
        + "Chair | $109.50 each\n*adds both to cart*\nPick one.";

    StringBuilder out = new StringBuilder();
    for (String fragment : text.split("(?<=\\G.{7})")) {
      out.append(filter.accept(fragment));
    }
    out.append(filter.flush());

    assertThat(out.toString()).isEqualTo("Options:\n- Aiden Mid-Century Velvet Armchair | $139\n"
        + "- Allie Velvet Dining Chair | $109.50 each\n\nPick one.");
    assertThat(filter.dropped()).isEqualTo(1);
  }

  @Test
  void sentenceEnds() {
    assertThat(CartClaimFilter.sentenceEnd("Hi there")).isEqualTo(-1);
    assertThat(CartClaimFilter.sentenceEnd("Hi. There")).isEqualTo(3);
    assertThat(CartClaimFilter.sentenceEnd("Hi.")).isEqualTo(3);
    assertThat(CartClaimFilter.sentenceEnd("It costs $1.5 now")).isEqualTo(-1);
    assertThat(CartClaimFilter.sentenceEnd("Done!* Next")).isEqualTo(6);
    assertThat(CartClaimFilter.sentenceEnd("a\nb")).isEqualTo(2);
  }
}
