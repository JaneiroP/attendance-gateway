package com.ohmdevs.attendance.device;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeviceService {

    private final DeviceRepository devices;
    private final Clock clock;

    DeviceService(DeviceRepository devices, Clock clock) {
        this.devices = devices;
        this.clock = clock;
    }

    /** Registers a device and returns it together with its API key, which cannot be recovered later. */
    @Transactional
    public RegisteredDevice register(String name, String siteCode, String timeZone) {
        String zone = validZone(timeZone);
        String key = DeviceKeys.generate();
        Device device = new Device(UUID.randomUUID(), name, siteCode, zone, DeviceKeys.hash(key), clock.instant());
        return new RegisteredDevice(devices.save(device), key);
    }

    @Transactional
    public RegisteredDevice rotateKey(UUID id) {
        Device device = require(id);
        String key = DeviceKeys.generate();
        device.replaceKeyHash(DeviceKeys.hash(key));
        return new RegisteredDevice(device, key);
    }

    @Transactional
    public Device changeStatus(UUID id, DeviceStatus status) {
        Device device = require(id);
        device.changeStatus(status);
        return device;
    }

    @Transactional(readOnly = true)
    public List<Device> list() {
        return devices.findAll();
    }

    @Transactional(readOnly = true)
    public Device require(UUID id) {
        return devices.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
    }

    /**
     * Verifies a device key. An unknown device and a wrong key get the same
     * answer, so the endpoint cannot be used to discover which ids exist.
     */
    @Transactional(readOnly = true)
    public AuthenticatedDevice authenticate(UUID id, String presentedKey) {
        Device device = devices.findById(id)
                .filter(found -> DeviceKeys.matches(presentedKey, found.getKeyHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid device credentials"));
        if (device.getStatus() != DeviceStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Device is disabled");
        }
        return new AuthenticatedDevice(device.getId(), device.getSiteCode(), ZoneId.of(device.getTimeZone()));
    }

    private static String validZone(String timeZone) {
        try {
            return ZoneId.of(timeZone).getId();
        } catch (DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown time zone: " + timeZone);
        }
    }

    public record RegisteredDevice(Device device, String apiKey) {
    }
}
