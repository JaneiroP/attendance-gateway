package com.ohmdevs.attendance.summary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ohmdevs.attendance.events.PunchRecorded;
import com.ohmdevs.attendance.summary.DailySummaryCalculator.Punch;
import com.ohmdevs.attendance.summary.DailySummaryCalculator.Summary;

/**
 * Builds the per-employee daily summary from punch events.
 *
 * <p>It reads only what the events carry and keeps its own copy of them: it
 * never looks at the ingest tables, so it could be moved to another service
 * with its three tables and nothing else.
 */
@Component
public class DailySummaryProjector {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    DailySummaryProjector(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record DailySummary(String employeeId, LocalDate workDate, Instant firstIn, Instant lastOut,
                               int workedMinutes, int punchCount) {
    }

    /**
     * Applies one event. Safe to call again with the same event: the second
     * call finds the event id already stored and changes nothing.
     *
     * @return {@code false} when the event had been applied before
     */
    @Transactional
    public boolean apply(PunchRecorded event) {
        // The work day is the calendar day at the device. A shift that crosses
        // midnight is split across two days; that is a known limitation.
        LocalDate workDate = event.punchedAtLocal().toLocalDate();

        // One writer per employee and day. Partitioning by employee already
        // gives that in normal operation; the lock covers a consumer rebalance,
        // where two consumers can briefly hold the same partition.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", row -> {
        }, event.employeeId() + "|" + workDate);

        int inserted = jdbc.update("""
                        INSERT INTO summary_punch (event_id, employee_id, work_date, punched_at_utc, type)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (event_id) DO NOTHING
                        """,
                event.eventId(), event.employeeId(), workDate, utc(event.punchedAtUtc()), event.type());
        if (inserted == 0) {
            return false;
        }

        List<Punch> punches = jdbc.query(
                "SELECT punched_at_utc, type FROM summary_punch WHERE employee_id = ? AND work_date = ?",
                (row, n) -> new Punch(row.getObject("punched_at_utc", OffsetDateTime.class).toInstant(),
                        row.getString("type")),
                event.employeeId(), workDate);
        Summary summary = DailySummaryCalculator.summarize(punches);

        jdbc.update("""
                        INSERT INTO daily_summary (employee_id, work_date, first_in, last_out,
                                                   worked_minutes, punch_count, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (employee_id, work_date) DO UPDATE
                           SET first_in = EXCLUDED.first_in,
                               last_out = EXCLUDED.last_out,
                               worked_minutes = EXCLUDED.worked_minutes,
                               punch_count = EXCLUDED.punch_count,
                               updated_at = EXCLUDED.updated_at
                        """,
                event.employeeId(), workDate, utc(summary.firstIn()), utc(summary.lastOut()),
                summary.workedMinutes(), summary.punchCount(), utc(clock.instant()));
        return true;
    }

    @Transactional(readOnly = true)
    public List<DailySummary> find(String employeeId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                        SELECT employee_id, work_date, first_in, last_out, worked_minutes, punch_count
                          FROM daily_summary
                         WHERE employee_id = ? AND work_date BETWEEN ? AND ?
                         ORDER BY work_date
                        """,
                (row, n) -> new DailySummary(
                        row.getString("employee_id"),
                        row.getObject("work_date", LocalDate.class),
                        instant(row.getObject("first_in", OffsetDateTime.class)),
                        instant(row.getObject("last_out", OffsetDateTime.class)),
                        row.getInt("worked_minutes"),
                        row.getInt("punch_count")),
                employeeId, from, to);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
