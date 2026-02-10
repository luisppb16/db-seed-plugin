/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.generator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class DictionaryLoader {

  private static final String ENGLISH_DICTIONARY_PATH = "/dictionaries/english-words.txt";
  private static final String SPANISH_DICTIONARY_PATH = "/dictionaries/spanish-words.txt";

  private static final AtomicReference<List<String>> englishDictionaryCache =
      new AtomicReference<>();
  private static final AtomicReference<List<String>> spanishDictionaryCache =
      new AtomicReference<>();
  private static final Object DICTIONARY_LOCK = new Object();

  private DictionaryLoader() {
    // Utility class
  }

  public static List<String> loadWords(
      final boolean useEnglishDictionary, final boolean useSpanishDictionary) {
    final List<String> words = new ArrayList<>();
    
    if (useEnglishDictionary) {
      words.addAll(getEnglishWords());
    }
    
    if (useSpanishDictionary) {
      words.addAll(getSpanishWords());
    }
    
    return words.isEmpty() ? Collections.emptyList() : words;
  }

  private static List<String> getEnglishWords() {
    if (englishDictionaryCache.get() == null) {
      synchronized (DICTIONARY_LOCK) {
        if (englishDictionaryCache.get() == null) {
          englishDictionaryCache.set(readWordsFromFile(ENGLISH_DICTIONARY_PATH));
        }
      }
    }
    return englishDictionaryCache.get();
  }

  private static List<String> getSpanishWords() {
    if (spanishDictionaryCache.get() == null) {
      synchronized (DICTIONARY_LOCK) {
        if (spanishDictionaryCache.get() == null) {
          spanishDictionaryCache.set(readWordsFromFile(SPANISH_DICTIONARY_PATH));
        }
      }
    }
    return spanishDictionaryCache.get();
  }

  private static List<String> readWordsFromFile(final String filePath) {
    try (final InputStream is = DictionaryLoader.class.getResourceAsStream(filePath)) {
      if (is == null) {
        return Collections.emptyList();
      }
      return Arrays.stream(new String(is.readAllBytes(), StandardCharsets.UTF_8).split("\\s+"))
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .toList();
    } catch (final IOException e) {
      return Collections.emptyList();
    }
  }
}
