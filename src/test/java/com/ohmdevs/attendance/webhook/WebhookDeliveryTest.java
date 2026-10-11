package com.ohmdevs.attendance.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import tools.jackson.databind.json.JsonMapper;

import com.ohmdevs.attendance.AbstractIntegrationTest;
import com.ohmdevs.attendance.events.PunchRecorded;

/**
 * Ingest to Kafka to a subscriber's endpoint, which here is a small HTTP
 * server inside the test that can be told to refuse.
 *
 * <p>A subscription is sent every event published while it exists, including
 * ones other tests left in flight, so each test looks only at the events of
 * its own employee.
 */
class WebhookDeliveryTest extends AbstractIntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(30);
    // The value of attendance.webhooks.max-failures in AbstractIntegrationTest.
    private static final int MAX_FAILURES = 6;

    @Autowired
    private JsonMapper json;

    private Receiver receiver;
    private final List<UUID> subscriptions = new ArrayList<>();

    @BeforeEach
    void startReceiver() throws IOException {
        receiver = new Receiver();
    }

    @AfterEach
    void unsubscribeAndStopReceiver() {
        subscriptions.forEach(id -> rest.exchange("/api/v1/webhooks/{id}", HttpMethod.DELETE,
                new HttpEntity<>(adminHeaders()), Void.class, id));
        receiver.close();
    }

    @Test
    void aSubscriberReceivesEachPunchOnceInOrderWithAValidSignature() {
        TestDevice device = registerDevice("America/Santo_Domingo");
        String employee = newEmployeeId();
        mapEmployee(device, "17", employee);
        SubscriptionWithSecretDto subscribed = subscribe(receiver.url());
        List<PunchDto> batch = List.of(
                new PunchDto("17", "2026-09-15T07:58:00", "IN"),
                new PunchDto("17", "2026-09-15T17:02:00", "OUT"));

        ingest(device, batch);
        ingest(device, batch);

        await().atMost(PATIENCE).until(() -> acceptedEvents(employee).size() == 2);
        // Time for a duplicate to show up, if the retried batch had produced one.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> acceptedEvents(employee).size() == 2);

        List<PunchRecorded> events = acceptedEvents(employee);
        assertThat(events).extracting(PunchRecorded::type).containsExactly("IN", "OUT");
        assertThat(events.get(0).punchedAtUtc()).isEqualTo(Instant.parse("2026-09-15T11:58:00Z"));
        assertThat(events.get(0).siteCode()).isEqualTo(device.siteCode());
        assertThat(receiver.requests).isNotEmpty().allSatisfy(request ->
                assertSignedWith(subscribed.secret(), request));

        await().atMost(PATIENCE).untilAsserted(() -> {
            SubscriptionDto subscription = subscription(subscribed.subscription().id());
            assertThat(subscription.status()).isEqualTo("ACTIVE");
            assertThat(subscription.pendingEvents()).isZero();
            assertThat(subscription.lastDeliveredAt()).isNotNull();
        });
    }

    @Test
    void aRefusedBatchIsSentAgainUntilItIsAccepted() {
        TestDevice device = registerDevice("UTC");
        String employee = newEmployeeId();
        mapEmployee(device, "17", employee);
        receiver.answer.set(500);
        SubscriptionWithSecretDto subscribed = subscribe(receiver.url());

        ingest(device, List.of(
                new PunchDto("17", "2026-09-16T08:00:00", "IN"),
                new PunchDto("17", "2026-09-16T17:00:00", "OUT")));

        await().atMost(PATIENCE).until(() -> receiver.requests.size() >= 2);
        assertThat(acceptedEvents(employee)).isEmpty();
        receiver.answer.set(200);

        await().atMost(PATIENCE).until(() -> acceptedEvents(employee).size() == 2);
        assertThat(acceptedEvents(employee)).extracting(PunchRecorded::type).containsExactly("IN", "OUT");
        await().atMost(PATIENCE).untilAsserted(() -> {
            SubscriptionDto subscription = subscription(subscribed.subscription().id());
            assertThat(subscription.status()).isEqualTo("ACTIVE");
            assertThat(subscription.consecutiveFailures()).isZero();
            assertThat(subscription.lastError()).isNull();
            assertThat(subscription.pendingEvents()).isZero();
        });
    }

    @Test
    void anEndpointThatKeepsRefusingIsSuspendedAndLosesNothingOnceResumed() {
        TestDevice device = registerDevice("UTC");
        String employee = newEmployeeId();
        mapEmployee(device, "17", employee);
        receiver.answer.set(503);
        UUID id = subscribe(receiver.url()).subscription().id();

        ingest(device, List.of(new PunchDto("17", "2026-09-17T08:00:00", "IN")));

        await().atMost(PATIENCE).until(() -> "SUSPENDED".equals(subscription(id).status()));
        SubscriptionDto suspended = subscription(id);
        assertThat(suspended.consecutiveFailures()).isEqualTo(MAX_FAILURES);
        assertThat(suspended.lastError()).isEqualTo("HTTP 503");
        assertThat(receiver.requests).hasSize(MAX_FAILURES);

        // The endpoint recovers and more punches arrive, but nobody has resumed the subscription.
        receiver.answer.set(200);
        ingest(device, List.of(new PunchDto("17", "2026-09-17T17:00:00", "OUT")));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> receiver.requests.size() == MAX_FAILURES);
        assertThat(subscription(id).pendingEvents()).isGreaterThanOrEqualTo(2);

        ResponseEntity<SubscriptionDto> resumed = rest.exchange("/api/v1/webhooks/{id}/resume", HttpMethod.POST,
                new HttpEntity<>(adminHeaders()), SubscriptionDto.class, id);
        assertThat(resumed.getBody().status()).isEqualTo("ACTIVE");

        await().atMost(PATIENCE).until(() -> acceptedEvents(employee).size() == 2);
        assertThat(acceptedEvents(employee)).extracting(PunchRecorded::type).containsExactly("IN", "OUT");
    }

    @Test
    void afterTheSecretIsRotatedRequestsAreSignedWithTheNewOne() {
        TestDevice device = registerDevice("UTC");
        String employee = newEmployeeId();
        mapEmployee(device, "17", employee);
        SubscriptionWithSecretDto subscribed = subscribe(receiver.url());
        // Whatever other tests left in flight is sent with the first secret; let it pass.
        await().atMost(PATIENCE).until(() -> subscription(subscribed.subscription().id()).pendingEvents() == 0);

        SubscriptionWithSecretDto rotated = rest.exchange("/api/v1/webhooks/{id}/rotate-secret", HttpMethod.POST,
                new HttpEntity<>(adminHeaders()), SubscriptionWithSecretDto.class,
                subscribed.subscription().id()).getBody();
        assertThat(rotated.secret()).isNotEqualTo(subscribed.secret());
        int requestsBefore = receiver.requests.size();
        ingest(device, List.of(new PunchDto("17", "2026-09-18T08:00:00", "IN")));

        await().atMost(PATIENCE).until(() -> acceptedEvents(employee).size() == 1);
        assertThat(receiver.requests.subList(requestsBefore, receiver.requests.size())).isNotEmpty()
                .allSatisfy(request -> assertSignedWith(rotated.secret(), request));
    }

    @Test
    void anAddressThatIsNotHttpIsRejected() {
        for (String url : List.of("ftp://example.com/hook", "/hook", "not a url")) {
            ResponseEntity<String> response = rest.exchange("/api/v1/webhooks", HttpMethod.POST,
                    new HttpEntity<>(Map.of("url", url), adminHeaders()), String.class);

            assertThat(response.getStatusCode()).as(url).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void subscriptionsAreAdministrationAndTheSecretIsShownOnlyOnce() {
        ResponseEntity<String> anonymous = rest.exchange("/api/v1/webhooks", HttpMethod.POST,
                new HttpEntity<>(Map.of("url", receiver.url()), new HttpHeaders()), String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        SubscriptionWithSecretDto subscribed = subscribe(receiver.url());
        String read = rest.exchange("/api/v1/webhooks/{id}", HttpMethod.GET, new HttpEntity<>(adminHeaders()),
                String.class, subscribed.subscription().id()).getBody();
        String listed = rest.exchange("/api/v1/webhooks", HttpMethod.GET, new HttpEntity<>(adminHeaders()),
                String.class).getBody();
        assertThat(read).contains(receiver.url()).doesNotContain(subscribed.secret());
        assertThat(listed).contains(receiver.url()).doesNotContain(subscribed.secret());

        ResponseEntity<String> unknown = rest.exchange("/api/v1/webhooks/{id}", HttpMethod.GET,
                new HttpEntity<>(adminHeaders()), String.class, UUID.randomUUID());
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    public record SubscriptionDto(UUID id, String url, String status, int consecutiveFailures, String lastError,
                                  String lastDeliveredAt, long pendingEvents) {
    }

    public record SubscriptionWithSecretDto(SubscriptionDto subscription, String secret) {
    }

    public record Envelope(List<PunchRecorded> events) {
    }

    private SubscriptionWithSecretDto subscribe(String url) {
        ResponseEntity<SubscriptionWithSecretDto> response = rest.exchange("/api/v1/webhooks", HttpMethod.POST,
                new HttpEntity<>(Map.of("url", url, "description", "test receiver"), adminHeaders()),
                SubscriptionWithSecretDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        subscriptions.add(response.getBody().subscription().id());
        return response.getBody();
    }

    private SubscriptionDto subscription(UUID id) {
        return rest.exchange("/api/v1/webhooks/{id}", HttpMethod.GET, new HttpEntity<>(adminHeaders()),
                SubscriptionDto.class, id).getBody();
    }

    /** One employee's events from the requests the receiver answered with a 2xx, in the order they came. */
    private List<PunchRecorded> acceptedEvents(String employeeId) {
        List<PunchRecorded> events = new ArrayList<>();
        for (Receiver.Request request : receiver.requests) {
            if (request.answer() == 200) {
                json.readValue(request.body(), Envelope.class).events().stream()
                        .filter(event -> employeeId.equals(event.employeeId()))
                        .forEach(events::add);
            }
        }
        return events;
    }

    /** What a receiver does: recompute the HMAC over timestamp and body, and refuse old timestamps. */
    private static void assertSignedWith(String secret, Receiver.Request request) {
        assertThat(request.signature()).matches("t=\\d+,v1=[0-9a-f]{64}");
        String[] parts = request.signature().split(",");
        long timestamp = Long.parseLong(parts[0].substring("t=".length()));
        assertThat(parts[1].substring("v1=".length()))
                .isEqualTo(WebhookSignature.sign(secret, timestamp, request.body()));
        assertThat(Instant.ofEpochSecond(timestamp)).isBetween(Instant.now().minusSeconds(120), Instant.now());
    }

    /** A subscriber's endpoint. It records every request and answers with the status it was last told to. */
    private static final class Receiver {

        record Request(String signature, String body, int answer) {
        }

        final List<Request> requests = new CopyOnWriteArrayList<>();
        final AtomicInteger answer = new AtomicInteger(200);
        private final HttpServer server;

        Receiver() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/hook", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int status = answer.get();
                requests.add(new Request(exchange.getRequestHeaders().getFirst(WebhookSignature.HEADER), body,
                        status));
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        }

        void close() {
            server.stop(0);
        }
    }
}
