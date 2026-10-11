package com.ohmdevs.attendance.webhook;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param batchSize   the most events sent in one request
 * @param maxFailures consecutive failed attempts after which a subscription is suspended
 */
@ConfigurationProperties("attendance.webhooks")
public record WebhookProperties(Duration pollInterval, int batchSize, Duration requestTimeout,
                                Duration initialBackoff, Duration maxBackoff, int maxFailures) {

    /** The pause before the next attempt, doubling with each consecutive failure up to the maximum. */
    Duration backoffAfter(int consecutiveFailures) {
        // Past this many doublings the maximum has long been reached, and the shift must not overflow.
        int doublings = Math.min(Math.max(consecutiveFailures - 1, 0), 30);
        Duration backoff = initialBackoff.multipliedBy(1L << doublings);
        return backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff;
    }

    /**
     * How long a claimed subscription is kept from other dispatchers: enough
     * to connect, wait for the answer and record it.
     */
    Duration lease() {
        return requestTimeout.multipliedBy(2).plusSeconds(5);
    }
}
