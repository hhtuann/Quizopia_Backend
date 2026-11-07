package com.quizopia.backend.exam.application;

import com.quizopia.backend.exam.domain.model.ExamStatus;
import com.quizopia.backend.question.application.QuestionCommunityPort;
import com.quizopia.backend.question.domain.model.QuestionDifficulty;
import com.quizopia.backend.question.domain.model.QuestionType;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Narrow seam through which the community module reads exam facts for
 * publication eligibility and denormalization, and clones a published
 * exam into an independent DRAFT for the fork flow (ADR-006 §6, issues
 * #40/#47). Community never injects Exam/Question repositories and
 * never mutates their aggregates outside these port methods.
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
     * Loads the full published composition snapshot for the fork flow
     * (ADR-006 §6/§8 step 5) <b>inside the exam row lock</b>: the
     * implementation first takes {@code findByIdForUpdate} (the §8
     * source-exam lock, so fork serializes against archive and any
     * exam mutation) and only then reads the composition rows — the
     * immutable published state, never the mutable
     * {@code question_versions}. Joins the caller's transaction (the
     * fork is one atomic unit with the lock order
     * rate-bucket → exam → publication).
     *
     * @param examId the source (published) exam
     * @return empty when the exam does not exist
     */
    Optional<PublishedCompositionSnapshot> loadPublishedSnapshot(Long examId);

    /**
     * Creates the fork's fresh DRAFT exam (ADR-006 §6/§8 step 7): new
     * {@code EX} code unique per owner, {@code source_exam_id} = the
     * source exam, title/subject/purpose/description/instructions/
     * duration copied, {@code tf_matrix_scoring} deep-copied
     * ({@code copyGradingPolicyFrom} semantics), {@code total_points}
     * left at the DRAFT 0 convention (recomputed by the normal publish
     * path), and sections/questions/options cloned with
     * {@code source_question_id}/{@code source_question_version_id}
     * REMAPPED to the cloned question ids (§6: the new owner's banks
     * own every referenced question, so their existing edit/publish
     * APIs work unchanged). No sessions/attempts/results/grades are
     * ever created.
     *
     * @return the new exam id
     */
    Long createForkDraft(ForkDraftSpec spec);

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

    /**
     * Server-only published composition snapshot (ADR-006 §6 — the
     * exact field list is frozen). Never serialized to any API, never
     * logged: it carries answer keys and option correctness.
     */
    record PublishedCompositionSnapshot(
            JsonNode tfMatrixScoring,
            String instructions,
            Integer durationMinutes,
            Long subjectId,
            Long purposeId,
            String title,
            String description,
            List<SnapshotSection> sections) {

        /**
         * Mirrors the {@code ExamQuestion}/{@code ExamQuestionOption}
         * convention: never renders answer material. Safe to nest —
         * every {@code SnapshotQuestion}/{@code SnapshotOption} below
         * redacts its answer fields.
         */
        @Override
        public String toString() {
            return "PublishedCompositionSnapshot[tfMatrixScoring=" + tfMatrixScoring
                    + ", instructions=" + instructions
                    + ", durationMinutes=" + durationMinutes
                    + ", subjectId=" + subjectId
                    + ", purposeId=" + purposeId
                    + ", title=" + title
                    + ", description=" + description
                    + ", sections=" + sections + "]";
        }
    }

    /** Section slice of the snapshot. */
    record SnapshotSection(
            int position,
            String title,
            String instructions,
            String code,
            List<SnapshotQuestion> questions) {
    }

    /** Question slice of the snapshot, with its original provenance ids. */
    record SnapshotQuestion(
            int position,
            QuestionType questionType,
            String content,
            BigDecimal points,
            QuestionDifficulty difficulty,
            String explanation,
            JsonNode answerKey,
            JsonNode metadata,
            Long sourceQuestionId,
            Long sourceQuestionVersionId,
            List<SnapshotOption> options) {

        /**
         * Mirrors {@code ExamQuestion#toString}: {@code answerKey} is
         * rendered as {@code <redacted>} so the auto-generated record
         * {@code toString} can never leak answers into logs.
         */
        @Override
        public String toString() {
            return "SnapshotQuestion[position=" + position
                    + ", questionType=" + questionType
                    + ", content=" + content
                    + ", points=" + points
                    + ", difficulty=" + difficulty
                    + ", explanation=" + explanation
                    + ", answerKey=<redacted>"
                    + ", metadata=" + metadata
                    + ", sourceQuestionId=" + sourceQuestionId
                    + ", sourceQuestionVersionId=" + sourceQuestionVersionId
                    + ", options=" + options + "]";
        }
    }

    /** Option slice of the snapshot. */
    record SnapshotOption(
            String optionKey,
            String content,
            boolean isCorrect,
            int position) {

        /**
         * Mirrors {@code ExamQuestionOption#toString}:
         * {@code isCorrect} is omitted so the auto-generated record
         * {@code toString} can never leak answers into logs.
         */
        @Override
        public String toString() {
            return "SnapshotOption[optionKey=" + optionKey
                    + ", content=" + content
                    + ", position=" + position + "]";
        }
    }

    /**
     * Everything {@link #createForkDraft} needs: the requester who will
     * own the DRAFT + the cloned bank, the source exam id for
     * {@code source_exam_id} provenance, the snapshot, and the
     * source-question-id → cloned-id remapping produced by
     * {@link QuestionCommunityPort#cloneForFork}.
     */
    record ForkDraftSpec(
            Long ownerUserId,
            Long sourceExamId,
            PublishedCompositionSnapshot snapshot,
            Map<Long, QuestionCommunityPort.ClonedQuestion> remappedQuestions) {
    }
}
