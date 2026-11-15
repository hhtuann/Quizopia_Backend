package com.quizopia.backend.monitoring.api;

import com.quizopia.backend.monitoring.application.AiIngestProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link AiWorkerIngestGuardFilter} on the worker ingest route so
 * the key check + body cap run BEFORE Spring MVC/Jackson touch the request
 * (servlet filters precede dispatcher processing). The controller keeps its
 * own key check as defense-in-depth.
 */
@Configuration
public class AiWorkerIngestGuardConfig {

    @Bean
    public FilterRegistrationBean<AiWorkerIngestGuardFilter> aiWorkerIngestGuard(
            AiIngestProperties properties) {
        FilterRegistrationBean<AiWorkerIngestGuardFilter> registration =
                new FilterRegistrationBean<>(new AiWorkerIngestGuardFilter(properties));
        registration.addUrlPatterns("/internal/ai/*");
        registration.setOrder(1); // ahead of business filters
        return registration;
    }
}
