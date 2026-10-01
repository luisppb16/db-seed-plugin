/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * Turns the credential typed in the settings into the {@code Authorization} header that Unsloth
 * Studio accepts on its OpenAI-compatible surface.
 *
 * <p>That surface takes an {@code sk-unsloth-…} API key or a session token, and nothing else: the
 * password used to sign in to Unsloth Studio is not a bearer, so sending it as one is answered with
 * {@code 401}. A password is therefore exchanged for a session token first — {@code GET
 * /api/auth/status} names the account to sign in as, {@code POST /api/auth/login} returns the token
 * — and the token is cached until it expires, because the login route is rate-limited and a request
 * would otherwise pay for the exchange every time.
 *
 * @see OpenAiCompatibleClient
 */
@Slf4j
class UnslothStudioAuth {

  /** Prefix Unsloth mints every API key with; anything else in the field is read as a password. */
  private static final String API_KEY_PREFIX = "sk-unsloth-";

  private static final String STATUS_PATH = "/api/auth/status";
  private static final String LOGIN_PATH = "/api/auth/login";
  private static final String FALLBACK_USERNAME = "unsloth";

  /** Renew this long before the token actually expires, so a request never races its expiry. */
  private static final long EXPIRY_MARGIN_MILLIS = 60_000L;

  /** Lifetime assumed when a server mints a token whose expiry cannot be read. */
  private static final long ASSUMED_TOKEN_LIFETIME_MILLIS = 50L * 60L * 1000L;

  private final String normalizedUrl;
  private final String credential;
  private final int requestTimeoutSeconds;

  private final Object lock = new Object();
  private String accessToken;
  private long accessTokenExpiresAtMillis;

  UnslothStudioAuth(
      @NotNull final String normalizedUrl,
      @NotNull final String credential,
      final int requestTimeoutSeconds) {
    this.normalizedUrl = normalizedUrl;
    this.credential = credential;
    this.requestTimeoutSeconds = requestTimeoutSeconds;
  }

  /** Whether the typed credential is an API key, which travels as it is and needs no exchange. */
  static boolean isApiKey(@NotNull final String credential) {
    return credential.startsWith(API_KEY_PREFIX);
  }

  /** When the session token stops being accepted, read from its own {@code exp} claim. */
  private static long expiresAtMillis(final String token) {
    final String[] segments = token.split("\\.");
    if (segments.length >= 2) {
      try {
        final String claims =
            new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8);
        final JsonElement exp = JsonParser.parseString(claims).getAsJsonObject().get("exp");
        if (Objects.nonNull(exp) && exp.isJsonPrimitive()) {
          return exp.getAsLong() * 1000L - EXPIRY_MARGIN_MILLIS;
        }
      } catch (final Exception e) {
        log.debug("Unsloth Studio session token carries no readable expiry: {}", e.getMessage());
      }
    }
    return System.currentTimeMillis() + ASSUMED_TOKEN_LIFETIME_MILLIS;
  }

  /** The reason an auth response carries, in the envelope that route uses. */
  private static String errorDetail(final int statusCode, final String body) {
    final String detail = AbstractAiClient.errorDetail(body);
    return detail.isEmpty() ? "status code " + statusCode : detail;
  }

  /**
   * The header to send: the API key verbatim, or the session token a password was exchanged for.
   *
   * @throws AiClientException when the password is refused, the account still owes a password
   *     change or the server cannot be reached to sign in
   */
  @NotNull
  String authorizationHeader() {
    if (isApiKey(credential)) {
      return "Bearer " + credential;
    }
    synchronized (lock) {
      if (Objects.nonNull(accessToken) && System.currentTimeMillis() < accessTokenExpiresAtMillis) {
        return "Bearer " + accessToken;
      }
      final JsonObject token = login(resolveUsername());
      final JsonElement access = token.get("access_token");
      if (Objects.isNull(access) || !access.isJsonPrimitive() || access.getAsString().isBlank()) {
        throw new AiClientException(
            "Unsloth Studio answered the sign-in without a session token. Create an API key in"
                + " Unsloth Studio under Settings → API and use it instead.");
      }
      accessToken = access.getAsString();
      accessTokenExpiresAtMillis = expiresAtMillis(accessToken);
      return "Bearer " + accessToken;
    }
  }

  /** Signs in and returns the whole token body, or fails with the reason the server gave. */
  private JsonObject login(final String username) {
    final JsonObject payload = new JsonObject();
    payload.addProperty("username", username);
    payload.addProperty("password", credential);

    final HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(normalizedUrl + LOGIN_PATH))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(requestTimeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofString(AbstractAiClient.GSON.toJson(payload)))
            .build();
    final HttpResponse<String> response;
    try {
      response = AbstractAiClient.HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (final Exception e) {
      throw new AiClientException(
          "Could not reach Unsloth Studio at "
              + normalizedUrl
              + " to sign in: "
              + Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()),
          e);
    }
    if (response.statusCode() != 200) {
      throw new AiClientException(
          "Unsloth Studio refused the sign-in as '"
              + username
              + "': "
              + errorDetail(response.statusCode(), response.body())
              + " Create an API key in Unsloth Studio under Settings → API and use it instead.");
    }
    try {
      final JsonObject token = JsonParser.parseString(response.body()).getAsJsonObject();
      if (token.has("must_change_password")
          && token.get("must_change_password").isJsonPrimitive()
          && token.get("must_change_password").getAsBoolean()) {
        throw new AiClientException(
            "Unsloth Studio asks for that password to be changed before it can be used: sign in to"
                + " Unsloth Studio, set a new one, and use it here.");
      }
      return token;
    } catch (final AiClientException e) {
      throw e;
    } catch (final Exception e) {
      throw new AiClientException("Unsloth Studio answered the sign-in with no usable body.", e);
    }
  }

  /**
   * Reads the account to sign in as from the unauthenticated status route, which is what keeps the
   * username out of the settings. A server that does not answer it still gets the documented
   * default, and a wrong name surfaces as the login error rather than as silence.
   */
  private String resolveUsername() {
    final HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(normalizedUrl + STATUS_PATH))
            .timeout(Duration.ofSeconds(requestTimeoutSeconds))
            .GET()
            .build();
    try {
      final HttpResponse<String> response =
          AbstractAiClient.HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        final JsonElement username =
            JsonParser.parseString(response.body()).getAsJsonObject().get("default_username");
        if (Objects.nonNull(username)
            && username.isJsonPrimitive()
            && !username.getAsString().isBlank()) {
          return username.getAsString();
        }
      }
    } catch (final Exception e) {
      log.debug("Could not read the Unsloth Studio account name: {}", e.getMessage());
    }
    return FALLBACK_USERNAME;
  }
}
