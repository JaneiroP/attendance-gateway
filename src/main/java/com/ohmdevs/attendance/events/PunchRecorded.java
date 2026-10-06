package com.ohmdevs.attendance.events;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Published once for every punch stored for the first time. Documented in
 * {@code docs/events.md}; fields may be added, never removed or repurposed.
 *
 * @param eventId        identifies this event; a consumer that has seen it must ignore a redelivery
 * @param punchedAtLocal wall-clock time at the device, which decides the work day
 * @param punchedAtUtc   the same moment as an instant
 * @param type           {@code IN} or {@code OUT}
 */
public record PunchRecorded(
        UUID eventId,
        UUID punchId,
        UUID deviceId,
        String siteCode,
        String employeeId,
        LocalDateTime punchedAtLocal,
        Instant punchedAtUtc,
        String type,
        Instant recordedAt) {
}
