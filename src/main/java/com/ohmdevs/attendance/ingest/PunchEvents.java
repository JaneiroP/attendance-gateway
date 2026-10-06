package com.ohmdevs.attendance.ingest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import com.ohmdevs.attendance.events.PunchRecorded;
import com.ohmdevs.attendance.events.Topics;
import com.ohmdevs.attendance.ingest.PunchStore.AcceptedPunch;
import com.ohmdevs.attendance.ingest.PunchStore.StoredPunch;
import com.ohmdevs.attendance.outbox.Outbox;

/** Announces newly stored punches through the outbox. */
@Component
class PunchEvents {

    private final Outbox outbox;
    private final JsonMapper json;

    PunchEvents(Outbox outbox, JsonMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    /** One event per punch, keyed by employee so a person's punches stay in order on the topic. */
    void recorded(List<StoredPunch> stored, Instant recordedAt) {
        List<Outbox.Message> messages = stored.stream().map(entry -> {
            AcceptedPunch punch = entry.punch();
            PunchRecorded event = new PunchRecorded(UUID.randomUUID(), entry.id(), punch.deviceId(),
                    punch.siteCode(), punch.employeeId(), punch.punchedAtLocal(), punch.punchedAtUtc(),
                    punch.type().name(), recordedAt);
            return new Outbox.Message(event.eventId(), Topics.PUNCH_RECORDED, punch.employeeId(),
                    json.writeValueAsString(event));
        }).toList();
        outbox.append(messages, recordedAt);
    }
}
