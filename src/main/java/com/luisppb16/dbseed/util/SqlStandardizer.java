/*
 *  Copyright (c) 2025 Luis Pepe.
 *  All rights reserved.
 */

package com.luisppb16.dbseed.util;

import com.luisppb16.dbseed.schema.SchemaDsl;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

@UtilityClass
public class SqlStandardizer {

  public static Set<String> loadAllowedValues() {
    return new LinkedHashSet<>();
  }

  public static SchemaDsl.CheckConstraint parseCheckConstraint(String checkClause) {
    Pattern inPattern = Pattern.compile("value IN \\((.*)\\)");
    Matcher inMatcher = inPattern.matcher(checkClause);
    if (inMatcher.matches()) {
      return new SchemaDsl.CheckConstraint.In(extractInValues(inMatcher.group(1)));
    }

    Pattern betweenPattern = Pattern.compile("value BETWEEN '(.*)' AND '(.*)'");
    Matcher betweenMatcher = betweenPattern.matcher(checkClause);
    if (betweenMatcher.matches()) {
      return new SchemaDsl.CheckConstraint.Between(betweenMatcher.group(1), betweenMatcher.group(2));
    }

    return new SchemaDsl.CheckConstraint.Expression(checkClause);
  }

  private static List<String> extractInValues(String inClause) {
    List<String> values = new ArrayList<>();
    Pattern valuePattern = Pattern.compile("'([^']*)'");
    Matcher valueMatcher = valuePattern.matcher(inClause);
    while (valueMatcher.find()) {
      values.add(valueMatcher.group(1));
    }
    return values;
  }
}
