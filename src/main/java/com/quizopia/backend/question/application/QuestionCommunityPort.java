package com.quizopia.backend.question.application;

import com.quizopia.backend.exam.application.ExamCommunityPort;

import java.util.Map;

/**
 * Narrow seam through which the community module clones a published
 * exam's questions into an independent bank owned by the forker
 * (ADR-006 §6, issue #47). Community never injects Question
 * repositories; this port is implemented in the question application
 * layer and joins the caller's fork transaction (§8: one atomic unit).
 */
public interface QuestionCommunityPort {

    /**
     * Clones every snapshot question into ONE fresh bank owned by
     * {@code ownerUserId} (ADR-006 §6/§8 step 6): a new {@code QB} code
     * unique per owner, fresh {@code QU} codes unique per bank,
     * question status ACTIVE, {@code current_version_number = 1}, one
     * immutable {@code question_versions} row per question (version 1,
     * all fields including the NUMERIC_FILL {@code answer_key}), and
     * {@code question_options} with {@code is_correct} — for all four
     * types (SINGLE_CHOICE, MULTIPLE_CHOICE, TRUE_FALSE_MATRIX,
     * NUMERIC_FILL). {@code created_by} = owner everywhere. Source rows
     * are never written.
     *
     * @return the new bank id plus the sourceQuestionId → cloned
     *         question/version/code mapping the exam-side fork draft
     *         uses to remap provenance ids
     */
    QuestionCloneResult cloneForFork(ExamCommunityPort.PublishedCompositionSnapshot snapshot,
            Long ownerUserId);

    /** Clone outcome: the new bank plus the provenance remapping. */
    record QuestionCloneResult(
            Long bankId,
            Map<Long, ClonedQuestion> bySourceQuestionId) {
    }

    /**
     * One cloned question: its new question id, its new version-1 id
     * (the id exam provenance pins), and its new {@code QU} code.
     */
    record ClonedQuestion(
            Long questionId,
            Long versionId,
            String code) {
    }
}
