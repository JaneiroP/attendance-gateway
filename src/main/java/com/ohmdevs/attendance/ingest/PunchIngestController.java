package com.ohmdevs.attendance.ingest;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ohmdevs.attendance.device.AuthenticatedDevice;
import com.ohmdevs.attendance.ingest.PunchIngestService.IngestResult;
import com.ohmdevs.attendance.ingest.PunchIngestService.RawPunch;
import com.ohmdevs.attendance.security.DeviceAuthenticationInterceptor;

/** The endpoint time clocks call. Authenticated per device by {@link DeviceAuthenticationInterceptor}. */
@RestController
class PunchIngestController {

    private final PunchIngestService service;

    PunchIngestController(PunchIngestService service) {
        this.service = service;
    }

    /**
     * Answers 202: the punches are stored, and what downstream systems do with
     * them happens later and is not this caller's concern.
     */
    @PostMapping("/api/v1/devices/{deviceId}/punches")
    @ResponseStatus(HttpStatus.ACCEPTED)
    IngestResult ingest(
            @RequestAttribute(DeviceAuthenticationInterceptor.AUTHENTICATED_DEVICE) AuthenticatedDevice device,
            @Valid @RequestBody PunchBatchRequest request) {
        List<RawPunch> punches = request.punches().stream()
                .map(punch -> new RawPunch(punch.userCode(), punch.punchedAt(), punch.type()))
                .toList();
        return service.ingest(device, punches);
    }

    record PunchBatchRequest(@NotEmpty List<@Valid PunchRequest> punches) {
    }

    /**
     * @param punchedAt wall-clock time on the device, without offset; the
     *                  device's registered time zone gives it meaning
     */
    record PunchRequest(
            @NotBlank @Size(max = 40) String userCode,
            @NotNull LocalDateTime punchedAt,
            @NotNull PunchType type) {
    }
}
