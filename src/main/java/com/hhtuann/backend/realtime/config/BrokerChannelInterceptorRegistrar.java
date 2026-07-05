package com.hhtuann.backend.realtime.config;

import com.hhtuann.backend.realtime.sync.ServerTimeSyncBrokerInterceptor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * B1R4-B2F8 — registers {@link ServerTimeSyncBrokerInterceptor} on the {@code brokerChannel}.
 *
 * <p>{@code WebSocketMessageBrokerConfigurer} exposes hooks for the client inbound/outbound channels but NOT
 * for the {@code brokerChannel} (no {@code configureBrokerChannel}). The broker channel is an
 * {@code ExecutorSubscribableChannel}, so an {@code ExecutorChannelInterceptor} added via
 * {@code addInterceptor} receives {@code afterMessageHandled}. This bean injects the {@code brokerChannel} by
 * qualifier and adds the interceptor in {@link #register()} (after the channel bean is created, before any
 * message flows). Implemented as a plain {@code @Component} with {@code @PostConstruct} (NOT a
 * {@code BeanPostProcessor}) so it does not disturb the test probe registrars' lifecycle.
 */
@Component
public class BrokerChannelInterceptorRegistrar {

    private final AbstractSubscribableChannel brokerChannel;
    private final ServerTimeSyncBrokerInterceptor interceptor;

    public BrokerChannelInterceptorRegistrar(@Qualifier("brokerChannel") AbstractSubscribableChannel brokerChannel,
                                             ServerTimeSyncBrokerInterceptor interceptor) {
        this.brokerChannel = brokerChannel;
        this.interceptor = interceptor;
    }

    @PostConstruct
    void register() {
        brokerChannel.addInterceptor(interceptor);
    }
}
