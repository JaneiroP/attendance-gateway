package com.ohmdevs.attendance.webhook;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for webhook subscriptions and the queue of events waiting for each of them. */
@Repository
class WebhookStore {

    private static final int JDBC_BATCH_SIZE = 500;
    private static final int MAX_ERROR_LENGTH = 300;

    private static final String SELECT_SUBSCRIPTION = """
            SELECT s.id, s.url, s.description, s.status, s.consecutive_failures, s.last_error,
                   s.last_delivered_at, s.created_at,
                   (SELECT count(*)
                      FROM webhook_delivery d
                     WHERE d.subscription_id = s.id AND d.delivered_at IS NULL) AS pending_events
              FROM webhook_subscription s
            """;

    private static final RowMapper<Subscription> SUBSCRIPTION = (row, n) -> new Subscription(
            row.getObject("id", UUID.class),
            row.getString("url"),
            row.getString("description"),
            row.getString("status"),
            row.getInt("consecutive_failures"),
            row.getString("last_error"),
            instant(row.getObject("last_delivered_at", OffsetDateTime.class)),
            row.getLong("pending_events"),
            instant(row.getObject("created_at", OffsetDateTime.class)));

    private final JdbcTemplate jdbc;

    WebhookStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Subscription(UUID id, String url, String description, String status, int consecutiveFailures,
                        String lastError, Instant lastDeliveredAt, long pendingEvents, Instant createdAt) {
    }

    record Event(UUID eventId, String payload) {
    }

    /** A subscription this dispatcher may send to until its lease runs out. */
    record Claim(UUID id, String url, String secret, int consecutiveFailures) {
    }

    record PendingDelivery(long id, String payload) {
    }

    void create(UUID id, String url, String description, String secret, Instant now) {
        jdbc.update("""
                        INSERT INTO webhook_subscription (id, url, description, secret, status,
                                                          next_attempt_at, created_at)
                        VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
                        """,
                id, url, description, secret, utc(now), utc(now));
    }

    List<Subscription> list() {
        return jdbc.query(SELECT_SUBSCRIPTION + " ORDER BY s.created_at", SUBSCRIPTION);
    }

    Optional<Subscription> find(UUID id) {
        return jdbc.query(SELECT_SUBSCRIPTION + " WHERE s.id = ?", SUBSCRIPTION, id).stream().findFirst();
    }

    boolean replaceSecret(UUID id, String secret) {
        return jdbc.update("UPDATE webhook_subscription SET secret = ? WHERE id = ?", secret, id) > 0;
    }

    /** Puts a subscription back to work at once, whether it was suspended or only pausing between attempts. */
    boolean resume(UUID id, Instant now) {
        return jdbc.update("""
                        UPDATE webhook_subscription
                           SET status = 'ACTIVE', consecutive_failures = 0, next_attempt_at = ?
                         WHERE id = ?
                        """,
                utc(now), id) > 0;
    }

    /** Its queued events go with it. */
    boolean delete(UUID id) {
        return jdbc.update("DELETE FROM webhook_subscription WHERE id = ?", id) > 0;
    }

    /**
     * Queues each event for every subscription and returns how many rows were
     * new. A suspended subscription is included: it is waiting for someone to
     * look at it, and must not lose what happens in the meantime.
     */
    @Transactional
    int enqueue(List<Event> events, Instant now) {
        if (events.isEmpty()) {
            return 0;
        }
        OffsetDateTime created = utc(now);
        int[][] counts = jdbc.batchUpdate("""
                        INSERT INTO webhook_delivery (subscription_id, event_id, payload, created_at)
                        SELECT s.id, ?, ?, ?
                          FROM webhook_subscription s
                        ON CONFLICT ON CONSTRAINT uq_webhook_delivery DO NOTHING
                        """,
                events, JDBC_BATCH_SIZE, (statement, event) -> {
                    statement.setObject(1, event.eventId());
                    statement.setString(2, event.payload());
                    statement.setObject(3, created);
                });
        return Arrays.stream(counts).flatMapToInt(Arrays::stream).filter(count -> count > 0).sum();
    }

    /**
     * Claims every subscription that is due and has something to send, by
     * moving its next attempt to the end of the lease. It is one statement, so
     * two dispatchers cannot claim the same subscription, and no lock is held
     * while the HTTP request is in flight. If the dispatcher dies before
     * recording the outcome, the lease runs out and the events are sent again.
     */
    List<Claim> claimDue(Instant now, Instant leaseUntil) {
        return jdbc.query("""
                        UPDATE webhook_subscription
                           SET next_attempt_at = ?
                         WHERE id IN (SELECT s.id
                                        FROM webhook_subscription s
                                       WHERE s.status = 'ACTIVE'
                                         AND s.next_attempt_at <= ?
                                         AND EXISTS (SELECT 1
                                                       FROM webhook_delivery d
                                                      WHERE d.subscription_id = s.id
                                                        AND d.delivered_at IS NULL)
                                         FOR UPDATE SKIP LOCKED)
                        RETURNING id, url, secret, consecutive_failures
                        """,
                (row, n) -> new Claim(row.getObject("id", UUID.class), row.getString("url"),
                        row.getString("secret"), row.getInt("consecutive_failures")),
                utc(leaseUntil), utc(now));
    }

    /** The oldest events still waiting for a subscription, in the order they were queued. */
    List<PendingDelivery> pending(UUID subscriptionId, int limit) {
        return jdbc.query("""
                        SELECT id, payload
                          FROM webhook_delivery
                         WHERE subscription_id = ? AND delivered_at IS NULL
                         ORDER BY id
                         LIMIT ?
                        """,
                (row, n) -> new PendingDelivery(row.getLong("id"), row.getString("payload")),
                subscriptionId, limit);
    }

    /** Records an accepted request and makes the subscription due again for whatever is still queued. */
    @Transactional
    void markDelivered(UUID subscriptionId, List<PendingDelivery> deliveries, Instant now) {
        OffsetDateTime delivered = utc(now);
        jdbc.batchUpdate("UPDATE webhook_delivery SET delivered_at = ? WHERE id = ?",
                deliveries, JDBC_BATCH_SIZE, (statement, delivery) -> {
                    statement.setObject(1, delivered);
                    statement.setLong(2, delivery.id());
                });
        jdbc.update("""
                        UPDATE webhook_subscription
                           SET consecutive_failures = 0, last_error = NULL, last_delivered_at = ?, next_attempt_at = ?
                         WHERE id = ?
                        """,
                delivered, delivered, subscriptionId);
    }

    void markFailed(UUID subscriptionId, int consecutiveFailures, String error, Instant nextAttemptAt,
                    boolean suspend) {
        String shortened = error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        jdbc.update("""
                        UPDATE webhook_subscription
                           SET consecutive_failures = ?, last_error = ?, next_attempt_at = ?,
                               status = CASE WHEN ? THEN 'SUSPENDED' ELSE status END
                         WHERE id = ?
                        """,
                consecutiveFailures, shortened, utc(nextAttemptAt), suspend, subscriptionId);
    }

    long pendingEvents() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE delivered_at IS NULL",
                Long.class);
        return count == null ? 0 : count;
    }

    long suspendedSubscriptions() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM webhook_subscription WHERE status = 'SUSPENDED'",
                Long.class);
        return count == null ? 0 : count;
    }

    // The PostgreSQL driver has no direct binding for Instant.
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
