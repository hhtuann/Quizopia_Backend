package com.hhtuann.backend.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides the application {@link Clock}.
 *
 * <p>Production uses {@link Clock#systemUTC()}. Injecting a single {@link Clock}
 * bean lets every Batch-1 component read time consistently, and lets tests fix
 * the clock via {@link Clock#fixed(java.time.Instant, java.time.ZoneId)} to make
 * time-based behaviour deterministic.
 */
@Configuration
public class ClockConfig {

    /**
     * @return the production UTC clock
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
