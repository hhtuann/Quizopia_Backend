package com.quizopia.backend.media.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Fails application startup fast when LiveKit is {@code enabled} but
 * credentials are missing (ADR-08 "fail-fast when enabled"). Runs after
 * context refresh begins but only throws when the feature is on — test
 * contexts with media disabled boot unchanged.
 */
@Component
public class LiveKitStartupValidator implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LiveKitStartupValidator.class);

    private final LiveKitProperties properties;
    private boolean running;

    public LiveKitStartupValidator(LiveKitProperties properties) {
        this.properties = properties;
    }

    @Override
    public void start() {
        if (properties.isEnabled()) {
            if (isBlank(properties.getApiKey()) || isBlank(properties.getApiSecret())) {
                throw new IllegalStateException(
                        "quizopia.media.livekit.enabled=true requires QUIZOPIA_LIVEKIT_API_KEY and "
                                + "QUIZOPIA_LIVEKIT_API_SECRET (no defaults by design)");
            }
            log.info("LiveKit media infrastructure enabled ({} -> {})",
                    properties.getHttpUrl(), properties.getWsUrl());
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
