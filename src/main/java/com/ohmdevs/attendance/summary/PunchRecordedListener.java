package com.ohmdevs.attendance.summary;

import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import com.ohmdevs.attendance.events.PunchRecorded;
import com.ohmdevs.attendance.events.Topics;

/** Feeds punch events from Kafka into the daily summary. */
@Component
class PunchRecordedListener {

    private final DailySummaryProjector projector;
    private final JsonMapper json;
    private final Counter applied;
    private final Counter redelivered;

    PunchRecordedListener(DailySummaryProjector projector, JsonMapper json, MeterRegistry registry) {
        this.projector = projector;
        this.json = json;
        this.applied = result(registry, "applied");
        this.redelivered = result(registry, "redelivered");
    }

    private static Counter result(MeterRegistry registry, String result) {
        return Counter.builder("attendance.summary.events")
                .description("Punch events consumed by the daily summary, by whether they were new")
                .tag("result", result)
                .register(registry);
    }

    /**
     * Receives one poll's worth of records. If one of them cannot be read, the
     * readable ones before it are applied, and the error handler is told which
     * record failed: it sends that one to the dead-letter topic and has the
     * rest delivered again.
     */
    @KafkaListener(topics = Topics.PUNCH_RECORDED)
    void onPunchRecorded(List<ConsumerRecord<String, String>> records) {
        List<PunchRecorded> events = new ArrayList<>(records.size());
        for (int index = 0; index < records.size(); index++) {
            try {
                events.add(parse(records.get(index).value()));
            } catch (UnreadableEventException unreadable) {
                apply(events);
                throw new BatchListenerFailedException("Unreadable record in batch", unreadable, index);
            }
        }
        apply(events);
    }

    private void apply(List<PunchRecorded> events) {
        int newEvents = projector.applyAll(events);
        applied.increment(newEvents);
        redelivered.increment(events.size() - newEvents);
    }

    private PunchRecorded parse(String payload) {
        PunchRecorded event;
        try {
            event = json.readValue(payload, PunchRecorded.class);
        } catch (JacksonException e) {
            throw new UnreadableEventException("Not a PunchRecorded event", e);
        }
        if (event.eventId() == null || event.employeeId() == null || event.punchedAtLocal() == null
                || event.punchedAtUtc() == null || !("IN".equals(event.type()) || "OUT".equals(event.type()))) {
            throw new UnreadableEventException("PunchRecorded event is missing required fields", null);
        }
        return event;
    }

    /** A message that will never become processable, however often it is retried. */
    static class UnreadableEventException extends RuntimeException {

        UnreadableEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
