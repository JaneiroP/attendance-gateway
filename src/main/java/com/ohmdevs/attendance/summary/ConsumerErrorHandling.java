package com.ohmdevs.attendance.summary;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import com.ohmdevs.attendance.events.Topics;
import com.ohmdevs.attendance.summary.PunchRecordedListener.UnreadableEventException;

@Configuration
class ConsumerErrorHandling {

    /**
     * A failing record is retried twice, one second apart, and then moved to
     * the dead-letter topic so the partition keeps flowing. An unreadable
     * record skips the retries: trying again cannot fix its content.
     */
    @Bean
    CommonErrorHandler punchRecordedErrorHandler(KafkaOperations<?, ?> kafka) {
        // A negative partition leaves the choice to the producer; the dead-letter
        // topic does not mirror the partitions of the source topic.
        DeadLetterPublishingRecoverer toDeadLetterTopic = new DeadLetterPublishingRecoverer(kafka,
                (record, failure) -> new TopicPartition(Topics.PUNCH_RECORDED_DLQ, -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(toDeadLetterTopic, new FixedBackOff(1000L, 2));
        handler.addNotRetryableExceptions(UnreadableEventException.class);
        return handler;
    }
}
