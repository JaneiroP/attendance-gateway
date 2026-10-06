package com.ohmdevs.attendance.ingest;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import com.ohmdevs.attendance.ingest.PunchIngestService.IngestResult;

/**
 * Counts what happened to incoming punches. A rising duplicate share means
 * clocks are retrying; a rising quarantine share means mappings or device
 * clocks need attention.
 */
@Component
class IngestMetrics {

    private final Counter accepted;
    private final Counter duplicates;
    private final Counter quarantined;

    IngestMetrics(MeterRegistry registry) {
        this.accepted = outcome(registry, "accepted");
        this.duplicates = outcome(registry, "duplicate");
        this.quarantined = outcome(registry, "quarantined");
    }

    void record(IngestResult result) {
        accepted.increment(result.accepted());
        duplicates.increment(result.duplicates());
        quarantined.increment(result.quarantined());
    }

    private static Counter outcome(MeterRegistry registry, String outcome) {
        return Counter.builder("attendance.punches")
                .description("Punches received from clocks, by what was done with them")
                .tag("outcome", outcome)
                .register(registry);
    }
}
