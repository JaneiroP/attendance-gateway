package com.ohmdevs.attendance.ingest;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ohmdevs.attendance.ingest.PunchIngestService.ReprocessResult;
import com.ohmdevs.attendance.ingest.PunchStore.QuarantineEntry;

/** Administration API for punches that were set aside. */
@RestController
@RequestMapping("/api/v1/quarantine")
class QuarantineController {

    private final PunchStore store;
    private final PunchIngestService service;

    QuarantineController(PunchStore store, PunchIngestService service) {
        this.store = store;
        this.service = service;
    }

    @GetMapping
    List<QuarantineEntry> list(
            @RequestParam(defaultValue = "PENDING") @Pattern(regexp = "PENDING|RESOLVED") String status,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit) {
        return store.listQuarantine(status, limit);
    }

    @PostMapping("/reprocess")
    ReprocessResult reprocess() {
        return service.reprocess();
    }
}
