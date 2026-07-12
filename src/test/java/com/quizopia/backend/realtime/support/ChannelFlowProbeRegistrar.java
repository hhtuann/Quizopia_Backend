package com.quizopia.backend.realtime.support;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.messaging.support.AbstractSubscribableChannel;

/**
 * B2F8 — registers {@link BrokerSubscribeHoldProbe} on the {@code brokerChannel} so the deterministic
 * registration-race test can hold the simple broker just before it registers a personal-queue subscription.
 * Registered as a {@code @Bean} in {@code OutboundProbeConfig} (test BeanPostProcessor). Pass-through unless
 * the probe is armed; production interceptor registration uses a separate @PostConstruct bean
 * ({@code BrokerChannelInterceptorRegistrar}) so the two lifecycles do not disturb each other.
 */
public class ChannelFlowProbeRegistrar implements BeanPostProcessor {

    private final BrokerSubscribeHoldProbe brokerHoldProbe = new BrokerSubscribeHoldProbe();

    public BrokerSubscribeHoldProbe brokerHoldProbe() {
        return brokerHoldProbe;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if ("brokerChannel".equals(beanName) && bean instanceof AbstractSubscribableChannel channel) {
            channel.addInterceptor(brokerHoldProbe);
        }
        return bean;
    }
}
