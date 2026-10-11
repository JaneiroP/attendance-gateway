package com.ohmdevs.attendance.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.ohmdevs.attendance.AbstractIntegrationTest;

class PunchIngestApiTest extends AbstractIntegrationTest {

    private record ReprocessDto(int examined, int resolved) {
    }

    @Test
    void storesPunchesInUtcAndRecordsThatTheDeviceWasSeen() {
        TestDevice device = registerDevice("America/Santo_Domingo");
        mapEmployee(device, "17", "EMP-17");

        ResponseEntity<IngestResultDto> response = ingest(device, List.of(
                new PunchDto("17", "2026-09-01T07:58:00", "IN"),
                new PunchDto("17", "2026-09-01T17:02:00", "OUT")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isEqualTo(new IngestResultDto(2, 2, 0, 0));

        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT employee_id, punched_at_utc FROM punch WHERE device_id = ? AND type = 'IN'", device.id());
        assertThat(stored.get("employee_id")).isEqualTo("EMP-17");
        assertThat(jdbc.queryForObject("SELECT punched_at_utc FROM punch WHERE device_id = ? AND type = 'IN'",
                OffsetDateTime.class, device.id()).toInstant())
                .isEqualTo(OffsetDateTime.of(2026, 9, 1, 11, 58, 0, 0, ZoneOffset.UTC).toInstant());
        assertThat(jdbc.queryForObject("SELECT last_seen_at IS NOT NULL FROM device WHERE id = ?",
                Boolean.class, device.id())).isTrue();
    }

    @Test
    void aRetriedBatchStoresNothingNewAndReportsDuplicates() {
        TestDevice device = registerDevice("America/Santo_Domingo");
        mapEmployee(device, "17", "EMP-17");
        List<PunchDto> batch = List.of(
                new PunchDto("17", "2026-09-02T07:58:00", "IN"),
                new PunchDto("17", "2026-09-02T17:02:00", "OUT"));

        ingest(device, batch);
        ResponseEntity<IngestResultDto> retry = ingest(device, batch);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(retry.getBody()).isEqualTo(new IngestResultDto(2, 0, 2, 0));
        assertThat(storedPunches(device)).isEqualTo(2);
    }

    @Test
    void aPunchRepeatedInsideOneBatchIsStoredOnce() {
        TestDevice device = registerDevice("America/Santo_Domingo");
        mapEmployee(device, "17", "EMP-17");
        PunchDto punch = new PunchDto("17", "2026-09-03T07:58:00", "IN");

        ResponseEntity<IngestResultDto> response = ingest(device, List.of(punch, punch, punch));

        assertThat(response.getBody()).isEqualTo(new IngestResultDto(3, 1, 2, 0));
        assertThat(storedPunches(device)).isEqualTo(1);
    }

    @Test
    void theSameCodeAndTimeOnAnotherDeviceIsADifferentPunch() {
        TestDevice frontDoor = registerDevice("America/Santo_Domingo");
        TestDevice backDoor = registerDevice("America/Santo_Domingo");
        mapEmployee(frontDoor, "17", "EMP-17");
        mapEmployee(backDoor, "17", "EMP-99");
        List<PunchDto> batch = List.of(new PunchDto("17", "2026-09-04T07:58:00", "IN"));

        ingest(frontDoor, batch);
        ResponseEntity<IngestResultDto> response = ingest(backDoor, batch);

        assertThat(response.getBody()).isEqualTo(new IngestResultDto(1, 1, 0, 0));
    }

    @Test
    void anUnmappedCodeIsQuarantinedAndTheRestOfTheBatchIsStored() {
        TestDevice device = registerDevice("America/Santo_Domingo");
        mapEmployee(device, "17", "EMP-17");

        ResponseEntity<IngestResultDto> response = ingest(device, List.of(
                new PunchDto("17", "2026-09-05T07:58:00", "IN"),
                new PunchDto("404", "2026-09-05T08:01:00", "IN")));

        assertThat(response.getBody()).isEqualTo(new IngestResultDto(2, 1, 0, 1));
        assertThat(storedPunches(device)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reason FROM quarantined_punch WHERE device_id = ?",
                String.class, device.id())).isEqualTo("UNKNOWN_EMPLOYEE");
    }

    @Test
    void aRetryDoesNotQuarantineTheSamePunchTwice() {
        TestDevice device = registerDevice("America/Santo_Domingo");
        List<PunchDto> batch = List.of(new PunchDto("404", "2026-09-06T08:01:00", "IN"));

        ingest(device, batch);
        ingest(device, batch);

        assertThat(quarantinedPunches(device, "PENDING")).isEqualTo(1);
    }

    @Test
    void aPunchFromTheFutureIsQuarantinedAsADeviceClockFault() {
        TestDevice device = registerDevice("UTC");
        mapEmployee(device, "17", "EMP-17");
        String tomorrow = LocalDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0).toString();

        ResponseEntity<IngestResultDto> response = ingest(device, List.of(new PunchDto("17", tomorrow, "IN")));

        assertThat(response.getBody()).isEqualTo(new IngestResultDto(1, 0, 0, 1));
        assertThat(jdbc.queryForObject("SELECT reason FROM quarantined_punch WHERE device_id = ?",
                String.class, device.id())).isEqualTo("FUTURE_TIMESTAMP");
    }

