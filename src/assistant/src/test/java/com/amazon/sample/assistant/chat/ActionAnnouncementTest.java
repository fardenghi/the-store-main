package com.amazon.sample.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Respuestas que terminan anunciando una acción sin hacerla (corrección posterior). */
class ActionAnnouncementTest {

  /** Finales reales de las corridas de {@code MultiTurnCartSmokeIT} y variantes. */
  @ParameterizedTest
  @ValueSource(strings = {
      "I need to check the current details for the Adjustable Pharmacy Desk Lamp first.",
      "I need to search for the Adjustable Pharmacy Desk Lamp first to get its correct product "
          + "ID. Let me check our inventory for that specific item.",
      "Let me pull the latest intel straight from headquarters.  *getting current price for "
          + "Aiden armchair*",
      "Field reports indicate several options.  First, let me check current inventory for "
          + "desk-appropriate lighting. I'll search for desk lamps specifically.",
      "*searches catalog for velvet armchairs under $139*",
      "Dame un segundo, voy a buscar las lámparas de escritorio."
  })
  void announcementsAtTheEndAreDetected(String reply) {
    assertThat(ActionAnnouncement.endsWithAnnouncement(reply)).as(reply).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "The Aiden is $139, Operative.",
      "Let me know if you need anything else for your lair.",
      "Let me know which one, and I'll add it to your cart.",
      "Shall I check the other lamps too?",
      "Once you confirm the lamp, I'll add it to your cart.",
      "I'll run more surveillance whenever you're ready.",
      "Let me check the catalog. The Aiden is $139 and ships in 3-5 days.",
      "Want me to add it to your cart? *checks watch*",
      "Excellent choice. *adjusts earpiece*",
      "Decime cuál querés y la agrego al carrito.",
      ""
  })
  void repliesThatEndWithAnAnswerOrWaitForTheUserAreNot(String reply) {
    assertThat(ActionAnnouncement.endsWithAnnouncement(reply)).as(reply).isFalse();
  }
}
