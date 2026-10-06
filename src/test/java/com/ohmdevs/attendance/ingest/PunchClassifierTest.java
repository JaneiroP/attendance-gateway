package com.ohmdevs.attendance.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.ohmdevs.attendance.ingest.PunchClassifier.Accepted;
import com.ohmdevs.attendance.ingest.PunchClassifier.Rejected;

class PunchClassifierTest {

    private static final ZoneId SANTO_DOMINGO = ZoneId.of("America/Santo_Domingo");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final Duration TOLERANCE = Duration.ofMinutes(5);
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @Test
    void convertsDeviceWallClockToUtcUsingTheDeviceZone() {
        var outcome = PunchClassifier.classify(LocalDateTime.parse("2026-10-05T07:30:00"), SANTO_DOMINGO,
                "E-1", NOW, TOLERANCE);

        assertThat(outcome).isEqualTo(new Accepted("E-1", Instant.parse("2026-10-05T11:30:00Z")));
    }

    @Test
    void theSameWallClockMeansDifferentInstantsInDifferentZones() {
        LocalDateTime wallClock = LocalDateTime.parse("2026-01-15T08:00:00");

        assertThat(PunchClassifier.toInstant(wallClock, SANTO_DOMINGO)).isEqualTo(Instant.parse("2026-01-15T12:00:00Z"));
        assertThat(PunchClassifier.toInstant(wallClock, NEW_YORK)).isEqualTo(Instant.parse("2026-01-15T13:00:00Z"));
    }

    @Test
    void quarantinesAPunchWithNoEmployeeMapping() {
        var outcome = PunchClassifier.classify(LocalDateTime.parse("2026-10-05T07:30:00"), SANTO_DOMINGO,
                null, NOW, TOLERANCE);

        assertThat(outcome).isEqualTo(new Rejected(QuarantineReason.UNKNOWN_EMPLOYEE));
    }

    @Test
    void acceptsAPunchSlightlyAheadOfTheServerClock() {
        // 08:04 local is 12:04 UTC, four minutes ahead: inside the tolerance.
        var outcome = PunchClassifier.classify(LocalDateTime.parse("2026-10-05T08:04:00"), SANTO_DOMINGO,
                "E-1", NOW, TOLERANCE);

        assertThat(outcome).isInstanceOf(Accepted.class);
    }

    @Test
    void quarantinesAPunchDatedBeyondTheTolerance() {
        var outcome = PunchClassifier.classify(LocalDateTime.parse("2026-10-05T08:06:00"), SANTO_DOMINGO,
                "E-1", NOW, TOLERANCE);

        assertThat(outcome).isEqualTo(new Rejected(QuarantineReason.FUTURE_TIMESTAMP));
    }

    @Test
    void aWrongDeviceClockWinsOverAMissingMapping() {
        var outcome = PunchClassifier.classify(LocalDateTime.parse("2026-10-06T08:00:00"), SANTO_DOMINGO,
                null, NOW, TOLERANCE);

        assertThat(outcome).isEqualTo(new Rejected(QuarantineReason.FUTURE_TIMESTAMP));
    }

    @Test
    void aTimeSkippedBySpringForwardMovesAheadByTheGap() {
        // 02:30 on 2026-03-08 does not exist in New York; clocks jump from 02:00 to 03:00.
        assertThat(PunchClassifier.toInstant(LocalDateTime.parse("2026-03-08T02:30:00"), NEW_YORK))
                .isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));
    }

    @Test
    void aTimeRepeatedByFallBackTakesTheEarlierOffset() {
        // 01:30 on 2026-11-01 happens twice in New York; the first one is still on daylight time.
        assertThat(PunchClassifier.toInstant(LocalDateTime.parse("2026-11-01T01:30:00"), NEW_YORK))
                .isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
    }
}
