package com.quizopia.backend.identity.domain.model;

/**
 * User account status enumeration.
 * Matches the CHECK constraint in the users table.
 */
public enum UserStatus {
    /**
     * User is active and can authenticate.
     */
    ACTIVE,

    /**
     * User account is temporarily locked due to security reasons.
     */
    LOCKED,

    /**
     * User account is permanently disabled.
     */
    DISABLED,

    /**
     * User registration is pending confirmation.
     */
    PENDING
}
