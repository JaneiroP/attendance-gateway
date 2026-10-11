package com.ohmdevs.attendance.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.ohmdevs.attendance.AbstractIntegrationTest;

class PunchQueryApiTest extends AbstractIntegrationTest {

    private static final String DAY_START = "2026-09-20T00:00:00Z";
    private static final String DAY_END = "2026-09-21T00:00:00Z";

    public record PunchViewDto(UUID id, UUID deviceId, String siteCode, String userCode, String employeeId,
                               String punchedAtLocal, String punchedAtUtc, String type, String receivedAt) {
    }

    public record PunchPageDto(List<PunchViewDto> punches, String nextCursor) {
    }

    @Test
    void pagesCoverEveryPunchOnceInTimeOrder() {
        TestDevice device = registerDevice("UTC");
        String employee = newEmployeeId();
        mapEmployee(device, "17", employee);
        List<PunchDto> batch = new ArrayList<>();
        LocalDateTime start = LocalDateTime.parse("2026-09-20T06:00:00");
        for (int i = 0; i < 25; i++) {
            batch.add(new PunchDto("17", start.plusMinutes(i * 20L).toString(), i % 2 == 0 ? "IN" : "OUT"));
        }
        ingest(device, batch);

        List<PunchViewDto> all = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String cursor = null;
        do {
            PunchPageDto page = find(employee, DAY_START, DAY_END, 10, cursor).getBody();
            pageSizes.add(page.punches().size());
            all.addAll(page.punches());
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(pageSizes).containsExactly(10, 10, 5);
        assertThat(all).extracting(PunchViewDto::id).doesNotHaveDuplicates();
        assertThat(all).extracting(punch -> Instant.parse(punch.punchedAtUtc()))
                .isSortedAccordingTo(Comparator.naturalOrder());
        assertThat(all.getFirst().punchedAtLocal()).startsWith("2026-09-20T06:00");
        assertThat(all.getFirst().employeeId()).isEqualTo(employee);
        assertThat(all.getFirst().deviceId()).isEqualTo(device.id());
    }

    @Test
    void aPageThatEndsExactlyAtTheLastPunchHasNoNextCursor() {
        TestDevice device = registerDevice("UTC");
        String employee = newEmployeeId();
        mapEmployee(device, "17", employee);
        ingest(device, List.of(
                new PunchDto("17", "2026-09-20T08:00:00", "IN"),
                new PunchDto("17", "2026-09-20T17:00:00", "OUT")));

        PunchPageDto page = find(employee, DAY_START, DAY_END, 2, null).getBody();

        assertThat(page.punches()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void punchesOfTheSameInstantAreNotLostAtAPageBoundary() {
        TestDevice device = registerDevice("UTC");
        String employee = newEmployeeId();
        mapEmployee(device, "17", employee);
        // Same person, same second, different type: two punches with one timestamp.
        ingest(device, List.of(
                new PunchDto("17", "2026-09-20T12:00:00", "IN"),
                new PunchDto("17", "2026-09-20T12:00:00", "OUT"),
                new PunchDto("17", "2026-09-20T13:00:00", "IN")));

        PunchPageDto first = find(employee, DAY_START, DAY_END, 1, null).getBody();
        PunchPageDto second = find(employee, DAY_START, DAY_END, 1, first.nextCursor()).getBody();
        PunchPageDto third = find(employee, DAY_START, DAY_END, 1, second.nextCursor()).getBody();

        assertThat(List.of(first.punches().getFirst().type(), second.punches().getFirst().type()))
                .containsExactlyInAnyOrder("IN", "OUT");
        assertThat(third.punches().getFirst().punchedAtLocal()).startsWith("2026-09-20T13:00");
        assertThat(third.nextCursor()).isNull();
    }

    @Test
    void theRangeIncludesItsStartAndExcludesItsEndAndOtherEmployees() {
        TestDevice device = registerDevice("UTC");
        String employee = newEmployeeId();
        mapEmployee(device, "17", employee);
        mapEmployee(device, "18", newEmployeeId());
        ingest(device, List.of(
                new PunchDto("17", "2026-09-19T23:59:59", "OUT"),
                new PunchDto("17", "2026-09-20T00:00:00", "IN"),
                new PunchDto("18", "2026-09-20T09:00:00", "IN"),
                new PunchDto("17", "2026-09-21T00:00:00", "OUT")));

        PunchPageDto page = find(employee, DAY_START, DAY_END, 100, null).getBody();

        assertThat(page.punches()).extracting(PunchViewDto::punchedAtLocal).hasSize(1)
                .allSatisfy(time -> assertThat(time).startsWith("2026-09-20T00:00"));
    }

    @Test
    void badRequestsAreRejected() {
        String employee = newEmployeeId();

        assertThat(find(employee, DAY_END, DAY_START, 10, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(find(employee, DAY_START, DAY_END, 0, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(find(employee, DAY_START, DAY_END, 501, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(find(employee, DAY_START, DAY_END, 10, "not-a-cursor").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> anonymous = rest.exchange(
                "/api/v1/punches?employeeId={e}&from={f}&to={t}", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class, employee, DAY_START, DAY_END);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aCursorSurvivesEncodingAndGarbageIsRefused() {
        PunchCursor cursor = new PunchCursor(Instant.parse("2026-09-20T12:00:00.123456Z"), UUID.randomUUID());

        assertThat(PunchCursor.decode(cursor.encode())).isEqualTo(cursor);
        for (String garbage : List.of("", "%%%", "bm90LWEtY3Vyc29y", "MjAyNi0wOS0yMFQxMjowMDowMFp8eA")) {
            assertThatIllegalArgumentException().as(garbage).isThrownBy(() -> PunchCursor.decode(garbage));
        }
    }

    private ResponseEntity<PunchPageDto> find(String employeeId, String from, String to, int limit, String cursor) {
        String url = "/api/v1/punches?employeeId={e}&from={f}&to={t}&limit={l}" + (cursor == null ? "" : "&cursor={c}");
        Object[] variables = cursor == null
                ? new Object[] {employeeId, from, to, limit}
                : new Object[] {employeeId, from, to, limit, cursor};
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(adminHeaders()), PunchPageDto.class, variables);
    }
}
