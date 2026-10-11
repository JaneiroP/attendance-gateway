package com.ohmdevs.attendance.webhook;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ohmdevs.attendance.webhook.WebhookStore.Claim;
import com.ohmdevs.attendance.webhook.WebhookStore.PendingDelivery;

/**
 * Sends queued events to the subscribers' endpoints.
 *
 * <p>Each subscription is a queue that is sent in order, oldest first, a batch
 * per request. A request that is not answered with a 2xx stops that queue:
 * the same batch is sent again after a pause that doubles with each failure,
 * and after too many failures in a row the subscription is suspended until it
 * is resumed through the API. Nothing is skipped, so a subscriber sees events
 * in the order they were published or not at all.
 *
 * <p>Delivery is at least once. A batch is marked delivered only after the
 * endpoint accepted it, so a crash or a lost answer sends it again, and
 * receivers must ignore an {@code eventId} they have already processed.
 */
@Component
class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebhookStore store;
    private final WebhookProperties properties;
    private final WebhookMetrics metrics;
    private final Clock clock;
    private final HttpClient http;

    WebhookDispatcher(WebhookStore store, WebhookProperties properties, WebhookMetrics metrics, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        // Redirects are not followed (the client's default): the request goes
        // to the address that was registered and nowhere else.
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.requestTimeout())
                .build();
    }

    @Scheduled(fixedDelayString = "${attendance.webhooks.poll-interval}")
    void dispatchDue() {
        try {
            // Keep going while some subscriber took a full batch, then wait for the next tick.
            boolean moreQueued;
            do {
                moreQueued = dispatchRound();
            } while (moreQueued);
        } catch (RuntimeException e) {
            log.warn("Webhook dispatch failed, events stay queued for the next round: {}", e.toString());
        }
    }

    private boolean dispatchRound() {
        Instant now = clock.instant();
        List<Claim> claims = store.claimDue(now, now.plus(properties.lease()));
        if (claims.isEmpty()) {
            return false;
        }
        // One virtual thread per subscriber, so an endpoint that takes its
        // whole timeout delays only its own queue.
        List<Future<Boolean>> attempts = new ArrayList<>(claims.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Claim claim : claims) {
                attempts.add(executor.submit(() -> deliver(claim)));
            }
        }
        boolean moreQueued = false;
        for (Future<Boolean> attempt : attempts) {
            moreQueued |= sentFullBatch(attempt);
        }
        return moreQueued;
    }

    private static boolean sentFullBatch(Future<Boolean> attempt) {
        try {
            return attempt.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            // The outcome was not recorded; the lease runs out and the batch is sent again.
            log.warn("Webhook attempt could not be recorded: {}", e.getCause().toString());
            return false;
        }
    }

    /** Sends one batch and records the outcome. Returns whether a full batch was accepted. */
    private boolean deliver(Claim claim) {
        List<PendingDelivery> pending = store.pending(claim.id(), properties.batchSize());
        if (pending.isEmpty()) {
            // Emptied since it was claimed; it becomes due again when the lease runs out.
            return false;
        }
        String failure = send(claim, pending);
        if (failure == null) {
            store.markDelivered(claim.id(), pending, clock.instant());
            metrics.succeeded(pending.size());
            return pending.size() == properties.batchSize();
        }

        int failures = claim.consecutiveFailures() + 1;
        boolean suspend = failures >= properties.maxFailures();
        store.markFailed(claim.id(), failures, failure, clock.instant().plus(properties.backoffAfter(failures)),
                suspend);
        metrics.failed();
        if (suspend) {
            log.warn("Webhook subscription {} suspended after {} failed attempts, last: {}", claim.id(), failures,
                    failure);
        } else {
            log.info("Webhook attempt {} for subscription {} failed: {}", failures, claim.id(), failure);
        }
        return false;
    }

    /** Returns {@code null} when the endpoint accepted the batch, otherwise what went wrong. */
    private String send(Claim claim, List<PendingDelivery> pending) {
        // The payloads are already JSON, so the body is assembled rather than
        // serialized again: what is signed is exactly what is sent.
        String body = pending.stream().map(PendingDelivery::payload)
                .collect(Collectors.joining(",", "{\"events\":[", "]}"));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(claim.url()))
                    .timeout(properties.requestTimeout())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "attendance-gateway")
                    .header(WebhookSignature.HEADER,
                            WebhookSignature.header(claim.secret(), clock.instant().getEpochSecond(), body))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status >= 200 && status < 300 ? null : "HTTP " + status;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted while waiting for the endpoint";
        } catch (IOException | RuntimeException e) {
            return e.toString();
        }
    }
}
