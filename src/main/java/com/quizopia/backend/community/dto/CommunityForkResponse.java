package com.quizopia.backend.community.dto;

import java.time.Instant;

/**
 * Fork response (ADR-006 §7, issue #47): 201 on first creation, 200 on
 * the idempotent replay. Attribution comes from the already-public
 * publication snapshot — never any answer material or internal ids
 * beyond the target exam the requester now owns.
 */
public record CommunityForkResponse(
        Long targetExamId,
        Long sourcePublicationId,
        String sourceTitle,
        String authorAlias,
        Instant forkedAt) {
}
