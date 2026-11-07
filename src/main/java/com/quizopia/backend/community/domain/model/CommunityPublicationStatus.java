package com.quizopia.backend.community.domain.model;

/**
 * Publication lifecycle state (ADR-006 §4). UNPUBLISHED is the created
 * state; PUBLISHED requires the publish gates (alias + eligibility
 * recheck under the publication row lock). There is no DELETE.
 */
public enum CommunityPublicationStatus {
    UNPUBLISHED,
    PUBLISHED
}
