package com.ohmdevs.attendance.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Two kinds of caller, two kinds of credential: a clock may only post its own
 * punches, and everything else under the API is administration.
 */
@Configuration
class WebSecurityConfiguration implements WebMvcConfigurer {

    private static final String INGEST_PATH = "/api/v1/devices/{deviceId}/punches";

    private final DeviceAuthenticationInterceptor deviceAuthentication;
    private final AdminAuthenticationInterceptor adminAuthentication;

    WebSecurityConfiguration(DeviceAuthenticationInterceptor deviceAuthentication,
                             AdminAuthenticationInterceptor adminAuthentication) {
        this.deviceAuthentication = deviceAuthentication;
        this.adminAuthentication = adminAuthentication;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(deviceAuthentication).addPathPatterns(INGEST_PATH);
        registry.addInterceptor(adminAuthentication).addPathPatterns("/api/v1/**").excludePathPatterns(INGEST_PATH);
    }
}
