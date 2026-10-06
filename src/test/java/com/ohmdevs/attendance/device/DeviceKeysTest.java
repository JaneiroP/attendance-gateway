package com.ohmdevs.attendance.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeviceKeysTest {

    @Test
    void aGeneratedKeyMatchesItsOwnHashAndNoOther() {
        String key = DeviceKeys.generate();
        String otherKey = DeviceKeys.generate();

        assertThat(key).isNotEqualTo(otherKey);
        assertThat(DeviceKeys.matches(key, DeviceKeys.hash(key))).isTrue();
        assertThat(DeviceKeys.matches(otherKey, DeviceKeys.hash(key))).isFalse();
    }

    @Test
    void aMissingKeyNeverMatches() {
        String hash = DeviceKeys.hash(DeviceKeys.generate());

        assertThat(DeviceKeys.matches(null, hash)).isFalse();
        assertThat(DeviceKeys.matches("", hash)).isFalse();
    }

    @Test
    void theStoredHashDoesNotContainTheKey() {
        String key = DeviceKeys.generate();

        assertThat(DeviceKeys.hash(key)).hasSize(64).doesNotContain(key);
    }
}
