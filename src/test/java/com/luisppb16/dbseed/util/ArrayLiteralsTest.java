/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ArrayLiteralsTest {

  @Test
  void isWrapped_bracedAndBracketed_areWrapped() {
    assertThat(ArrayLiterals.isWrapped("{a,b,c}")).isTrue();
    assertThat(ArrayLiterals.isWrapped("[a,b,c]")).isTrue();
    assertThat(ArrayLiterals.isWrapped("  {a,b} ")).isTrue();
  }

  @Test
  void isWrapped_scalar_isNotWrapped() {
    assertThat(ArrayLiterals.isWrapped("python")).isFalse();
    assertThat(ArrayLiterals.isWrapped(null)).isFalse();
    assertThat(ArrayLiterals.isWrapped("")).isFalse();
  }

  @Test
  void topLevelElements_flatLiteral_returnsElements() {
    assertThat(ArrayLiterals.topLevelElements("{python,cli,linux}"))
        .containsExactly("python", "cli", "linux");
  }

  @Test
  void topLevelElements_quotedAndSpaced_returnsCleanElements() {
    assertThat(ArrayLiterals.topLevelElements("{ 'intelligence', 'reports', 'classified' }"))
        .containsExactly("intelligence", "reports", "classified");
  }

  @Test
  void topLevelElements_nestedGroups_areSingleElements() {
    // The commas inside each nested group must not be counted as separators.
    assertThat(ArrayLiterals.topLevelElements("{{a,b,c},{d,e,f},{g,h,i}}"))
        .containsExactly("{a,b,c}", "{d,e,f}", "{g,h,i}");
  }

  @Test
  void topLevelElements_scalar_isSingleElement() {
    assertThat(ArrayLiterals.topLevelElements("Madrid")).containsExactly("Madrid");
  }

  @Test
  void flatten_nestedGroups_collapsesToLeaves() {
    assertThat(ArrayLiterals.flatten("{{a,b,c},{d,e,f},{g,h,i}}"))
        .containsExactly("a", "b", "c", "d", "e", "f", "g", "h", "i");
  }

  @Test
  void flatten_quotedElements_stripsQuotes() {
    assertThat(ArrayLiterals.flatten("{ 'intelligence', 'reports' }"))
        .containsExactly("intelligence", "reports");
  }

  @Test
  void flatten_emptyLiteral_yieldsNoLeaves() {
    assertThat(ArrayLiterals.flatten("{}")).isEmpty();
    assertThat(ArrayLiterals.flatten("[]")).isEmpty();
  }

  @Test
  void normalize_doubleBracedLiterals_flattensToSingleLiteral() {
    assertThat(ArrayLiterals.normalize("{{a,b,c},{d,e,f}}")).isEqualTo("{a,b,c,d,e,f}");
  }

  @Test
  void normalize_quotedAndSpacedLiteral_removesNoise() {
    assertThat(ArrayLiterals.normalize("{ 'intelligence', 'reports', 'classified' }"))
        .isEqualTo("{intelligence,reports,classified}");
    assertThat(ArrayLiterals.normalize("['python', 'cli']")).isEqualTo("{python,cli}");
  }

  @Test
  void normalize_scalar_isReturnedTrimmed() {
    assertThat(ArrayLiterals.normalize("  Madrid  ")).isEqualTo("Madrid");
  }

  @Test
  void normalize_elementWithComma_isQuotedSoItSurvivesARoundTrip() {
    // A quoted element that itself contains a comma must not be split back into two elements.
    final String normalized = ArrayLiterals.normalize("{\"a,b\",c}");
    assertThat(normalized).isEqualTo("{\"a,b\",c}");
    assertThat(ArrayLiterals.flatten(normalized)).containsExactly("a,b", "c");
  }

  @Test
  void splitTopLevel_commasInsideQuotes_doNotSplit() {
    assertThat(ArrayLiterals.splitTopLevel("\"a,b\",c")).containsExactly("\"a,b\"", "c");
  }

  @Test
  void splitTopLevel_commasInsideNestedGroup_doNotSplit() {
    assertThat(ArrayLiterals.splitTopLevel("{a,b},c")).containsExactly("{a,b}", "c");
  }

  @Test
  void stripQuotes_matchingAndNonMatchingQuotes() {
    assertThat(ArrayLiterals.stripQuotes("'a'")).isEqualTo("a");
    assertThat(ArrayLiterals.stripQuotes("\"a\"")).isEqualTo("a");
    assertThat(ArrayLiterals.stripQuotes("a")).isEqualTo("a");
  }

  @Test
  void topLevelElements_nullAndBlank_areEmpty() {
    assertThat(ArrayLiterals.topLevelElements(null)).isEqualTo(List.of());
    assertThat(ArrayLiterals.topLevelElements("{}")).isEqualTo(List.of());
  }
}
