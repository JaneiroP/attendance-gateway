package com.ohmdevs.attendance.ingest;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("attendance.ingest")
public record IngestProperties(Duration futureTolerance, int maxBatchSize) {
}
