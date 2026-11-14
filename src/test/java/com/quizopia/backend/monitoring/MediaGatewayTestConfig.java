package com.quizopia.backend.monitoring;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Registers the in-memory {@link FakeMediaRoomGateway} as the primary
 * {@link com.quizopia.backend.media.roomservice.MediaRoomGateway} bean.
 * Shared (identical) by every P4-B camera test class so the Spring test
 * context — and its PostgreSQL container — is reused across them.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MediaGatewayTestConfig {

    @Bean
    @Primary
    FakeMediaRoomGateway mediaRoomGateway() {
        return new FakeMediaRoomGateway();
    }
}
