package com.ohmdevs.attendance.summary;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Turns one person's punches for one day into a summary. Pure, and always
 * computed from the full set of punches, so the result does not depend on the
 * order in which they arrived.
 */
final class DailySummaryCalculator {

    private DailySummaryCalculator() {
    }

    record Punch(Instant at, String type) {
    }

    /**
     * @param firstIn       earliest IN of the day, or {@code null} if there is none
     * @param lastOut       latest OUT of the day, or {@code null} if there is none
     * @param workedMinutes minutes between each IN and the OUT that closes it
     */
    record Summary(Instant firstIn, Instant lastOut, int workedMinutes, int punchCount) {
    }

    /**
     * Time counts from an IN to the next OUT. A second IN while one is open is
     * ignored (the person badged twice), and an OUT with no open IN adds
     * nothing. Only what both ends prove is counted as worked.
     */
    static Summary summarize(List<Punch> punches) {
        List<Punch> ordered = punches.stream().sorted(Comparator.comparing(Punch::at)).toList();
        Instant firstIn = null;
        Instant lastOut = null;
        Instant openIn = null;
        Duration worked = Duration.ZERO;
        for (Punch punch : ordered) {
            if ("IN".equals(punch.type())) {
                if (firstIn == null) {
                    firstIn = punch.at();
                }
                if (openIn == null) {
                    openIn = punch.at();
                }
            } else {
                lastOut = punch.at();
                if (openIn != null) {
                    worked = worked.plus(Duration.between(openIn, punch.at()));
                    openIn = null;
                }
            }
        }
        return new Summary(firstIn, lastOut, (int) worked.toMinutes(), ordered.size());
    }
}
