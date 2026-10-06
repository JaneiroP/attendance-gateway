package com.ohmdevs.attendance.outbox;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where business code leaves events for later publication.
 *
 * <p>Writing here instead of sending to Kafka directly is what keeps the two
 * in step: the event commits or rolls back with the business change, and a
 * broker outage neither loses it nor fails the request.
 */
@Repository
public class Outbox {

    private static final int JDBC_BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;

    Outbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Message(UUID eventId, String topic, String key, String payload) {
    }

    record PendingMessage(long id, String topic, String key, String payload) {
    }

    /** Must be called inside the transaction that makes the change the events describe. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(List<Message> messages, Instant createdAt) {
        if (messages.isEmpty()) {
            return;
        }
        OffsetDateTime created = createdAt.atOffset(ZoneOffset.UTC);
        jdbc.batchUpdate("""
                        INSERT INTO outbox_event (event_id, topic, message_key, payload, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                messages, JDBC_BATCH_SIZE, (statement, message) -> {
                    statement.setObject(1, message.eventId());
                    statement.setString(2, message.topic());
                    statement.setString(3, message.key());
                    statement.setString(4, message.payload());
                    statement.setObject(5, created);
                });
    }

    /** Claims the oldest unpublished events for the current transaction. */
    List<PendingMessage> lockPending(int limit) {
        return jdbc.query("""
                        SELECT id, topic, message_key, payload
                          FROM outbox_event
                         WHERE published_at IS NULL
                         ORDER BY id
                         LIMIT ?
                           FOR UPDATE SKIP LOCKED
                        """,
                (row, n) -> new PendingMessage(row.getLong("id"), row.getString("topic"),
                        row.getString("message_key"), row.getString("payload")),
                limit);
    }

    void markPublished(List<PendingMessage> messages, Instant publishedAt) {
        OffsetDateTime published = publishedAt.atOffset(ZoneOffset.UTC);
        jdbc.batchUpdate("UPDATE outbox_event SET published_at = ? WHERE id = ?",
                messages, JDBC_BATCH_SIZE, (statement, message) -> {
                    statement.setObject(1, published);
                    statement.setLong(2, message.id());
                });
    }
}
