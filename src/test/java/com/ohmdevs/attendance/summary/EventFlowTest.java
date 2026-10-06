package com.ohmdevs.attendance.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import com.ohmdevs.attendance.AbstractIntegrationTest;
import com.ohmdevs.attendance.events.PunchRecorded;
import com.ohmdevs.attendance.events.Topics;

/** Ingest to outbox to Kafka to consumer to summary, through the real broker. */
class EventFlowTest extends AbstractIntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private DailySummaryProjector projector;

    @Test
    void anIngestedDayBecomesADailySummary() {
        TestDevice device = registerDevice("America/Santo_Domingo");
        String employee = newEmployeeId();
        mapEmployee(device, "17", employee);

        ingest(device, List.of(
                new PunchDto("17", "2026-09-01T07:58:00", "IN"),
                new PunchDto("17", "2026-09-01T17:02:00", "OUT")));

        await().atMost(PATIENCE).untilAsserted(() -> {
            DailySummaryDto summary = dailySummary(employee, "2026-09-01");
            assertThat(summary).isNotNull();
            assertThat(summary.punchCount()).isEqualTo(2);
            assertThat(summary.workedMinutes()).isEqualTo(544);
            assertThat(Instant.parse(summary.firstIn())).isEqualTo(Instant.parse("2026-09-01T11:58:00Z"));
            assertThat(Instant.parse(summary.lastOut())).isEqualTo(Instant.parse("2026-09-01T21:02:00Z"));
        });
    }

    @Test
    void aRetriedBatchPublishesNoNewEvents() {
        TestDevice device = registerDevice("America/Santo_Domingo");
        String employee = newEmployeeId();
        mapEmployee(device, "17", employee);
        List<PunchDto> batch = List.of(
                new PunchDto("17", "2026-09-02T08:00:00", "IN"),
                new PunchDto("17", "2026-09-02T17:00:00", "OUT"));

        ingest(device, batch);
        ingest(device, batch);
        ingest(device, batch);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE message_key = ?",
                Integer.class, employee)).isEqualTo(2);
    }

    @Test
    void punchesThatArriveOutOfOrderGiveTheSameSummary() {
        TestDevice device = registerDevice("America/Santo_Domingo");
        String employee = newEmployeeId();
        mapEmployee(device, "17", employee);

        // The clock was offline in the morning and delivers the IN after the OUT.
        ingest(device, List.of(new PunchDto("17", "2026-09-03T17:00:00", "OUT")));
        await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(dailySummary(employee, "2026-09-03")).isNotNull());
        ingest(device, List.of(new PunchDto("17", "2026-09-03T08:00:00", "IN")));

        await().atMost(PATIENCE).untilAsserted(() -> {
            DailySummaryDto summary = dailySummary(employee, "2026-09-03");
            assertThat(summary.punchCount()).isEqualTo(2);
            assertThat(summary.workedMinutes()).isEqualTo(540);
        });
    }

    @Test
    void anEventDeliveredTwiceIsAppliedOnce() {
        String employee = newEmployeeId();
        PunchRecorded event = new PunchRecorded(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "HQ",
                employee, LocalDateTime.parse("2026-09-04T08:00:00"), Instant.parse("2026-09-04T12:00:00Z"),
                "IN", Instant.now());

        assertThat(projector.apply(event)).isTrue();
        assertThat(projector.apply(event)).isFalse();

        assertThat(dailySummary(employee, "2026-09-04").punchCount()).isEqualTo(1);
    }

    @Test
    void aDayWithOnlyAnOutHasNoFirstIn() {
        String employee = newEmployeeId();

        projector.apply(new PunchRecorded(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "HQ",
                employee, LocalDateTime.parse("2026-09-05T17:00:00"), Instant.parse("2026-09-05T21:00:00Z"),
                "OUT", Instant.now()));

        DailySummaryDto summary = dailySummary(employee, "2026-09-05");
        assertThat(summary.firstIn()).isNull();
        assertThat(summary.workedMinutes()).isZero();
    }

    @Test
    void anUnreadableMessageGoesToTheDeadLetterTopicAndDoesNotBlockTheNextOnes() {
        String poison = "not-an-event-" + UUID.randomUUID();
        kafka.send(Topics.PUNCH_RECORDED, "some-key", poison).join();

        try (KafkaConsumer<String, String> deadLetters = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            deadLetters.subscribe(List.of(Topics.PUNCH_RECORDED_DLQ));
            await().atMost(PATIENCE).until(() -> {
                for (ConsumerRecord<String, String> record : deadLetters.poll(Duration.ofMillis(500))) {
                    if (poison.equals(record.value())) {
                        return true;
                    }
                }
                return false;
            });
        }

        // The consumer is still alive behind the poison message.
        TestDevice device = registerDevice("UTC");
        String employee = newEmployeeId();
        mapEmployee(device, "17", employee);
        ingest(device, List.of(new PunchDto("17", "2026-09-06T08:00:00", "IN")));
        await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(dailySummary(employee, "2026-09-06")).isNotNull());
    }
}
