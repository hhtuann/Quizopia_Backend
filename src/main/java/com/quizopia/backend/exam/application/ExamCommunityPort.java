package com.quizopia.backend.exam.application;

import com.quizopia.backend.exam.domain.model.ExamStatus;

import java.util.Optional;

/**
 * Narrow seam through which the community module reads exam facts for
 * publication eligibility and denormalization (ADR-006 §6). Community
 * never injects Exam/Question repositories and never mutates their
 * aggregates; this port is implemented in the exam application layer and
 * is strictly read-only.
 *
 * <p>Anti-enumeration is baked in: a caller who does not own the exam
 * gets {@link Optional#empty()} — indistinguishable from a missing exam
 * — so the community service maps both to 404.
 */
public interface ExamCommunityPort {

    /**
     * Read-only eligibility projection for community publication. The
     * counts come from the current (immutable, once PUBLISHED) exam
     * composition; {@code durationMinutes} is COALESCE'd (the source
     * column is nullable).
     *
     * @param examId the exam a publication would expose
     * @param userId the prospective publisher (owner check)
     * @return empty when the exam does not exist or is not owned by
     *         {@code userId}; otherwise the projection
     */
    Optional<PublicationEligibility> checkPublicationEligibility(Long examId, Long userId);

    /**
     * Projection record (ADR-006 §6): exactly the fields the
     * create/publish gates and the denormalized publication counts need.
     */
    record PublicationEligibility(
            Long examOwnerId,
            ExamStatus examStatus,
            int durationMinutes,
            int sectionCount,
            int questionCount) {
    }
}
