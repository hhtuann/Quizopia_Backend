package com.hhtuann.backend.security.web;

import com.hhtuann.backend.security.config.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * CORS configuration source and servlet-filter registration housekeeping.
 *
 * <p>
 * Credentials are enabled (the refresh cookie must be sent cross-origin from
 * the web app), so a wildcard origin is rejected outright: {@code *} combined
 * with {@code allowCredentials=true} is invalid and a security
 * misconfiguration.
 * The {@link OriginCheckFilter} registration is disabled at the servlet level
 * so
 * the filter runs only within the Spring Security chain (added via
 * {@code addFilterBefore} in the security configuration), avoiding a double
 * invocation.
 */
@Configuration
public class CorsConfig {

    private static final List<String> ALLOWED_HEADERS = List.of("Authorization", "Content-Type", "Accept", "Origin");
    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final long MAX_AGE_SECONDS = 3600L;

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(normalizedOrigins(properties));
        config.setAllowedHeaders(ALLOWED_HEADERS);
        config.setAllowedMethods(ALLOWED_METHODS);
        config.setAllowCredentials(true);
        config.setExposedHeaders(List.of("Authorization"));
        config.setMaxAge(MAX_AGE_SECONDS);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * Prevents Spring Boot from auto-registering {@link OriginCheckFilter} as a
     * plain servlet filter; it is instead wired into the Spring Security chain.
     */
    @Bean
    FilterRegistrationBean<OriginCheckFilter> originFilterRegistration(OriginCheckFilter filter) {
        FilterRegistrationBean<OriginCheckFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Returns the configured origins, trimmed and de-blanked. Fails fast when a
     * wildcard is present (incompatible with {@code allowCredentials=true}) or
     * when the list is empty. Exposed as package-private static so the
     * validation can be unit-tested without the Spring context.
     */
    @SuppressWarnings("null")
    static List<String> normalizedOrigins(SecurityProperties properties) {
        List<String> origins = properties.getCors().getAllowedOrigins().stream()
                .filter(o -> o != null)
                .map(String::trim)
                .filter(o -> !o.isEmpty())
                .toList();
        if (origins.contains("*")) {
            throw new IllegalStateException(
                    "Wildcard CORS origin '*' is not allowed because allowCredentials is true");
        }
        if (origins.isEmpty()) {
            throw new IllegalStateException(
                    "quizopia.security.cors.allowed-origins must contain at least one origin");
        }
        return origins;
    }
}
