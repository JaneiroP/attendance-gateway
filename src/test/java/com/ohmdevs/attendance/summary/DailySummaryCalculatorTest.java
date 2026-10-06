package com.ohmdevs.attendance.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ohmdevs.attendance.summary.DailySummaryCalculator.Punch;
import com.ohmdevs.attendance.summary.DailySummaryCalculator.Summary;

class DailySummaryCalculatorTest {

    private static Punch in(String time) {
        return new Punch(Instant.parse("2026-09-01T" + time + ":00Z"), "IN");
    }

    private static Punch out(String time) {
        return new Punch(Instant.parse("2026-09-01T" + time + ":00Z"), "OUT");
    }

    @Test
    void countsTheTimeBetweenAnInAndItsOut() {
        Summary summary = DailySummaryCalculator.summarize(List.of(in("08:00"), out("17:00")));

        assertThat(summary).isEqualTo(new Summary(in("08:00").at(), out("17:00").at(), 540, 2));
    }

    @Test
    void aLunchBreakIsNotWorkedTime() {
        Summary summary = DailySummaryCalculator.summarize(
                List.of(in("08:00"), out("12:00"), in("13:00"), out("17:00")));

        assertThat(summary.workedMinutes()).isEqualTo(480);
        assertThat(summary.firstIn()).isEqualTo(in("08:00").at());
        assertThat(summary.lastOut()).isEqualTo(out("17:00").at());
    }

    @Test
    void theResultDoesNotDependOnArrivalOrder() {
        List<Punch> inOrder = List.of(in("08:00"), out("12:00"), in("13:00"), out("17:00"));
        List<Punch> scrambled = List.of(out("17:00"), in("13:00"), in("08:00"), out("12:00"));

        assertThat(DailySummaryCalculator.summarize(scrambled)).isEqualTo(DailySummaryCalculator.summarize(inOrder));
    }

    @Test
    void badgingInTwiceCountsFromTheFirst() {
        Summary summary = DailySummaryCalculator.summarize(List.of(in("08:00"), in("08:05"), out("17:00")));

        assertThat(summary.workedMinutes()).isEqualTo(540);
        assertThat(summary.punchCount()).isEqualTo(3);
    }

    @Test
    void anInWithNoOutCountsNoTime() {
        Summary summary = DailySummaryCalculator.summarize(List.of(in("08:00")));

        assertThat(summary).isEqualTo(new Summary(in("08:00").at(), null, 0, 1));
    }

    @Test
    void anOutWithNoInCountsNoTime() {
        Summary summary = DailySummaryCalculator.summarize(List.of(out("17:00")));

        assertThat(summary).isEqualTo(new Summary(null, out("17:00").at(), 0, 1));
    }
}
