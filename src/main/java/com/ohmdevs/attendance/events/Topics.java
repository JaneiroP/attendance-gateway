package com.ohmdevs.attendance.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** The topics this service owns. The version is part of the name: a breaking change gets a new topic. */
@Configuration
public class Topics {

    public static final String PUNCH_RECORDED = "attendance.punch.recorded.v1";
    public static final String PUNCH_RECORDED_DLQ = "attendance.punch.recorded.v1.dlq";

    /**
     * Messages are keyed by employee, so all punches of one person land in one
     * partition and are consumed in order.
     */
    @Bean
    NewTopic punchRecordedTopic() {
        return TopicBuilder.name(PUNCH_RECORDED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic punchRecordedDlqTopic() {
        return TopicBuilder.name(PUNCH_RECORDED_DLQ).partitions(1).replicas(1).build();
    }
}
