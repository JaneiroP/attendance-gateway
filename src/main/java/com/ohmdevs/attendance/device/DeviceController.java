package com.ohmdevs.attendance.device;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ohmdevs.attendance.device.DeviceService.RegisteredDevice;

/** Administration API for the device registry. */
@RestController
@RequestMapping("/api/v1/devices")
class DeviceController {

    private final DeviceService service;

    DeviceController(DeviceService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    DeviceWithKeyResponse register(@Valid @RequestBody RegisterDeviceRequest request) {
        return DeviceWithKeyResponse.of(service.register(request.name(), request.siteCode(), request.timeZone()));
    }

    @GetMapping
    List<DeviceResponse> list() {
        return service.list().stream().map(DeviceResponse::of).toList();
    }

    @GetMapping("/{id}")
    DeviceResponse get(@PathVariable UUID id) {
        return DeviceResponse.of(service.require(id));
    }

    @PostMapping("/{id}/rotate-key")
    DeviceWithKeyResponse rotateKey(@PathVariable UUID id) {
        return DeviceWithKeyResponse.of(service.rotateKey(id));
    }

    @PostMapping("/{id}/disable")
    DeviceResponse disable(@PathVariable UUID id) {
        return DeviceResponse.of(service.changeStatus(id, DeviceStatus.DISABLED));
    }

    @PostMapping("/{id}/enable")
    DeviceResponse enable(@PathVariable UUID id) {
        return DeviceResponse.of(service.changeStatus(id, DeviceStatus.ACTIVE));
    }

    record RegisterDeviceRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 40) String siteCode,
            @NotBlank @Size(max = 60) String timeZone) {
    }

    record DeviceResponse(UUID id, String name, String siteCode, String timeZone, DeviceStatus status,
                          Instant createdAt, Instant lastSeenAt) {

        static DeviceResponse of(Device device) {
            return new DeviceResponse(device.getId(), device.getName(), device.getSiteCode(), device.getTimeZone(),
                    device.getStatus(), device.getCreatedAt(), device.getLastSeenAt());
        }
    }

    /** The only responses that carry an API key: registration and key rotation. */
    record DeviceWithKeyResponse(DeviceResponse device, String apiKey) {

        static DeviceWithKeyResponse of(RegisteredDevice registered) {
            return new DeviceWithKeyResponse(DeviceResponse.of(registered.device()), registered.apiKey());
        }
    }
}
