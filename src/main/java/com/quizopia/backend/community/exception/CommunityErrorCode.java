package com.quizopia.backend.community.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Community module error codes (ADR-006 §4/§7/§9). Anti-enumeration:
 * absent/foreign/invisible resources are always COMMUNITY_NOT_FOUND
 * (404); 403 is reserved for "authenticated but not allowed".
 */
public enum CommunityErrorCode {

    COMMUNITY_VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Community request validation failed"),
    COMMUNITY_TAG_INVALID(HttpStatus.BAD_REQUEST, "Invalid community tag"),
    COMMUNITY_ALIAS_INVALID(HttpStatus.BAD_REQUEST, "Author alias must not match the account email or username"),
    COMMUNITY_CATEGORY_NOT_FOUND(HttpStatus.BAD_REQUEST, "Unknown community category"),

    COMMUNITY_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied to this community resource"),

    COMMUNITY_NOT_FOUND(HttpStatus.NOT_FOUND, "Community publication not found"),

    COMMUNITY_PUBLICATION_EXISTS(HttpStatus.CONFLICT, "A publication already exists for this exam"),
    COMMUNITY_CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "The publication was modified by another transaction"),
    COMMUNITY_MODERATION_HIDDEN(HttpStatus.CONFLICT, "The publication is hidden by moderation"),
    COMMUNITY_PUBLISH_INELIGIBLE(HttpStatus.CONFLICT, "The exam is not eligible for community publication"),
    COMMUNITY_ALIAS_REQUIRED(HttpStatus.CONFLICT, "A public author alias is required before publishing"),

    COMMUNITY_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Community rate limit exceeded");

    private final HttpStatusCode status;
    private final String defaultMessage;

    CommunityErrorCode(HttpStatusCode status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatusCode status() {
        return status;
    }

    public int statusCode() {
        return status.value();
    }

    public String code() {
        return name();
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
