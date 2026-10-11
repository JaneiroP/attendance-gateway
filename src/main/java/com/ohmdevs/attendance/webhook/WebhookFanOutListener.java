package com.ohmdevs.attendance.webhook;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import com.ohmdevs.attendance.events.PunchRecorded;
import com.ohmdevs.attendance.events.Topics;
import com.ohmdevs.attendance.webhook.WebhookStore.Event;

/**
 * Queues every punch event for every webhook subscription.
 *
 * <p>It reads the topic like any outside consumer would, under its own
 * consumer group, so webhooks see exactly what Kafka consumers see and a slow
 * subscriber never holds back the daily summary. The event is passed on as it
 * was published, byte for byte.
 */
@Component
class WebhookFanOutListener {

    static final String GROUP = "attendance-gateway.webhooks";

    private static final Logger log = LoggerFactory.getLogger(WebhookFanOutListener.class);

    private final WebhookStore store;
    private final JsonMapper json;
    private final Clock clock;

    WebhookFanOutListener(WebhookStore store, JsonMapper json, Clock clock) {
        this.store = store;
        this.json = json;
        this.clock = clock;
    }

    @KafkaListener(topics = Topics.PUNCH_RECORDED, groupId = GROUP)
    void onPunchRecorded(List<ConsumerRecord<String, String>> records) {
        List<Event> events = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            try {
                PunchRecorded event = json.readValue(record.value(), PunchRecorded.class);
                if (event.eventId() != null) {
                    events.add(new Event(event.eventId(), record.value()));
                    continue;
                }
            } catch (JacksonException e) {
                // Falls through to the skip below.
            }
            // The daily summary consumer sends the same record to the dead-letter
            // topic; doing it here too would only put it there twice.
            log.warn("Skipping an unreadable record at {}-{}@{}", record.topic(), record.partition(),
                    record.offset());
        }
        store.enqueue(events, clock.instant());
    }
}
