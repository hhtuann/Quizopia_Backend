package com.quizopia.backend.monitoring.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Activity-event ingest policy (P4-A2, issue #81). Bound from
 * {@code quizopia.monitoring.activity-ingest.*}. Values are policy, not
 * secrets; the bucket HMAC key is derived at runtime from the data
 * encryption key (see {@code ActivityIngestRateLimiter}).
 */
@Validated
@ConfigurationProperties(prefix = "quizopia.monitoring.activity-ingest")
public class ActivityIngestProperties {

    /** Max events per batch request. */
    @Min(1)
    private int maxBatchSize = 25;

    /** Max serialized metadata bytes per event. */
    @Min(64)
    private int maxMetadataBytes = 2048;

    /** Max ingest batches per user per window. */
    @Min(1)
    private int rateLimit = 30;

    @NotNull
    private Duration window = Duration.ofMinutes(1);

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    public int getMaxMetadataBytes() {
        return maxMetadataBytes;
    }

    public void setMaxMetadataBytes(int maxMetadataBytes) {
        this.maxMetadataBytes = maxMetadataBytes;
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
}
