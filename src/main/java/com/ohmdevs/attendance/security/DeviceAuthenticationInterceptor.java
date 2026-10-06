package com.ohmdevs.attendance.security;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import com.ohmdevs.attendance.device.AuthenticatedDevice;
import com.ohmdevs.attendance.device.DeviceService;

/**
 * Authenticates a time clock by its own API key before the request body is
 * read, so an unauthenticated caller learns nothing from validation errors.
 */
@Component
public class DeviceAuthenticationInterceptor implements HandlerInterceptor {

    public static final String DEVICE_KEY_HEADER = "X-Device-Key";
    public static final String AUTHENTICATED_DEVICE = "attendance.authenticatedDevice";

    private final DeviceService devices;

    DeviceAuthenticationInterceptor(DeviceService devices) {
        this.devices = devices;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(AUTHENTICATED_DEVICE,
                devices.authenticate(deviceId(request), request.getHeader(DEVICE_KEY_HEADER)));
        return true;
    }

    @SuppressWarnings("unchecked")
    private static UUID deviceId(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        try {
            return UUID.fromString(((Map<String, String>) variables).get("deviceId"));
        } catch (RuntimeException e) {
            // A malformed id is answered like an unknown one.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid device credentials");
        }
    }
}
