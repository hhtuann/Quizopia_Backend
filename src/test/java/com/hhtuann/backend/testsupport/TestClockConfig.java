package com.hhtuann.backend.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Replaces the production {@link java.time.Clock} bean with a {@link MutableClock}
 * so tests that need to control time (lockout expiry) can advance it. Import only
 * in tests that need deterministic time.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(Instant.now(), ZoneOffset.UTC);
    }
}
