package com.ohmdevs.attendance.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.DockerClientFactory;

import com.ohmdevs.attendance.AbstractIntegrationTest;

/**
 * What the outbox is for: the broker goes away while clocks keep sending.
 *
 * <p>The broker container is frozen rather than stopped, so it keeps its
 * address and the application reconnects to it as it would after a real
 * network partition.
 */
@ExtendWith(OutputCaptureExtension.class)
class BrokerOutageTest extends AbstractIntegrationTest {

    private static final int PUNCHES = 40;

    @Test
    void punchesIngestedWhileTheBrokerIsDownAreDeliveredOnceWhenItReturns(CapturedOutput log) {
        TestDevice device = registerDevice("America/Santo_Domingo");
        String employee = newEmployeeId();
        mapEmployee(device, "17", employee);
        // Let anything left by earlier tests drain, so the counts below are this test's own.
        await().atMost(Duration.ofSeconds(30)).until(() -> unpublishedEvents() == 0);

        List<PunchDto> batch = new ArrayList<>();
        LocalDateTime start = LocalDateTime.parse("2026-09-10T06:00:00");
        for (int i = 0; i < PUNCHES; i++) {
            batch.add(new PunchDto("17", start.plusMinutes(i * 10L).toString(), i % 2 == 0 ? "IN" : "OUT"));
        }

        freezeBroker();
        try {
            ResponseEntity<IngestResultDto> response = ingest(device, batch);

            // The clock gets its answer and the punches are safe, broker or no broker.
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody().accepted()).isEqualTo(PUNCHES);
            assertThat(storedPunches(device)).isEqualTo(PUNCHES);

            // Long enough for the publisher to try and give up more than once.
            await().during(Duration.ofSeconds(8)).atMost(Duration.ofSeconds(12))
                    .until(() -> unpublishedEvents() == PUNCHES);
            assertThat(dailySummary(employee, "2026-09-10")).isNull();
            // Proof that the outage was real: the publisher did fail, it was not just slow.
            assertThat(log.getOut()).contains("Outbox publication failed");
        } finally {
            unfreezeBroker();
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(unpublishedEvents()).isZero();
            DailySummaryDto summary = dailySummary(employee, "2026-09-10");
            assertThat(summary).isNotNull();
            assertThat(summary.punchCount()).isEqualTo(PUNCHES);
        });

        // A failed attempt can leave copies in the producer that the broker
        // still receives once it is back. Give any such duplicates time to
        // arrive and check that none was counted.
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(10))
                .until(() -> dailySummary(employee, "2026-09-10").punchCount() == PUNCHES);
        // Twenty IN/OUT pairs, each ten minutes long.
        assertThat(dailySummary(employee, "2026-09-10").workedMinutes()).isEqualTo(PUNCHES / 2 * 10);
    }

    private static void freezeBroker() {
        DockerClientFactory.instance().client().pauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    private static void unfreezeBroker() {
        DockerClientFactory.instance().client().unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }
}
