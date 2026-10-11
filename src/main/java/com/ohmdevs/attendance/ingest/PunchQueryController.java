package com.ohmdevs.attendance.ingest;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.ohmdevs.attendance.ingest.PunchStore.PunchView;

/** Administration API to read the punches that were accepted. */
@RestController
@RequestMapping("/api/v1/punches")
class PunchQueryController {

    private final PunchStore store;

    PunchQueryController(PunchStore store) {
        this.store = store;
    }

    /**
     * One employee's punches with {@code from <= punchedAtUtc < to}, oldest
     * first. When {@code nextCursor} is not null there is more: repeat the
     * request with it, and with the same employee and range.
     */
    @GetMapping
    PunchPage find(
            @RequestParam String employeeId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
            @RequestParam(required = false) String cursor) {
        if (!from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'from' must be before 'to'");
        }
        // One more than asked for, which says whether there is a next page without a second query.
        List<PunchView> found = store.findPunches(employeeId, from, to, decode(cursor), limit + 1);
        if (found.size() <= limit) {
            return new PunchPage(found, null);
        }
        List<PunchView> page = found.subList(0, limit);
        PunchView last = page.getLast();
        return new PunchPage(page, new PunchCursor(last.punchedAtUtc(), last.id()).encode());
    }

    private static PunchCursor decode(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            return PunchCursor.decode(cursor);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'cursor' is not one returned by this API");
        }
    }

    record PunchPage(List<PunchView> punches, String nextCursor) {
    }
}
