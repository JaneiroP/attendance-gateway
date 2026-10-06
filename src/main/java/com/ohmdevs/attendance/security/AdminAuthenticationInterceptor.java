package com.ohmdevs.attendance.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/** Protects the administration API with a single shared key from configuration. */
@Component
public class AdminAuthenticationInterceptor implements HandlerInterceptor {

    public static final String ADMIN_KEY_HEADER = "X-Admin-Key";

    private static final Logger log = LoggerFactory.getLogger(AdminAuthenticationInterceptor.class);

    private final byte[] adminKey;

    AdminAuthenticationInterceptor(@Value("${attendance.admin.api-key:}") String adminKey) {
        this.adminKey = adminKey.getBytes(StandardCharsets.UTF_8);
        if (adminKey.isBlank()) {
            log.warn("attendance.admin.api-key is not set: every administration request will be rejected");
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String presented = request.getHeader(ADMIN_KEY_HEADER);
        if (adminKey.length == 0 || presented == null
                || !MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), adminKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid administration key");
        }
        return true;
    }
}
