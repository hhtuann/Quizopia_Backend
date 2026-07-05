package com.hhtuann.backend.realtime;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Registers the test-only observation probes (B1R4-A1 §4, B1R4-A2 §3):
 * <ul>
 *   <li>{@link OutboundMessageProbe} — a {@code ChannelInterceptor} on the {@code clientOutboundChannel}
 *       capturing every outbound STOMP message (MESSAGE/ERROR/etc.). Pure observer (preSend returns the
 *       message unchanged); does not alter production routing.</li>
 *   <li>{@link AcceptedSubscriptionProbe} — an {@code @EventListener} on {@code SessionSubscribeEvent},
 *       recording each SUBSCRIBE the inbound authorization interceptors accepted (authorization + inbound
 *       receipt). NOT broker-registration proof (B2F8 corrected the prior assumption).</li>
 * </ul>
 * {@code @TestConfiguration} beans are merged with the production {@code WebSocketMessageBrokerConfigurer}.
 * No production REST/debug endpoint is exposed — the probes are reachable only from tests that
 * {@code @Import} this configuration.
 */
@TestConfiguration
public class OutboundProbeConfig implements WebSocketMessageBrokerConfigurer {

    @Bean
    OutboundMessageProbe outboundMessageProbe() {
        return new OutboundMessageProbe();
    }

    @Bean
    AcceptedSubscriptionProbe acceptedSubscriptionProbe() {
        return new AcceptedSubscriptionProbe();
    }

    @Bean
    com.hhtuann.backend.realtime.support.RepositoryLockEntryProbe repositoryLockEntryProbe() {
        return new com.hhtuann.backend.realtime.support.RepositoryLockEntryProbe();
    }

    @Bean
    com.hhtuann.backend.realtime.support.ChannelFlowProbeRegistrar channelFlowProbeRegistrar() {
        return new com.hhtuann.backend.realtime.support.ChannelFlowProbeRegistrar();
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(outboundMessageProbe());
    }
}
