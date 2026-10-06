package com.ohmdevs.attendance.outbox;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.ohmdevs.attendance.outbox.Outbox.PendingMessage;

/**
 * Moves events from the outbox table to Kafka.
 *
 * <p>Delivery is at least once. An event is marked published only after the
 * broker acknowledged it, so a crash or a timeout in between sends it again on
 * the next round, and consumers must ignore an event id they have already
 * seen. The alternative, marking first and sending after, would lose events.
 *
 * <p>Events are read in insertion order. That order is only guaranteed with a
 * single running publisher: {@code SKIP LOCKED} lets a second instance take
 * later rows while the first still holds earlier ones.
 */
@Component
class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final Outbox outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;
    private final OutboxProperties properties;
    private final Clock clock;

    OutboxPublisher(Outbox outbox, KafkaTemplate<String, String> kafka, TransactionTemplate transaction,
                    OutboxProperties properties, Clock clock) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.transaction = transaction;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${attendance.outbox.poll-interval}")
    void publishPending() {
        try {
            // Keep draining while full batches come back, then wait for the next tick.
            int published;
            do {
                published = publishBatch();
            } while (published == properties.batchSize());
        } catch (RuntimeException e) {
            log.warn("Outbox publication failed, events stay queued for the next round: {}", e.toString());
        }
    }

    private int publishBatch() {
        Integer published = transaction.execute(status -> {
            List<PendingMessage> pending = outbox.lockPending(properties.batchSize());
            if (pending.isEmpty()) {
                return 0;
            }
            // Send the whole batch, then wait: the producer pipelines the requests.
            List<CompletableFuture<?>> sends = new ArrayList<>(pending.size());
            for (PendingMessage message : pending) {
                sends.add(kafka.send(message.topic(), message.key(), message.payload()));
            }
            awaitAll(sends);
            outbox.markPublished(pending, clock.instant());
            return pending.size();
        });
        return published == null ? 0 : published;
    }

    private void awaitAll(List<CompletableFuture<?>> sends) {
        try {
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the broker", e);
        } catch (Exception e) {
            // Rolls the transaction back, which releases the rows unpublished.
            throw new IllegalStateException("Broker did not acknowledge the batch", e);
        }
    }
}
