package com.amazon.sample.assistant.chat.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

/** Descarte de {@code <think>…</think>} en el stream (D7). */
class ThinkTagFilterTest {

  private static String filter(String... fragments) {
    List<String> out = ThinkTagFilter.apply(Flux.fromArray(fragments)).collectList().block();
    return String.join("", out);
  }

  @Test
  void withoutTagsPassesEverything() {
    assertThat(filter("Hello", ", Operative", ". <b>bold</b> < 5")).isEqualTo(
        "Hello, Operative. <b>bold</b> < 5");
  }

  @Test
  void removesAWholeBlockWithTextBeforeAndAfter() {
    assertThat(filter("Before <think>secret plan</think> after")).isEqualTo("Before  after");
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 5, 7, 8, 9, 12, 15, 18, 20})
  void removesBlocksWhoseTagsAreSplitAcrossFragments(int size) {
    String text = "<think>The user wants a chair. Compare prices.</think>The Aiden costs $139.";
    List<String> fragments = new java.util.ArrayList<>();
    for (int i = 0; i < text.length(); i += size) {
      fragments.add(text.substring(i, Math.min(text.length(), i + size)));
    }

    assertThat(filter(fragments.toArray(String[]::new))).isEqualTo("The Aiden costs $139.");
  }

  @Test
  void splitInsideTheOpeningAndClosingTags() {
    assertThat(filter("Intro <thi", "nk>reason", "ing</th", "ink> outro")).isEqualTo(
        "Intro  outro");
  }

  @Test
  void aPartialTagAtTheEndIsText() {
    assertThat(filter("x < y and <thi")).isEqualTo("x < y and <thi");
  }

  @Test
  void anUnclosedBlockIsDropped() {
    assertThat(filter("Answer.", "<think>never closed")).isEqualTo("Answer.");
  }

  @Test
  void emptyFragmentsAreNotEmitted() {
    List<String> out = ThinkTagFilter.apply(Flux.just("<think>", "a", "</think>", "b"))
        .collectList().block();

    assertThat(out).containsExactly("b");
  }
}
