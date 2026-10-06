package com.ohmdevs.attendance.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The two numbers that say whether events are flowing: how many are waiting,
 * and how long the oldest has waited. Both stay near zero when the broker is
 * healthy; the age is the one to alert on.
 */
@Component
class OutboxMetrics {

    private final JdbcTemplate jdbc;
    private final Counter published;

    OutboxMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.published = Counter.builder("attendance.outbox.published")
                .description("Events acknowledged by the broker")
                .register(registry);
        // Read on each scrape. Both queries are served by the partial index on unpublished rows.
        Gauge.builder("attendance.outbox.pending", this, OutboxMetrics::pending)
                .description("Events not yet acknowledged by the broker")
                .register(registry);
        Gauge.builder("attendance.outbox.oldest.pending.age", this, OutboxMetrics::oldestPendingAgeSeconds)
                .description("How long the oldest unpublished event has been waiting")
                .baseUnit("seconds")
                .register(registry);
    }

    void published(int count) {
        published.increment(count);
    }

    private double pending() {
        return query("SELECT count(*) FROM outbox_event WHERE published_at IS NULL");
    }

    private double oldestPendingAgeSeconds() {
        return query("""
                SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0)
                  FROM outbox_event
                 WHERE published_at IS NULL
                """);
    }

    private double query(String sql) {
        try {
            Double value = jdbc.queryForObject(sql, Double.class);
            return value == null ? 0 : value;
        } catch (RuntimeException e) {
            // A gauge must not throw; no reading is more honest than a made-up one.
            return Double.NaN;
        }
    }
}
