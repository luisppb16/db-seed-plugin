/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.config;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.luisppb16.dbseed.ai.AiProvider;
import java.util.Objects;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * Stores each AI engine's credential in the IDE credential store (the same keychain the database
 * connection profiles use), never in the plugin's settings XML, which is written in plain text.
 *
 * <p>Every engine owns its own entry, so switching engines never mixes one engine's secret with
 * another's. The value is an API key for every engine but Unsloth Studio, where it may also be the
 * account password: that one is exchanged for a session token before it is sent, so what is stored
 * here is exactly what the user typed.
 *
 * <p>Every operation is defensive: the credential store is unavailable in a headless test
 * environment and can also fail on a machine with no keychain, and neither case should stop a
 * generation that may not even need a credential.
 */
@Slf4j
@UtilityClass
public class AiApiKeyStore {

  private static final String SERVICE_NAME = "DBSeed";
  private static final String KEY_NAME_PREFIX = "ai-api-key";

  /** Entry written while there was a single credential, read once and then moved to its engine. */
  private static final String LEGACY_KEY_NAME = KEY_NAME_PREFIX;

  /**
   * @return the credential stored for that engine, or an empty string when there is none or it
   *     cannot be read
   */
  @NotNull
  public static String load(@NotNull final AiProvider provider) {
    try {
      final Credentials credentials =
          PasswordSafe.getInstance().get(createCredentialAttributes(credentialKeyName(provider)));
      if (Objects.isNull(credentials)) {
        return "";
      }
      return Objects.requireNonNullElse(credentials.getPasswordAsString(), "");
    } catch (final Exception e) {
      log.debug("Could not read the {} credential from the credential store", provider, e);
      return "";
    }
  }

  public static void save(@NotNull final AiProvider provider, @NotNull final String credential) {
    try {
      PasswordSafe.getInstance()
          .set(
              createCredentialAttributes(credentialKeyName(provider)),
              new Credentials(credentialKeyName(provider), credential.trim()));
    } catch (final Exception e) {
      log.warn("Could not store the {} credential in the credential store", provider, e);
    }
  }

  public static void clear(@NotNull final AiProvider provider) {
    try {
      PasswordSafe.getInstance().set(createCredentialAttributes(credentialKeyName(provider)), null);
    } catch (final Exception e) {
      log.warn("Could not clear the {} credential from the credential store", provider, e);
    }
  }

  /**
   * Moves the single credential written by earlier builds into the entry of the engine it belonged
   * to: those builds saved the settings field together with the engine selected at that moment, so
   * the configured engine is its owner. Idempotent, and a no-op once the engine already has one.
   *
   * <p>Ollama cannot hold a credential — its field is disabled and its client takes none — so in
   * that case the legacy value is adopted by Unsloth Studio, the only engine whose key is shown
   * once and cannot be fetched back from the server.
   */
  public static void migrateLegacyKey(@NotNull final AiProvider configuredProvider) {
    final AiProvider owner =
        configuredProvider == AiProvider.OLLAMA ? AiProvider.UNSLOTH_STUDIO : configuredProvider;
    try {
      if (!load(owner).isEmpty()) {
        return;
      }
      final Credentials legacy =
          PasswordSafe.getInstance().get(createCredentialAttributes(LEGACY_KEY_NAME));
      if (Objects.isNull(legacy) || Objects.isNull(legacy.getPasswordAsString())) {
        return;
      }
      final String credential = legacy.getPasswordAsString();
      if (credential.isEmpty()) {
        return;
      }
      save(owner, credential);
      PasswordSafe.getInstance().set(createCredentialAttributes(LEGACY_KEY_NAME), null);
    } catch (final Exception e) {
      log.debug("Could not migrate the legacy AI credential", e);
    }
  }

  /** Account name holding one engine's credential; distinct per engine by construction. */
  static String credentialKeyName(@NotNull final AiProvider provider) {
    return KEY_NAME_PREFIX + "-" + provider.name();
  }

  private static CredentialAttributes createCredentialAttributes(@NotNull final String keyName) {
    // Kotlin extension function from IntelliJ Platform credential store API.
    // May require updates if the IntelliJ Platform changes this API.
    return new CredentialAttributes(
        CredentialAttributesKt.generateServiceName(SERVICE_NAME, keyName));
  }
}
