package com.quizopia.backend.identity.domain.model;

/**
 * Purpose of an email-verification challenge. Distinct from the academic
 * pending-student state: verification is about email ownership only.
 */
public enum EmailChallengePurpose {
    ACCOUNT_VERIFICATION
}
