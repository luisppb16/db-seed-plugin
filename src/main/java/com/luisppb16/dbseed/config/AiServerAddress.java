/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.config;

import java.util.Objects;
import org.jetbrains.annotations.Nullable;

final class AiServerAddress {

  private static final String SCHEME_SEPARATOR = "://";

  private final String url;
  private final String port;

  private AiServerAddress(final String url, final String port) {
    this.url = url;
    this.port = port;
  }

  static AiServerAddress parse(@Nullable final String rawUrl) {
    final String value = Objects.requireNonNullElse(rawUrl, "").trim();
    if (value.isEmpty()) {
      return new AiServerAddress("", "");
    }
    final int schemeEnd = value.indexOf(SCHEME_SEPARATOR);
    final boolean hasScheme = schemeEnd > 0 && isScheme(value.substring(0, schemeEnd));
    final String prefix =
        hasScheme ? value.substring(0, schemeEnd + SCHEME_SEPARATOR.length()) : "";
    final String rest = value.substring(prefix.length());
    final int authorityEnd = authorityEnd(rest);
    final String authority = rest.substring(0, authorityEnd);
    final String tail = rest.substring(authorityEnd);
    final String host;
    final String port;
    if (authority.startsWith("[")) {
      final int close = authority.indexOf(']');
      final String candidate = close >= 0 ? authority.substring(close + 1) : "";
      if (candidate.startsWith(":") && isPort(candidate.substring(1))) {
        host = authority.substring(0, close + 1);
        port = candidate.substring(1);
      } else {
        host = authority;
        port = "";
      }
    } else {
      final int colon = authority.lastIndexOf(':');
      final String candidate = colon >= 0 ? authority.substring(colon + 1) : "";
      if (colon >= 0 && isPort(candidate)) {
        host = authority.substring(0, colon);
        port = candidate;
      } else {
        host = authority;
        port = "";
      }
    }
    return new AiServerAddress(prefix + host + tail, port);
  }

  static AiServerAddress of(@Nullable final String url, @Nullable final String port) {
    final AiServerAddress parsed = parse(url);
    final String explicitPort = Objects.requireNonNullElse(port, "").trim();
    return new AiServerAddress(parsed.url, explicitPort.isEmpty() ? parsed.port : explicitPort);
  }

  private static int authorityEnd(final String rest) {
    int end = rest.length();
    final int slash = rest.indexOf('/');
    final int question = rest.indexOf('?');
    final int hash = rest.indexOf('#');
    if (slash >= 0) {
      end = slash;
    }
    if (question >= 0 && question < end) {
      end = question;
    }
    if (hash >= 0 && hash < end) {
      end = hash;
    }
    return end;
  }

  private static boolean isScheme(final String candidate) {
    if (candidate.isEmpty()) {
      return false;
    }
    for (int index = 0; index < candidate.length(); index++) {
      final char character = candidate.charAt(index);
      final boolean valid =
          Character.isLetterOrDigit(character)
              || character == '+'
              || character == '-'
              || character == '.';
      if (!valid || (index == 0 && !Character.isLetter(character))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isPort(final String candidate) {
    return !candidate.isEmpty() && candidate.chars().allMatch(Character::isDigit);
  }

  String url() {
    return url;
  }

  String port() {
    return port;
  }

  String composed() {
    if (url.isBlank()) {
      return "";
    }
    return port.isBlank() ? url : url + ":" + port;
  }
}
