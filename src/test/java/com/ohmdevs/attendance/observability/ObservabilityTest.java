package com.ohmdevs.attendance.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import com.ohmdevs.attendance.AbstractIntegrationTest;

class ObservabilityTest extends AbstractIntegrationTest {

    @Test
    void punchOutcomesAreCountedAndExposedToPrometheus() {
        TestDevice device = registerDevice("UTC");
        mapEmployee(device, "17", newEmployeeId());
        double acceptedBefore = metric("attendance_punches_total", "outcome=\"accepted\"");
        double duplicatesBefore = metric("attendance_punches_total", "outcome=\"duplicate\"");
        double quarantinedBefore = metric("attendance_punches_total", "outcome=\"quarantined\"");
        List<PunchDto> batch = List.of(
                new PunchDto("17", "2026-08-20T08:00:00", "IN"),
                new PunchDto("unknown", "2026-08-20T08:01:00", "IN"));

        ingest(device, batch);
        ingest(device, batch);

        assertThat(metric("attendance_punches_total", "outcome=\"accepted\"") - acceptedBefore).isEqualTo(1);
        assertThat(metric("attendance_punches_total", "outcome=\"duplicate\"") - duplicatesBefore).isEqualTo(1);
        assertThat(metric("attendance_punches_total", "outcome=\"quarantined\"") - quarantinedBefore).isEqualTo(2);
    }

    @Test
    void theOutboxBacklogIsExposed() {
        String scrape = rest.getForObject("/actuator/prometheus", String.class);

        assertThat(scrape)
                .contains("attendance_outbox_pending")
                .contains("attendance_outbox_oldest_pending_age_seconds")
                .contains("attendance_outbox_published_total");
    }

    @Test
    void aRequestGetsACorrelationIdAndKeepsTheOneItBrought() {
        ResponseEntity<String> generated = rest.getForEntity("/actuator/health", String.class);
        assertThat(generated.getHeaders().getFirst("X-Request-Id")).isNotBlank();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", "clock-7.retry-2");
        ResponseEntity<String> supplied = rest.exchange("/actuator/health", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(supplied.getHeaders().getFirst("X-Request-Id")).isEqualTo("clock-7.retry-2");
    }

    @Test
    void aCorrelationIdThatCouldForgeALogLineIsReplaced() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", "abc ERROR fake entry");

        ResponseEntity<String> response = rest.exchange("/actuator/health", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotEqualTo("abc ERROR fake entry").isNotBlank();
    }

    /** Reads one sample from the Prometheus scrape; a series that does not exist yet counts as zero. */
    private double metric(String name, String label) {
        String scrape = rest.getForObject("/actuator/prometheus", String.class);
        Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\{[^}]*" + Pattern.quote(label)
                + "[^}]*}\\s+(\\S+)$").matcher(scrape);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0;
    }
}
