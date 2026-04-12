/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DictionaryLoaderTest {

  @Test
  void englishOnly() {
    final List<String> words = DictionaryLoader.loadWords(true, false);
    assertThat(words).isNotEmpty();
  }

  @Test
  void spanishOnly() {
    final List<String> words = DictionaryLoader.loadWords(false, true);
    assertThat(words).isNotEmpty();
  }

  @Test
  void both() {
    final List<String> english = DictionaryLoader.loadWords(true, false);
    final List<String> spanish = DictionaryLoader.loadWords(false, true);
    final List<String> both = DictionaryLoader.loadWords(true, true);
    assertThat(both.size()).isEqualTo(english.size() + spanish.size());
  }

  @Test
  void neither_empty() {
    final List<String> words = DictionaryLoader.loadWords(false, false);
    assertThat(words).isEmpty();
  }

  @Test
  void caching_sameReference() {
    final List<String> first = DictionaryLoader.loadWords(true, false);
    final List<String> second = DictionaryLoader.loadWords(true, false);
    // Both should contain the same cached English words
    assertThat(first).hasSameHashCodeAs(first);
    assertThat(first.size()).isEqualTo(second.size());
  }

  @Test
  void noBlankEntries_english() {
    final List<String> words = DictionaryLoader.loadWords(true, false);
    assertThat(words).allSatisfy(w -> assertThat(w).isNotBlank());
  }

  @Test
  void noBlankEntries_spanish() {
    final List<String> words = DictionaryLoader.loadWords(false, true);
    assertThat(words).allSatisfy(w -> assertThat(w).isNotBlank());
  }
}
