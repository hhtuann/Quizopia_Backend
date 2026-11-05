package com.quizopia.backend.authentication.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Email-verification OTP policy (ADR-002 §2). Bound from
 * {@code quizopia.email-verification.*}. Values are policy, not secrets.
 */
@Validated
@ConfigurationProperties(prefix = "quizopia.email-verification")
public class EmailVerificationProperties {

    /** OTP validity window. Locked default 10 minutes. */
    @NotNull
    private Duration otpTtl = Duration.ofMinutes(10);

    /** Wrong OTP submissions allowed per challenge before it FAILs. */
    @Min(1)
    private int maxFailedAttempts = 5;

    /** Minimum delay between two challenges for the same account. */
    @NotNull
    private Duration resendCooldown = Duration.ofSeconds(60);

    /** Rolling window for the per-email challenge rate limit. */
    @NotNull
    private Duration emailWindow = Duration.ofHours(1);

    /** Max challenges per email within {@link #emailWindow}. */
    @Min(1)
    private int maxChallengesPerEmail = 5;

    /** Rolling window for the per-IP challenge rate limit. */
    @NotNull
    private Duration ipWindow = Duration.ofHours(1);

    /** Max challenges per IP within {@link #ipWindow}. */
    @Min(1)
    private int maxChallengesPerIp = 20;

    /** Background cleanup of terminal challenges. */
    @NotNull
    private Cleanup cleanup = new Cleanup();

    public Duration getOtpTtl() {
        return otpTtl;
    }

    public void setOtpTtl(Duration otpTtl) {
        this.otpTtl = otpTtl;
    }

    public int getMaxFailedAttempts() {
        return maxFailedAttempts;
    }

    public void setMaxFailedAttempts(int maxFailedAttempts) {
        this.maxFailedAttempts = maxFailedAttempts;
    }

    public Duration getResendCooldown() {
        return resendCooldown;
    }

    public void setResendCooldown(Duration resendCooldown) {
        this.resendCooldown = resendCooldown;
    }

    public Duration getEmailWindow() {
        return emailWindow;
    }

    public void setEmailWindow(Duration emailWindow) {
        this.emailWindow = emailWindow;
    }

    public int getMaxChallengesPerEmail() {
        return maxChallengesPerEmail;
    }

    public void setMaxChallengesPerEmail(int maxChallengesPerEmail) {
        this.maxChallengesPerEmail = maxChallengesPerEmail;
    }

    public Duration getIpWindow() {
        return ipWindow;
    }

    public void setIpWindow(Duration ipWindow) {
        this.ipWindow = ipWindow;
    }

    public int getMaxChallengesPerIp() {
        return maxChallengesPerIp;
    }

    public void setMaxChallengesPerIp(int maxChallengesPerIp) {
        this.maxChallengesPerIp = maxChallengesPerIp;
    }

    public Cleanup getCleanup() {
        return cleanup;
    }

    public void setCleanup(Cleanup cleanup) {
        this.cleanup = cleanup;
    }

    /**
     * Cleanup sweeper settings. Mirrors the attempt-timeout sweeper pattern:
     * disabled in tests so it cannot race with clock manipulation.
     */
    public static class Cleanup {

        private boolean enabled = true;

        /** Terminal challenges older than this are deleted. */
        @NotNull
        private Duration retention = Duration.ofDays(7);

        /** Fixed rate of the sweep. */
        @NotNull
        private Duration interval = Duration.ofHours(1);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = retention;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }
    }
}
