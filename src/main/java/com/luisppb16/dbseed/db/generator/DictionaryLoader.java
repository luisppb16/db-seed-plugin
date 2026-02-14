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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Efficient dictionary loading and caching system for multilingual word resources in the DBSeed
 * plugin.
 *
 * <p>This utility class provides optimized loading and caching mechanisms for multilingual
 * dictionary resources used in data generation. It implements thread-safe lazy loading with atomic
 * reference caching to ensure efficient access to dictionary resources across multiple concurrent
 * operations. The class handles multiple language dictionaries and provides configurable loading
 * based on user preferences for English and/or Spanish word sets.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Lazy loading of dictionary resources from embedded resource files
 *   <li>Thread-safe caching of dictionary content to avoid redundant file operations
 *   <li>Efficient parsing and normalization of dictionary word lists
 *   <li>Conditional loading based on user language preferences
 *   <li>Error handling for missing or corrupted dictionary resources
 *   <li>Memory-efficient storage and retrieval of word collections
 * </ul>
 *
 * <p>The implementation uses atomic references and synchronized blocks to ensure thread safety
 * during cache initialization. It employs efficient stream operations for parsing dictionary files
 * and implements proper resource management through try-with-resources patterns. The class follows
 * the singleton pattern through static methods and provides immutable list views to prevent
 * external modification of cached dictionary content.
 */
public final class DictionaryLoader {

  private static final String ENGLISH_DICTIONARY_PATH = "/dictionaries/english-words.txt";
  private static final String SPANISH_DICTIONARY_PATH = "/dictionaries/spanish-words.txt";

  private static final AtomicReference<List<String>> englishDictionaryCache =
      new AtomicReference<>();
  private static final AtomicReference<List<String>> spanishDictionaryCache =
      new AtomicReference<>();
  private static final Object DICTIONARY_LOCK = new Object();

  private DictionaryLoader() {}

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
    if (Objects.isNull(englishDictionaryCache.get())) {
      synchronized (DICTIONARY_LOCK) {
        if (Objects.isNull(englishDictionaryCache.get())) {
          englishDictionaryCache.set(readWordsFromFile(ENGLISH_DICTIONARY_PATH));
        }
      }
    }
    return englishDictionaryCache.get();
  }

  private static List<String> getSpanishWords() {
    if (Objects.isNull(spanishDictionaryCache.get())) {
      synchronized (DICTIONARY_LOCK) {
        if (Objects.isNull(spanishDictionaryCache.get())) {
          spanishDictionaryCache.set(readWordsFromFile(SPANISH_DICTIONARY_PATH));
        }
      }
    }
    return spanishDictionaryCache.get();
  }

  private static List<String> readWordsFromFile(final String filePath) {
    try (final InputStream is = DictionaryLoader.class.getResourceAsStream(filePath)) {
      if (Objects.isNull(is)) {
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
