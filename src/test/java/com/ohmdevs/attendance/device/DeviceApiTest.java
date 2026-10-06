package com.ohmdevs.attendance.device;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.ohmdevs.attendance.AbstractIntegrationTest;

class DeviceApiTest extends AbstractIntegrationTest {

    @Test
    void registrationReturnsAKeyThatIsStoredOnlyAsAHash() {
        TestDevice device = registerDevice("America/Santo_Domingo");

        assertThat(device.apiKey()).startsWith("agw_");
        String storedHash = jdbc.queryForObject("SELECT key_hash FROM device WHERE id = ?", String.class, device.id());
        assertThat(storedHash).isEqualTo(DeviceKeys.hash(device.apiKey())).doesNotContain(device.apiKey());
    }

    @Test
    void readingADeviceNeverReturnsItsKey() {
        TestDevice device = registerDevice("UTC");

        ResponseEntity<String> response = rest.exchange("/api/v1/devices/{id}", HttpMethod.GET,
                new HttpEntity<>(adminHeaders()), String.class, device.id());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("apiKey").doesNotContain("keyHash").doesNotContain("agw_");
    }

    @Test
    void rotatingTheKeyInvalidatesTheOldOne() {
        TestDevice device = registerDevice("UTC");
        mapEmployee(device, "17", "EMP-17");
        List<PunchDto> batch = List.of(new PunchDto("17", "2026-09-12T07:58:00", "IN"));

        DeviceWithKeyDto rotated = rest.exchange("/api/v1/devices/{id}/rotate-key", HttpMethod.POST,
                new HttpEntity<>(adminHeaders()), DeviceWithKeyDto.class, device.id()).getBody();

        assertThat(rotated.apiKey()).isNotEqualTo(device.apiKey());
        assertThat(ingest(device.id(), device.apiKey(), batch, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ingest(device.id(), rotated.apiKey(), batch, String.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void theAdministrationApiRequiresTheAdminKey() {
        HttpHeaders wrongKey = new HttpHeaders();
        wrongKey.set("X-Admin-Key", "not-the-key");

        assertThat(rest.exchange("/api/v1/devices", HttpMethod.GET, HttpEntity.EMPTY, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange("/api/v1/devices", HttpMethod.GET, new HttpEntity<>(wrongKey), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange("/api/v1/quarantine", HttpMethod.GET, HttpEntity.EMPTY, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aDeviceKeyDoesNotOpenTheAdministrationApi() {
        TestDevice device = registerDevice("UTC");
        HttpHeaders deviceKeyAsAdmin = new HttpHeaders();
        deviceKeyAsAdmin.set("X-Admin-Key", device.apiKey());
        deviceKeyAsAdmin.set("X-Device-Key", device.apiKey());

        ResponseEntity<String> response = rest.exchange("/api/v1/devices", HttpMethod.GET,
                new HttpEntity<>(deviceKeyAsAdmin), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsAnUnknownTimeZone() {
        ResponseEntity<String> response = rest.exchange("/api/v1/devices", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Clock", "siteCode", "S1", "timeZone", "Mars/Olympus"),
                        adminHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void healthAndApiDocumentationAreOpen() {
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> apiDocs = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(apiDocs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(apiDocs.getBody()).contains("/api/v1/devices/{deviceId}/punches");
    }
}
