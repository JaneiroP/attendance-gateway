package com.ohmdevs.attendance.device;

import java.time.ZoneId;
import java.util.UUID;

/** What the ingest path needs to know about the device that sent a request. */
public record AuthenticatedDevice(UUID id, String siteCode, ZoneId zone) {
}
