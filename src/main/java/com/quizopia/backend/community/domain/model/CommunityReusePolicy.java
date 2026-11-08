package com.quizopia.backend.community.domain.model;

/**
 * Reuse policy of a publication (ADR-006 §2/§4). Defaults to NO_FORK;
 * ALLOW_FORK is an explicit owner consent (FE shows the warning) that
 * enables the fork endpoint (#47).
 */
public enum CommunityReusePolicy {
    NO_FORK,
    ALLOW_FORK
}
