package com.ohmdevs.attendance.ingest;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Decides whether a punch can be accepted and, if so, what instant it really
 * happened at. Pure on purpose: every rule here is testable without a database.
 */
final class PunchClassifier {

    private PunchClassifier() {
    }

    sealed interface Outcome permits Accepted, Rejected {
    }

    record Accepted(String employeeId, Instant punchedAtUtc) implements Outcome {
    }

    record Rejected(QuarantineReason reason) implements Outcome {
    }

    /**
     * @param punchedAtLocal wall-clock time as the device reported it
     * @param zone           the device's time zone
     * @param employeeId     the mapped employee, or {@code null} when the clock code is unknown
     */
    static Outcome classify(LocalDateTime punchedAtLocal, ZoneId zone, String employeeId,
                            Instant now, Duration futureTolerance) {
        Instant punchedAtUtc = toInstant(punchedAtLocal, zone);
        // Checked first: a device with a wrong clock is the more basic fault, and
        // fixing the mapping would not make its punches trustworthy.
        if (punchedAtUtc.isAfter(now.plus(futureTolerance))) {
            return new Rejected(QuarantineReason.FUTURE_TIMESTAMP);
        }
        if (employeeId == null) {
            return new Rejected(QuarantineReason.UNKNOWN_EMPLOYEE);
        }
        return new Accepted(employeeId, punchedAtUtc);
    }

    /**
     * Daylight saving edges follow {@link LocalDateTime#atZone}: a time that
     * does not exist (spring forward) moves ahead by the gap, and a time that
     * happens twice (fall back) takes the earlier offset.
     */
    static Instant toInstant(LocalDateTime punchedAtLocal, ZoneId zone) {
        return punchedAtLocal.atZone(zone).toInstant();
    }
}
