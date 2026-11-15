package com.quizopia.backend.evidence.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code quizopia.media.egress.*} (P4-D, ADR-009 §2.2/§2.5). */
@ConfigurationProperties("quizopia.media.egress")
public class EvidenceProperties {

    /** Rolling-buffer pulse length (recording OFF + clips ON). */
    private Duration pulseSeconds = Duration.ofSeconds(120);

    /** Rolling pulses kept per attempt (buffer bound; older objects deleted). */
    private int rollingKeep = 3;

    private Duration clipPreRoll = Duration.ofSeconds(30);

    private Duration clipPostRoll = Duration.ofSeconds(60);

    /** Hard cap on the extracted window. */
    private Duration clipMaxWindow = Duration.ofMinutes(5);

    /** Client-vs-server clock tolerance applied before coverage math. */
    private Duration clockSkewMargin = Duration.ofSeconds(5);

    /** Gaps below this are pulse-restart cost, not real coverage holes. */
    private Duration gapIgnoreThreshold = Duration.ofSeconds(2);

    /** ffmpeg invocation (split on whitespace); absence = honest FAILED. */
    private String ffmpegCommand = "ffmpeg";

    private Duration ffmpegTimeout = Duration.ofSeconds(60);

    private long snapshotMaxBytes = 2 * 1024 * 1024;

    private int snapshotPerAlert = 3;

    private int snapshotPerAttempt = 30;

    public Duration getPulseSeconds() {
        return pulseSeconds;
    }

    public void setPulseSeconds(Duration pulseSeconds) {
        this.pulseSeconds = pulseSeconds;
    }

    public int getRollingKeep() {
        return rollingKeep;
    }

    public void setRollingKeep(int rollingKeep) {
        this.rollingKeep = rollingKeep;
    }

    public Duration getClipPreRoll() {
        return clipPreRoll;
    }

    public void setClipPreRoll(Duration clipPreRoll) {
        this.clipPreRoll = clipPreRoll;
    }

    public Duration getClipPostRoll() {
        return clipPostRoll;
    }

    public void setClipPostRoll(Duration clipPostRoll) {
        this.clipPostRoll = clipPostRoll;
    }

    public Duration getClipMaxWindow() {
        return clipMaxWindow;
    }

    public void setClipMaxWindow(Duration clipMaxWindow) {
        this.clipMaxWindow = clipMaxWindow;
    }

    public Duration getClockSkewMargin() {
        return clockSkewMargin;
    }

    public void setClockSkewMargin(Duration clockSkewMargin) {
        this.clockSkewMargin = clockSkewMargin;
    }

    public Duration getGapIgnoreThreshold() {
        return gapIgnoreThreshold;
    }

    public void setGapIgnoreThreshold(Duration gapIgnoreThreshold) {
        this.gapIgnoreThreshold = gapIgnoreThreshold;
    }

    public String getFfmpegCommand() {
        return ffmpegCommand;
    }

    public void setFfmpegCommand(String ffmpegCommand) {
        this.ffmpegCommand = ffmpegCommand;
    }

    public Duration getFfmpegTimeout() {
        return ffmpegTimeout;
    }

    public void setFfmpegTimeout(Duration ffmpegTimeout) {
        this.ffmpegTimeout = ffmpegTimeout;
    }

    public long getSnapshotMaxBytes() {
        return snapshotMaxBytes;
    }

    public void setSnapshotMaxBytes(long snapshotMaxBytes) {
        this.snapshotMaxBytes = snapshotMaxBytes;
    }

    public int getSnapshotPerAlert() {
        return snapshotPerAlert;
    }

    public void setSnapshotPerAlert(int snapshotPerAlert) {
        this.snapshotPerAlert = snapshotPerAlert;
    }

    public int getSnapshotPerAttempt() {
        return snapshotPerAttempt;
    }

    public void setSnapshotPerAttempt(int snapshotPerAttempt) {
        this.snapshotPerAttempt = snapshotPerAttempt;
    }
}
