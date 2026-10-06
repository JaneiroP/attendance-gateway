package com.ohmdevs.attendance.summary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

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

    /** One employee's work day: the unit a summary is computed for. */
    private record Day(String employeeId, LocalDate workDate) implements Comparable<Day> {

        // The work day is the calendar day at the device. A shift that crosses
        // midnight is split across two days; that is a known limitation.
        static Day of(PunchRecorded event) {
            return new Day(event.employeeId(), event.punchedAtLocal().toLocalDate());
        }

        @Override
        public int compareTo(Day other) {
            int byEmployee = employeeId.compareTo(other.employeeId);
            return byEmployee != 0 ? byEmployee : workDate.compareTo(other.workDate);
        }
    }

    /** Applies one event; see {@link #applyAll}. Returns {@code false} when it had been applied before. */
    @Transactional
    public boolean apply(PunchRecorded event) {
        return applyAll(List.of(event)) == 1;
    }

    /**
     * Applies a batch of events in one transaction and returns how many were
     * new. Safe to call again with events already applied: their ids are
     * already stored, they insert nothing, and no summary is touched for them.
     *
     * <p>Working per batch is what makes the consumer keep up: one commit and
     * one recomputation per affected day, however many events the batch holds.
     */
    @Transactional
    public int applyAll(List<PunchRecorded> events) {
        if (events.isEmpty()) {
            return 0;
        }
        // One writer per employee and day. Partitioning by employee already
        // gives that in normal operation; the locks cover a consumer rebalance,
        // where two consumers can briefly hold the same partition. They are
        // taken in a fixed order so two batches cannot deadlock on them.
        TreeSet<Day> days = new TreeSet<>();
        events.forEach(event -> days.add(Day.of(event)));
        for (Day day : days) {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", row -> {
            }, day.employeeId() + "|" + day.workDate());
        }

        int[][] counts = jdbc.batchUpdate("""
                        INSERT INTO summary_punch (event_id, employee_id, work_date, punched_at_utc, type)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (event_id) DO NOTHING
                        """,
                events, events.size(), (statement, event) -> {
                    statement.setObject(1, event.eventId());
                    statement.setString(2, event.employeeId());
                    statement.setObject(3, event.punchedAtLocal().toLocalDate());
                    statement.setObject(4, utc(event.punchedAtUtc()));
                    statement.setString(5, event.type());
                });
        int[] inserted = Arrays.stream(counts).flatMapToInt(Arrays::stream).toArray();

        TreeSet<Day> changed = new TreeSet<>();
        List<PunchRecorded> applied = new ArrayList<>();
        for (int i = 0; i < inserted.length; i++) {
            if (inserted[i] > 0) {
                applied.add(events.get(i));
                changed.add(Day.of(events.get(i)));
            }
        }
        changed.forEach(this::recompute);
        return applied.size();
    }

    private void recompute(Day day) {
        String employeeId = day.employeeId();
        LocalDate workDate = day.workDate();
        List<Punch> punches = jdbc.query(
                "SELECT punched_at_utc, type FROM summary_punch WHERE employee_id = ? AND work_date = ?",
                (row, n) -> new Punch(row.getObject("punched_at_utc", OffsetDateTime.class).toInstant(),
                        row.getString("type")),
                employeeId, workDate);
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
                employeeId, workDate, utc(summary.firstIn()), utc(summary.lastOut()),
                summary.workedMinutes(), summary.punchCount(), utc(clock.instant()));
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