    @Test
    void reprocessingAfterAddingTheMappingMovesThePunchOutOfQuarantine() {
        TestDevice device = registerDevice("America/Santo_Domingo");
        ingest(device, List.of(new PunchDto("55", "2026-09-07T07:58:00", "IN")));
        assertThat(storedPunches(device)).isZero();

        mapEmployee(device, "55", "EMP-55");
        ResponseEntity<ReprocessDto> response = rest.exchange("/api/v1/quarantine/reprocess", HttpMethod.POST,
                new HttpEntity<>(adminHeaders()), ReprocessDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().resolved()).isGreaterThanOrEqualTo(1);
        assertThat(storedPunches(device)).isEqualTo(1);
        assertThat(quarantinedPunches(device, "PENDING")).isZero();
        assertThat(quarantinedPunches(device, "RESOLVED")).isEqualTo(1);
    }

    @Test
    void rejectsAWrongKeyAMissingKeyAndAnUnknownDeviceAlike() {
        TestDevice device = registerDevice("UTC");
        List<PunchDto> batch = List.of(new PunchDto("17", "2026-09-08T07:58:00", "IN"));

        assertThat(ingest(device.id(), "agw_wrong", batch, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ingest(device.id(), null, batch, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ingest(UUID.randomUUID(), device.apiKey(), batch, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(storedPunches(device)).isZero();
    }

    @Test
    void aDeviceCannotPostWithAnotherDevicesKey() {
        TestDevice device = registerDevice("UTC");
        TestDevice other = registerDevice("UTC");

        ResponseEntity<String> response = ingest(device.id(), other.apiKey(),
                List.of(new PunchDto("17", "2026-09-09T07:58:00", "IN")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anUnauthenticatedCallerGetsNoValidationDetails() {
        TestDevice device = registerDevice("UTC");

        // An invalid body from a caller without a key must be answered 401, not 400.
        ResponseEntity<String> response = ingest(device.id(), null, List.of(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aDisabledDeviceIsRefused() {
        TestDevice device = registerDevice("UTC");
        rest.exchange("/api/v1/devices/{id}/disable", HttpMethod.POST, new HttpEntity<>(adminHeaders()),
                String.class, device.id());

        ResponseEntity<String> response = ingest(device.id(), device.apiKey(),
                List.of(new PunchDto("17", "2026-09-10T07:58:00", "IN")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsAnInvalidBatchWithProblemDetails() {
        TestDevice device = registerDevice("UTC");

        ResponseEntity<String> empty = ingest(device.id(), device.apiKey(), List.of(), String.class);
        ResponseEntity<String> badType = ingest(device.id(), device.apiKey(),
                List.of(new PunchDto("17", "2026-09-11T07:58:00", "LUNCH")), String.class);

        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(empty.getHeaders().getContentType().toString()).contains("application/problem+json");
        assertThat(badType.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void theQuarantineListRejectsAnUnknownStatusOrLimit() {
        for (String query : List.of("status=LOST", "limit=0", "limit=1001")) {
            ResponseEntity<String> response = rest.exchange("/api/v1/quarantine?" + query, HttpMethod.GET,
                    new HttpEntity<>(adminHeaders()), String.class);

            assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
