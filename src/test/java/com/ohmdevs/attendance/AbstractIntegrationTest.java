package com.ohmdevs.attendance;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for tests that go through real HTTP against a real PostgreSQL and a real Kafka.
 *
 * <p>The behavior under test is {@code ON CONFLICT}, row locking and broker
 * delivery, which in-memory stand-ins do not reproduce. The containers are
 * started once for the whole run and never stopped by hand: the Spring context
 * is cached across test classes, and a container tied to one class would be
 * gone for the next.
 *
 * <p>Tests do not clean the database. Each one registers its own device and
 * site, so they cannot see each other's rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractIntegrationTest {

    protected static final String ADMIN_KEY = "test-admin-key";

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @SuppressWarnings("resource")
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0");

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
            KAFKA.start();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("attendance.admin.api-key", () -> ADMIN_KEY);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Short enough that a test can watch the publisher give up and try again.
        registry.add("attendance.outbox.poll-interval", () -> "200ms");
        registry.add("attendance.outbox.send-timeout", () -> "3s");
        registry.add("spring.kafka.producer.properties.max.block.ms", () -> "2000");
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    public record DeviceDto(UUID id, String name, String siteCode, String timeZone, String status,
                               String lastSeenAt) {
    }

    public record DeviceWithKeyDto(DeviceDto device, String apiKey) {
    }

    public record PunchDto(String userCode, String punchedAt, String type) {
    }

    public record IngestResultDto(int received, int accepted, int duplicates, int quarantined) {
    }

    /** A registered device with its key, plus the site it was registered at. */
    public record TestDevice(UUID id, String apiKey, String siteCode) {
    }

    protected TestDevice registerDevice(String timeZone) {
        String siteCode = "SITE-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<DeviceWithKeyDto> response = rest.exchange("/api/v1/devices", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Front door", "siteCode", siteCode, "timeZone", timeZone),
                        adminHeaders()),
                DeviceWithKeyDto.class);
        DeviceWithKeyDto body = response.getBody();
        return new TestDevice(body.device().id(), body.apiKey(), siteCode);
    }

    protected void mapEmployee(TestDevice device, String userCode, String employeeId) {
        rest.exchange("/api/v1/sites/{site}/mappings/{code}", HttpMethod.PUT,
                new HttpEntity<>(Map.of("employeeId", employeeId), adminHeaders()),
                Void.class, device.siteCode(), userCode);
    }

    protected ResponseEntity<IngestResultDto> ingest(TestDevice device, List<PunchDto> punches) {
        return ingest(device.id(), device.apiKey(), punches, IngestResultDto.class);
    }

    protected <T> ResponseEntity<T> ingest(UUID deviceId, String apiKey, List<PunchDto> punches, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null) {
            headers.set("X-Device-Key", apiKey);
        }
        return rest.exchange("/api/v1/devices/{id}/punches", HttpMethod.POST,
                new HttpEntity<>(Map.of("punches", punches), headers), type, deviceId);
    }

    protected HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-Key", ADMIN_KEY);
        return headers;
    }

    public record DailySummaryDto(String employeeId, String workDate, String firstIn, String lastOut,
                                  int workedMinutes, int punchCount) {
    }

    protected static String newEmployeeId() {
        return "EMP-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** The summary of one employee for one day, or {@code null} while it does not exist yet. */
    protected DailySummaryDto dailySummary(String employeeId, String day) {
        DailySummaryDto[] found = rest.exchange(
                "/api/v1/daily-summaries?employeeId={employee}&from={day}&to={day}", HttpMethod.GET,
                new HttpEntity<>(adminHeaders()), DailySummaryDto[].class, employeeId, day, day).getBody();
        return found == null || found.length == 0 ? null : found[0];
    }

    protected int unpublishedEvents() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Integer.class);
    }

    protected int storedPunches(TestDevice device) {
        return jdbc.queryForObject("SELECT count(*) FROM punch WHERE device_id = ?", Integer.class, device.id());
    }

    protected int quarantinedPunches(TestDevice device, String status) {
        return jdbc.queryForObject("SELECT count(*) FROM quarantined_punch WHERE device_id = ? AND status = ?",
                Integer.class, device.id(), status);
    }
}
