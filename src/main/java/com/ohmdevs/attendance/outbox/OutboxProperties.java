package com.ohmdevs.attendance.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("attendance.outbox")
public record OutboxProperties(Duration pollInterval, int batchSize, Duration sendTimeout) {
}
