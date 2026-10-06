package com.ohmdevs.attendance.device;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A time clock that is allowed to send punches. */
@Entity
@Table(name = "device")
public class Device {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "site_code", nullable = false, length = 40)
    private String siteCode;

    @Column(name = "time_zone", nullable = false, length = 60)
    private String timeZone;

    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DeviceStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    protected Device() {
    }

    Device(UUID id, String name, String siteCode, String timeZone, String keyHash, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.siteCode = siteCode;
        this.timeZone = timeZone;
        this.keyHash = keyHash;
        this.status = DeviceStatus.ACTIVE;
        this.createdAt = createdAt;
    }

    void replaceKeyHash(String newKeyHash) {
        this.keyHash = newKeyHash;
    }

    void changeStatus(DeviceStatus newStatus) {
        this.status = newStatus;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSiteCode() {
        return siteCode;
    }

    public String getTimeZone() {
        return timeZone;
    }

    String getKeyHash() {
        return keyHash;
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
