package com.codearena.business.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class UserLlmSettingsServiceTest {
    private UserLlmSettingsService service;

    @BeforeEach
    void setUp() {
        service = new UserLlmSettingsService(null);
        ReflectionTestUtils.setField(service, "keySecret", "test-master-secret-with-more-than-32-characters");
    }

    @Test
    void aesGcmRoundTripNeverStoresPlaintext() {
        String encrypted = service.encrypt("sk-secret-123456789");

        assertThat(encrypted).startsWith("v1:").doesNotContain("sk-secret");
        assertThat(service.decrypt(encrypted)).isEqualTo("sk-secret-123456789");
    }

    @Test
    void legacyPlaintextRemainsReadableForReadTimeMigration() {
        assertThat(service.decrypt("legacy-secret-key")).isEqualTo("legacy-secret-key");
    }
}
