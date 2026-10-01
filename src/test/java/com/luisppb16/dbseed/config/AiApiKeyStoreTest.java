/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.luisppb16.dbseed.ai.AiProvider;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AiApiKeyStore}: the account name each engine stores under, and the promise
 * that every operation degrades instead of throwing when the credential store is not there.
 *
 * <p>A plain JUnit run has no IntelliJ application, so the store itself is unreachable — which is
 * exactly the situation the defensive code exists for. Nothing here needs a platform fixture.
 */
class AiApiKeyStoreTest {

  private static List<String> allKeyNames() {
    return Arrays.stream(AiProvider.values()).map(AiApiKeyStore::credentialKeyName).toList();
  }

  @Nested
  class CredentialKeyName {

    @Test
    void isDistinctForEveryEngine() {
      assertThat(allKeyNames()).doesNotHaveDuplicates();
    }

    @Test
    void keepsTheStoredContract() {
      assertThat(AiApiKeyStore.credentialKeyName(AiProvider.OLLAMA)).isEqualTo("ai-api-key-OLLAMA");
      assertThat(AiApiKeyStore.credentialKeyName(AiProvider.UNSLOTH_STUDIO))
          .isEqualTo("ai-api-key-UNSLOTH_STUDIO");
      assertThat(AiApiKeyStore.credentialKeyName(AiProvider.OPENAI_COMPATIBLE))
          .isEqualTo("ai-api-key-OPENAI_COMPATIBLE");
    }

    @Test
    void doesNotClaimTheNameOfTheSingleCredentialBuild() {
      assertThat(allKeyNames()).doesNotContain("ai-api-key");
    }
  }

  @Nested
  class WithoutACredentialStore {

    @Test
    void load_returnsEmptyInsteadOfThrowing() {
      assertThat(AiApiKeyStore.load(AiProvider.UNSLOTH_STUDIO)).isEmpty();
    }

    @Test
    void save_doesNotThrow() {
      assertThatCode(() -> AiApiKeyStore.save(AiProvider.UNSLOTH_STUDIO, "sk-unsloth-test"))
          .doesNotThrowAnyException();
    }

    @Test
    void clear_doesNotThrow() {
      assertThatCode(() -> AiApiKeyStore.clear(AiProvider.OPENAI_COMPATIBLE))
          .doesNotThrowAnyException();
    }

    @Test
    void migrateLegacyKey_doesNotThrow() {
      assertThatCode(() -> AiApiKeyStore.migrateLegacyKey(AiProvider.UNSLOTH_STUDIO))
          .doesNotThrowAnyException();
    }
  }

  /**
   * The platform instruments {@code @NotNull} parameters, so a missing argument fails fast before
   * any of the code runs — the same contract the rest of the plugin relies on.
   */
  @Nested
  class FailFast {

    @Test
    void load_withoutAnEngine_fails() {
      assertThatIllegalArgumentException().isThrownBy(() -> AiApiKeyStore.load(null));
    }

    @Test
    void save_withoutAnEngine_fails() {
      assertThatIllegalArgumentException().isThrownBy(() -> AiApiKeyStore.save(null, "sk-test"));
    }

    @Test
    void save_withoutACredential_fails() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> AiApiKeyStore.save(AiProvider.UNSLOTH_STUDIO, null));
    }

    @Test
    void clear_withoutAnEngine_fails() {
      assertThatIllegalArgumentException().isThrownBy(() -> AiApiKeyStore.clear(null));
    }

    @Test
    void migrateLegacyKey_withoutAnEngine_fails() {
      assertThatIllegalArgumentException().isThrownBy(() -> AiApiKeyStore.migrateLegacyKey(null));
    }
  }
}
