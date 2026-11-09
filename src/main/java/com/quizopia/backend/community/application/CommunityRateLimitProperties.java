package com.quizopia.backend.community.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Community rate-limit policy (ADR-006 §9). Bound from
 * {@code quizopia.community.rate-limit.*}. Values are policy, not
 * secrets; the bucket HMAC key is derived at runtime from the data
 * encryption key (see {@code CommunityRateLimiter}) and never configured
 * here.
 */
@Validated
@ConfigurationProperties(prefix = "quizopia.community.rate-limit")
public class CommunityRateLimitProperties {

    /** Fixed admission window shared by every community bucket (1h, §9). */
    @NotNull
    private Duration window = Duration.ofHours(1);

    /** Create + PATCH per user per window (taxonomy abuse, §9). */
    @Min(1)
    private int createUpdateLimit = 30;

    /** Publish + unpublish per user per window. */
    @Min(1)
    private int publishUnpublishLimit = 10;

    /** Report submissions per user / per IP per window (#42). */
    @Min(1)
    private int reportUserLimit = 5;
    @Min(1)
    private int reportIpLimit = 20;

    /** Bookmark mutations per user per window (#45). */
    @Min(1)
    private int bookmarkLimit = 60;

    /** Forks per user per window (#47). */
    @Min(1)
    private int forkLimit = 5;

    /** Likes + unlikes per user per window (#65). */
    @Min(1)
    private int likeLimit = 60;

    /** Rating upserts + deletes per user per window (#65). */
    @Min(1)
    private int ratingLimit = 30;

    /** Comment creates per user per window (#66, ADR-007 §5). */
    @Min(1)
    private int commentCreateLimit = 10;

    /** Comment edits per user per window (#66). */
    @Min(1)
    private int commentEditLimit = 20;

    /** Comment deletes per user per window (#66). */
    @Min(1)
    private int commentDeleteLimit = 30;

    /** Comment report submissions per user / per IP per window (#66) —
     *  separate buckets from the publication REPORT_* policies. */
    @Min(1)
    private int commentReportUserLimit = 5;
    @Min(1)
    private int commentReportIpLimit = 20;

    /** Moderation writes per user per window (#42): hide/restore/dismiss/resolve. */
    @Min(1)
    private int moderationLimit = 60;

    /** Anonymous public reads per IP per window (#41; pools NAT cohorts). */
    @Min(1)
    private int publicReadIpLimit = 6000;

    /** Expired bucket rows older than this are deleted by the sweeper. */
    @NotNull
    private Duration bucketRetention = Duration.ofHours(2);

    private boolean cleanupEnabled = true;

    @NotNull
    private Duration cleanupInterval = Duration.ofHours(1);

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }

    public int getCreateUpdateLimit() {
        return createUpdateLimit;
    }

    public void setCreateUpdateLimit(int createUpdateLimit) {
        this.createUpdateLimit = createUpdateLimit;
    }

    public int getPublishUnpublishLimit() {
        return publishUnpublishLimit;
    }

    public void setPublishUnpublishLimit(int publishUnpublishLimit) {
        this.publishUnpublishLimit = publishUnpublishLimit;
    }

    public int getReportUserLimit() {
        return reportUserLimit;
    }

    public void setReportUserLimit(int reportUserLimit) {
        this.reportUserLimit = reportUserLimit;
    }

    public int getReportIpLimit() {
        return reportIpLimit;
    }

    public void setReportIpLimit(int reportIpLimit) {
        this.reportIpLimit = reportIpLimit;
    }

    public int getBookmarkLimit() {
        return bookmarkLimit;
    }

    public void setBookmarkLimit(int bookmarkLimit) {
        this.bookmarkLimit = bookmarkLimit;
    }

    public int getForkLimit() {
        return forkLimit;
    }

    public void setForkLimit(int forkLimit) {
        this.forkLimit = forkLimit;
    }

    public int getCommentCreateLimit() {
        return commentCreateLimit;
    }

    public void setCommentCreateLimit(int commentCreateLimit) {
        this.commentCreateLimit = commentCreateLimit;
    }

    public int getCommentEditLimit() {
        return commentEditLimit;
    }

    public void setCommentEditLimit(int commentEditLimit) {
        this.commentEditLimit = commentEditLimit;
    }

    public int getCommentDeleteLimit() {
        return commentDeleteLimit;
    }

    public void setCommentDeleteLimit(int commentDeleteLimit) {
        this.commentDeleteLimit = commentDeleteLimit;
    }

    public int getCommentReportUserLimit() {
        return commentReportUserLimit;
    }

    public void setCommentReportUserLimit(int commentReportUserLimit) {
        this.commentReportUserLimit = commentReportUserLimit;
    }

    public int getCommentReportIpLimit() {
        return commentReportIpLimit;
    }

    public void setCommentReportIpLimit(int commentReportIpLimit) {
        this.commentReportIpLimit = commentReportIpLimit;
    }

    public int getLikeLimit() {
        return likeLimit;
    }

    public void setLikeLimit(int likeLimit) {
        this.likeLimit = likeLimit;
    }

    public int getRatingLimit() {
        return ratingLimit;
    }

    public void setRatingLimit(int ratingLimit) {
        this.ratingLimit = ratingLimit;
    }

    public int getModerationLimit() {
        return moderationLimit;
    }

    public void setModerationLimit(int moderationLimit) {
        this.moderationLimit = moderationLimit;
    }

    public int getPublicReadIpLimit() {
        return publicReadIpLimit;
    }

    public void setPublicReadIpLimit(int publicReadIpLimit) {
        this.publicReadIpLimit = publicReadIpLimit;
    }

    public Duration getBucketRetention() {
        return bucketRetention;
    }

    public void setBucketRetention(Duration bucketRetention) {
        this.bucketRetention = bucketRetention;
    }

    public boolean isCleanupEnabled() {
        return cleanupEnabled;
    }

    public void setCleanupEnabled(boolean cleanupEnabled) {
        this.cleanupEnabled = cleanupEnabled;
    }

    public Duration getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(Duration cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }
}
