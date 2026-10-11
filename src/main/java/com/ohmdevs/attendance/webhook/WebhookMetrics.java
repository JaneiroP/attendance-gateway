package com.ohmdevs.attendance.webhook;

import java.util.function.LongSupplier;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Whether downstream systems are keeping up. A suspended subscription is the
 * one to alert on: nothing reaches that system until someone resumes it.
 */
@Component
class WebhookMetrics {

    private final Counter succeeded;
    private final Counter failed;
    private final Counter delivered;

    WebhookMetrics(WebhookStore store, MeterRegistry registry) {
        this.succeeded = attempts(registry, "succeeded");
        this.failed = attempts(registry, "failed");
        this.delivered = Counter.builder("attendance.webhook.events.delivered")
                .description("Events accepted by a subscriber's endpoint")
                .register(registry);
        // Read on each scrape.
        Gauge.builder("attendance.webhook.events.pending", () -> read(store::pendingEvents))
                .description("Events queued for a subscriber and not yet accepted by it")
                .register(registry);
        Gauge.builder("attendance.webhook.subscriptions.suspended", () -> read(store::suspendedSubscriptions))
                .description("Subscriptions that stopped receiving after too many failed attempts")
                .register(registry);
    }

    void succeeded(int events) {
        succeeded.increment();
        delivered.increment(events);
    }

    void failed() {
        failed.increment();
    }

    private static Counter attempts(MeterRegistry registry, String result) {
        return Counter.builder("attendance.webhook.attempts")
                .description("Requests sent to subscribers, by whether the endpoint accepted them")
                .tag("result", result)
                .register(registry);
    }

    private static double read(LongSupplier query) {
        try {
            return query.getAsLong();
        } catch (RuntimeException e) {
            // A gauge must not throw; no reading is more honest than a made-up one.
            return Double.NaN;
        }
    }
}
