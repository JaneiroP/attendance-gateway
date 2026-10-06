package com.ohmdevs.attendance.summary;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.ohmdevs.attendance.summary.DailySummaryProjector.DailySummary;

/** Administration API to read the summaries built from events. */
@RestController
@RequestMapping("/api/v1/daily-summaries")
class DailySummaryController {

    private static final int MAX_RANGE_DAYS = 366;

    private final DailySummaryProjector projector;

    DailySummaryController(DailySummaryProjector projector) {
        this.projector = projector;
    }

    @GetMapping
    List<DailySummary> find(
            @RequestParam String employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from) || from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'to' must not be before 'from', and the range may span at most " + MAX_RANGE_DAYS + " days");
        }
        return projector.find(employeeId, from, to);
    }
}
