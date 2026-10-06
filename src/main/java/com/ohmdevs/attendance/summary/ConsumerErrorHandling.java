package com.ohmdevs.attendance.summary;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import com.ohmdevs.attendance.events.Topics;
import com.ohmdevs.attendance.summary.PunchRecordedListener.UnreadableEventException;

@Configuration
class ConsumerErrorHandling {

    /**
     * An unreadable record goes to the dead-letter topic at once: trying again
     * cannot fix its content, and the records behind it should not wait.
     *
     * <p>Any other failure is most likely the database being unavailable, so
     * the batch is retried with growing pauses for about a minute before its
     * records are given up to the dead-letter topic.
     */
    @Bean
    CommonErrorHandler punchRecordedErrorHandler(KafkaOperations<?, ?> kafka) {
        // A negative partition leaves the choice to the producer; the dead-letter
        // topic does not mirror the partitions of the source topic.
        DeadLetterPublishingRecoverer toDeadLetterTopic = new DeadLetterPublishingRecoverer(kafka,
                (record, failure) -> new TopicPartition(Topics.PUNCH_RECORDED_DLQ, -1));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(6);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(30_000L);
        DefaultErrorHandler handler = new DefaultErrorHandler(toDeadLetterTopic, backOff);
        handler.addNotRetryableExceptions(UnreadableEventException.class);
        return handler;
    }
}
