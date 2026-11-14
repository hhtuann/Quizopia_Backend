package com.quizopia.backend.monitoring.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AI-detection ingest &amp; alert policy (P4-C, issues #88/#90). Bound
 * from {@code quizopia.monitoring.ai-ingest.*}. Thresholds are policy,
 * not secrets; the only secret-ish value is {@code workerKey} (the
 * internal worker endpoint's X-Api-Key — deny-all while unset, exactly
 * the LiveKit webhook posture).
 */
@Validated
@ConfigurationProperties(prefix = "quizopia.monitoring.ai-ingest")
public class AiIngestProperties {

    /** Max detection events per batch request (CONTRACT §3.1: ≤ 8). */
    @Min(1)
    private int maxBatchSize = 8;

    /** Max detected boxes stored per event payload (CONTRACT §3.1: ≤ 8). */
    @Min(1)
    private int maxDetectionsPerEvent = 8;

    /** Max serialized payload (detections array) bytes per event. */
    @Min(256)
    private int maxPayloadBytes = 2048;

    /** Max ingest batches per user per window (student path). */
    @Min(1)
    private int rateLimit = 6;

    @NotNull
    private Duration window = Duration.ofMinutes(1);

    /** Aggregation cooldown for PHONE/SCREEN/MULTIPLE_PERSONS (CONTRACT §5: 20s). */
    @NotNull
    private Duration alertCooldown = Duration.ofSeconds(20);

    /** Aggregation cooldown for PERSON_NOT_VISIBLE (CONTRACT §5: 45s). */
    @NotNull
    private Duration alertCooldownPersonNotVisible = Duration.ofSeconds(45);

    /**
     * X-Api-Key of the internal worker endpoint. Unset/blank ⇒ the
     * endpoint denies everything with 404 (fail closed; same posture as
     * the LiveKit webhook config).
     */
    private String workerKey = "";

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    public int getMaxDetectionsPerEvent() {
        return maxDetectionsPerEvent;
    }

    public void setMaxDetectionsPerEvent(int maxDetectionsPerEvent) {
        this.maxDetectionsPerEvent = maxDetectionsPerEvent;
    }

    public int getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public void setMaxPayloadBytes(int maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public int getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(int rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }

    public Duration getAlertCooldown() {
        return alertCooldown;
    }

    public void setAlertCooldown(Duration alertCooldown) {
        this.alertCooldown = alertCooldown;
    }

    public Duration getAlertCooldownPersonNotVisible() {
        return alertCooldownPersonNotVisible;
    }

    public void setAlertCooldownPersonNotVisible(Duration alertCooldownPersonNotVisible) {
        this.alertCooldownPersonNotVisible = alertCooldownPersonNotVisible;
    }

    public String getWorkerKey() {
        return workerKey;
    }

    public void setWorkerKey(String workerKey) {
        this.workerKey = workerKey;
    }
}
