package com.ohmdevs.attendance.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.ohmdevs.attendance.AbstractIntegrationTest;

/**
 * The reason the idempotency key is a database constraint and not a check in
 * code: several copies of a batch arriving at the same moment.
 */
class ConcurrentIngestTest extends AbstractIntegrationTest {

    private static final int SENDERS = 8;
    private static final int BATCH_SIZE = 200;

    @Test
    void copiesOfABatchSentAtOnceStoreEachPunchExactlyOnce() throws Exception {
        TestDevice device = registerDevice("America/Santo_Domingo");
        List<PunchDto> batch = new ArrayList<>();
        LocalDateTime start = LocalDateTime.parse("2026-08-01T06:00:00");
        for (int i = 0; i < BATCH_SIZE; i++) {
            String userCode = String.valueOf(1000 + i % 50);
            if (i < 50) {
                mapEmployee(device, userCode, "EMP-" + userCode);
            }
            batch.add(new PunchDto(userCode, start.plusMinutes(i).toString(), i % 2 == 0 ? "IN" : "OUT"));
        }

        // Every sender shuffles its copy. Without a fixed insert order inside the
        // service, overlapping batches in different orders deadlock each other.
        CyclicBarrier allReady = new CyclicBarrier(SENDERS);
        List<Callable<ResponseEntity<IngestResultDto>>> senders = new ArrayList<>();
        for (int sender = 0; sender < SENDERS; sender++) {
            List<PunchDto> copy = new ArrayList<>(batch);
            Collections.shuffle(copy, new Random(sender));
            senders.add(() -> {
                allReady.await(30, TimeUnit.SECONDS);
                return ingest(device, copy);
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(SENDERS);
        int acceptedAcrossSenders = 0;
        try {
            for (Future<ResponseEntity<IngestResultDto>> future : pool.invokeAll(senders, 60, TimeUnit.SECONDS)) {
                ResponseEntity<IngestResultDto> response = future.get();
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
                IngestResultDto result = response.getBody();
                assertThat(result.accepted() + result.duplicates()).isEqualTo(BATCH_SIZE);
                acceptedAcrossSenders += result.accepted();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(storedPunches(device)).isEqualTo(BATCH_SIZE);
        assertThat(acceptedAcrossSenders).isEqualTo(BATCH_SIZE);
    }
}
