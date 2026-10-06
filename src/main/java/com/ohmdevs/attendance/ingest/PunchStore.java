package com.ohmdevs.attendance.ingest;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the ingest path, written with plain JDBC: these are batch
 * inserts that lean on {@code ON CONFLICT}, which an ORM would only get in the way of.
 */
@Repository
class PunchStore {

    private static final int JDBC_BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;

    PunchStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record AcceptedPunch(UUID deviceId, String siteCode, String userCode, String employeeId,
                         LocalDateTime punchedAtLocal, Instant punchedAtUtc, PunchType type) {
    }

    record RejectedPunch(UUID deviceId, String siteCode, String userCode, LocalDateTime punchedAtLocal,
                         PunchType type, QuarantineReason reason) {
    }

    record PendingQuarantine(UUID id, UUID deviceId, String siteCode, ZoneId zone, String userCode,
                             LocalDateTime punchedAtLocal, PunchType type) {
    }

    record QuarantineEntry(UUID id, UUID deviceId, String siteCode, String userCode, LocalDateTime punchedAtLocal,
                           PunchType type, QuarantineReason reason, String status, Instant receivedAt) {
    }

    record StoredPunch(UUID id, AcceptedPunch punch) {
    }

    /**
     * Inserts the punches that are not already stored and returns the ones that were new.
     *
     * <p>Which rows were new is read from the per-statement update counts,
     * which are exact because each row is its own statement in the JDBC batch.
     * That stops being true with the driver option
     * {@code reWriteBatchedInserts=true}, which merges them into one statement.
     */
    List<StoredPunch> insertPunches(List<AcceptedPunch> punches, Instant receivedAt) {
        if (punches.isEmpty()) {
            return List.of();
        }
        List<StoredPunch> candidates = punches.stream()
                .map(punch -> new StoredPunch(UUID.randomUUID(), punch))
                .toList();
        int[][] counts = jdbc.batchUpdate("""
                        INSERT INTO punch (id, device_id, site_code, user_code, employee_id,
                                           punched_at_local, punched_at_utc, type, received_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT ON CONSTRAINT uq_punch_identity DO NOTHING
                        """,
                candidates, JDBC_BATCH_SIZE, (statement, candidate) -> {
                    AcceptedPunch punch = candidate.punch();
                    statement.setObject(1, candidate.id());
                    statement.setObject(2, punch.deviceId());
                    statement.setString(3, punch.siteCode());
                    statement.setString(4, punch.userCode());
                    statement.setString(5, punch.employeeId());
                    statement.setObject(6, punch.punchedAtLocal());
                    setInstant(statement, 7, punch.punchedAtUtc());
                    statement.setString(8, punch.type().name());
                    setInstant(statement, 9, receivedAt);
                });
        // The counts come back in the order the rows were sent, one array per JDBC batch.
        int[] flatCounts = Arrays.stream(counts).flatMapToInt(Arrays::stream).toArray();
        List<StoredPunch> inserted = new ArrayList<>();
        for (int i = 0; i < flatCounts.length; i++) {
            if (flatCounts[i] > 0) {
                inserted.add(candidates.get(i));
            }
        }
        return inserted;
    }

    /** A punch that is already quarantined stays as it is; a retry must not reopen or duplicate it. */
    void insertQuarantined(List<RejectedPunch> punches, Instant receivedAt) {
        if (punches.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                        INSERT INTO quarantined_punch (id, device_id, site_code, user_code, punched_at_local,
                                                       type, reason, status, received_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                        ON CONFLICT ON CONSTRAINT uq_quarantine_identity DO NOTHING
                        """,
                punches, JDBC_BATCH_SIZE, (statement, punch) -> {
                    statement.setObject(1, UUID.randomUUID());
                    statement.setObject(2, punch.deviceId());
                    statement.setString(3, punch.siteCode());
                    statement.setString(4, punch.userCode());
                    statement.setObject(5, punch.punchedAtLocal());
                    statement.setString(6, punch.type().name());
                    statement.setString(7, punch.reason().name());
                    setInstant(statement, 8, receivedAt);
                });
    }

    void markDeviceSeen(UUID deviceId, Instant seenAt) {
        jdbc.update("UPDATE device SET last_seen_at = ? WHERE id = ?", utc(seenAt), deviceId);
    }

    /**
     * Claims pending entries for this transaction. {@code SKIP LOCKED} lets two
     * reprocess calls run at once without working on the same rows.
     */
    List<PendingQuarantine> lockPending(int limit) {
        return jdbc.query("""
                        SELECT q.id, q.device_id, q.site_code, d.time_zone, q.user_code, q.punched_at_local, q.type
                          FROM quarantined_punch q
                          JOIN device d ON d.id = q.device_id
                         WHERE q.status = 'PENDING'
                         ORDER BY q.received_at
                         LIMIT ?
                           FOR UPDATE OF q SKIP LOCKED
                        """,
                (row, n) -> new PendingQuarantine(
                        row.getObject("id", UUID.class),
                        row.getObject("device_id", UUID.class),
                        row.getString("site_code"),
                        ZoneId.of(row.getString("time_zone")),
                        row.getString("user_code"),
                        row.getObject("punched_at_local", LocalDateTime.class),
                        PunchType.valueOf(row.getString("type"))),
                limit);
    }

    void markResolved(List<UUID> ids, Instant resolvedAt) {
        jdbc.batchUpdate("UPDATE quarantined_punch SET status = 'RESOLVED', resolved_at = ? WHERE id = ?",
                ids, JDBC_BATCH_SIZE, (statement, id) -> {
                    setInstant(statement, 1, resolvedAt);
                    statement.setObject(2, id);
                });
    }

    List<QuarantineEntry> listQuarantine(String status, int limit) {
        return jdbc.query("""
                        SELECT id, device_id, site_code, user_code, punched_at_local, type, reason, status, received_at
                          FROM quarantined_punch
                         WHERE status = ?
                         ORDER BY received_at
                         LIMIT ?
                        """,
                (row, n) -> new QuarantineEntry(
                        row.getObject("id", UUID.class),
                        row.getObject("device_id", UUID.class),
                        row.getString("site_code"),
                        row.getString("user_code"),
                        row.getObject("punched_at_local", LocalDateTime.class),
                        PunchType.valueOf(row.getString("type")),
                        QuarantineReason.valueOf(row.getString("reason")),
                        row.getString("status"),
                        row.getObject("received_at", OffsetDateTime.class).toInstant()),
                status, limit);
    }

    private static void setInstant(PreparedStatement statement, int index, Instant instant) throws SQLException {
        statement.setObject(index, utc(instant));
    }

    // The PostgreSQL driver has no direct binding for Instant.
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
