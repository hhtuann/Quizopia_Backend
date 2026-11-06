package com.quizopia.backend.account.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Password-reset policy (ADR-004 §"Reset persistence, intake and
 * delivery"). Bound from {@code quizopia.password-reset.*}. Values are
 * policy, not secrets; the HMAC key is derived at runtime from the data
 * encryption key and never configured here.
 */
@Validated
@ConfigurationProperties(prefix = "quizopia.password-reset")
public class PasswordResetProperties {

    /** Challenge validity window (15 minutes per ADR-004). */
    @NotNull
    private Duration ttl = Duration.ofMinutes(15);

    /**
     * Trusted absolute frontend origin the reset link points at, e.g.
     * {@code https://app.quizopia.example}. The fragment
     * {@code #token=<opaque>} is appended by the service; the value is
     * validated at startup (HTTPS except non-production loopback HTTP, no
     * userinfo/query/fragment) and never derived from request headers.
     */
    @NotNull
    private String frontendBaseUri = "http://localhost:3000";

    /** Rolling window of the per-email abuse bucket. */
    @NotNull
    private Duration emailWindow = Duration.ofHours(1);

    /** Max admitted requests per email within {@link #emailWindow}. */
    @Min(1)
    private int maxPerEmail = 5;

    /** Minimum delay between two admitted requests for the same email. */
    @NotNull
    private Duration emailCooldown = Duration.ofSeconds(60);

    /** Rolling window of the per-IP abuse bucket. */
    @NotNull
    private Duration ipWindow = Duration.ofHours(1);

    /** Max admitted requests per IP within {@link #ipWindow}. */
    @Min(1)
    private int maxPerIp = 20;

    /** Capacity of the bounded intake worker queue (2 workers). */
    @Min(1)
    private int intakeCapacity = 200;

    /** Terminal challenges older than this are deleted by the sweeper. */
    @NotNull
    private Duration challengeRetention = Duration.ofDays(7);

    /** Abuse buckets are deleted this long after their window expiry. */
    @NotNull
    private Duration bucketRetention = Duration.ofHours(2);

    /** Background cleanup sweeper settings (disabled in tests). */
    @NotNull
    private Cleanup cleanup = new Cleanup();

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public String getFrontendBaseUri() {
        return frontendBaseUri;
    }

    public void setFrontendBaseUri(String frontendBaseUri) {
        this.frontendBaseUri = frontendBaseUri;
    }

    public Duration getEmailWindow() {
        return emailWindow;
    }

    public void setEmailWindow(Duration emailWindow) {
        this.emailWindow = emailWindow;
    }

    public int getMaxPerEmail() {
        return maxPerEmail;
    }

    public void setMaxPerEmail(int maxPerEmail) {
        this.maxPerEmail = maxPerEmail;
    }

    public Duration getEmailCooldown() {
        return emailCooldown;
    }

    public void setEmailCooldown(Duration emailCooldown) {
        this.emailCooldown = emailCooldown;
    }

    public Duration getIpWindow() {
        return ipWindow;
    }

    public void setIpWindow(Duration ipWindow) {
        this.ipWindow = ipWindow;
    }

    public int getMaxPerIp() {
        return maxPerIp;
    }

    public void setMaxPerIp(int maxPerIp) {
        this.maxPerIp = maxPerIp;
    }

    public int getIntakeCapacity() {
        return intakeCapacity;
    }

    public void setIntakeCapacity(int intakeCapacity) {
        this.intakeCapacity = intakeCapacity;
    }

    public Duration getChallengeRetention() {
        return challengeRetention;
    }

    public void setChallengeRetention(Duration challengeRetention) {
        this.challengeRetention = challengeRetention;
    }

    public Duration getBucketRetention() {
        return bucketRetention;
    }

    public void setBucketRetention(Duration bucketRetention) {
        this.bucketRetention = bucketRetention;
    }

    public Cleanup getCleanup() {
        return cleanup;
    }

    public void setCleanup(Cleanup cleanup) {
        this.cleanup = cleanup;
    }

    /** Cleanup sweeper settings; mirrors the OTP cleanup pattern. */
    public static class Cleanup {

        private boolean enabled = true;

        /** Fixed delay of the sweep. */
        @NotNull
        private Duration interval = Duration.ofHours(1);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }
    }
}
