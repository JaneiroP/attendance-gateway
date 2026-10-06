package com.ohmdevs.attendance.summary;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import com.ohmdevs.attendance.events.PunchRecorded;
import com.ohmdevs.attendance.events.Topics;

/** Feeds punch events from Kafka into the daily summary. */
@Component
class PunchRecordedListener {

    private final DailySummaryProjector projector;
    private final JsonMapper json;

    PunchRecordedListener(DailySummaryProjector projector, JsonMapper json) {
        this.projector = projector;
        this.json = json;
    }

    @KafkaListener(topics = Topics.PUNCH_RECORDED)
    void onPunchRecorded(String payload) {
        projector.apply(parse(payload));
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
