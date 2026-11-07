package com.quizopia.backend.attempt.application;


import com.quizopia.backend.attempt.domain.model.Attempt;
import com.quizopia.backend.attempt.domain.model.AttemptAnswer;
import com.quizopia.backend.attempt.domain.model.AttemptQuestion;
import com.quizopia.backend.attempt.domain.model.AttemptStatus;
import com.quizopia.backend.attempt.domain.model.Grade;
import com.quizopia.backend.attempt.domain.model.GradeItem;
import com.quizopia.backend.attempt.dto.AttemptResultResponse;
import com.quizopia.backend.attempt.dto.QuestionResultView;
import com.quizopia.backend.attempt.exception.AttemptErrorCode;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.attempt.repository.AttemptAnswerRepository;
import com.quizopia.backend.attempt.repository.AttemptQuestionRepository;
import com.quizopia.backend.attempt.repository.AttemptRepository;
import com.quizopia.backend.attempt.repository.GradeItemRepository;
import com.quizopia.backend.attempt.repository.GradeRepository;
import com.quizopia.backend.grading.BestResultComparator;
import com.quizopia.backend.grading.domain.BestCandidate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Day 8 result read service — reads persisted Grade/GradeItems, builds
 * student-facing DTOs with the BEST
 * flag, and enforces authorization. NEVER re-grades, NEVER reads the answer
 * key. The {@code answered} flag
 * is derived from persisted {@link AttemptAnswer#getAnswerPayload()} (non-null
 * = answered).
 */
@Service
@Transactional(readOnly = true)
public class AttemptResultService {

        private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        private final AttemptAuthorizationService auth;
        private final AttemptRepository attemptRepo;
        private final GradeRepository gradeRepo;
        private final GradeItemRepository gradeItemRepo;
        private final AttemptQuestionRepository attemptQuestionRepo;
        private final AttemptAnswerRepository attemptAnswerRepo;
        private final JdbcTemplate jdbc;

        public AttemptResultService(AttemptAuthorizationService auth, AttemptRepository attemptRepo,
                        GradeRepository gradeRepo, GradeItemRepository gradeItemRepo,
                        AttemptQuestionRepository attemptQuestionRepo,
                        AttemptAnswerRepository attemptAnswerRepo, JdbcTemplate jdbc) {
                this.auth = auth;
                this.attemptRepo = attemptRepo;
                this.gradeRepo = gradeRepo;
                this.gradeItemRepo = gradeItemRepo;
                this.attemptQuestionRepo = attemptQuestionRepo;
                this.attemptAnswerRepo = attemptAnswerRepo;
                this.jdbc = jdbc;
        }


         /**
          * Resource-based attempt result (reads persisted snapshot; no re-grading;
          * no answer key). Policy — the decision does NOT branch on the caller's
          * resolved role:
          * <ol>
          *   <li>OWN attempt — any authorized learner holding ATTEMPT_READ may
          *       read their own submitted/graded attempt ({@code attempt.userId}),
          *       regardless of TEACHER/STUDENT capability mix;</li>
          *   <li>organizer — the session creator (user-centric ownership).</li>
          * </ol>
          */
          public AttemptResultResponse getAttemptResult(Long userId, Long attemptId) {
                 Attempt attempt = attemptRepo.findById(attemptId)
                                 .orElseThrow(() -> new AttemptException(AttemptErrorCode.RESULT_NOT_FOUND));
                 if (attempt.getUserId().equals(userId)) {
                         auth.requireLearnerWithPermission(userId, "ATTEMPT_READ");
                 } else {
                         authorizeOrganizerOwnsSession(userId, attempt.getExamSessionId());
                 }
                 if (attempt.getStatus() != AttemptStatus.SUBMITTED && attempt.getStatus() != AttemptStatus.GRADED) {
                         throw new AttemptException(AttemptErrorCode.ATTEMPT_NOT_SUBMITTED);
                 }
                 Grade grade = gradeRepo.findByAttemptId(attemptId)
                                 .orElseThrow(() -> new AttemptException(AttemptErrorCode.ATTEMPT_NOT_GRADED));
                 return buildResponse(attempt, grade, attempt.getUserId());
         }

         private void authorizeOrganizerOwnsSession(Long userId, Long sessionId) {
                // Session creator only (user-centric ownership).
                Integer owns = jdbc.queryForObject(
                                "SELECT count(*) FROM exam_sessions s WHERE s.id = ? AND s.created_by = ?",
                                Integer.class, sessionId, userId);
                if (owns == null || owns == 0) {
                        throw new AttemptException(AttemptErrorCode.RESULT_ACCESS_DENIED);
                }
         }


        /**
         * Student own BEST result for a session (RESULT_NOT_FOUND if no
         * submitted+graded attempt).
         */
         public AttemptResultResponse getMyBestResult(Long userId, Long sessionId) {
                auth.requireLearnerWithPermission(userId, "ATTEMPT_READ");
                Long bestAttemptId = findBestAttemptId(sessionId, userId);
                if (bestAttemptId == null) {
                        throw new AttemptException(AttemptErrorCode.RESULT_NOT_FOUND);
                }
                Attempt attempt = attemptRepo.findById(bestAttemptId)
                                .orElseThrow(() -> new AttemptException(AttemptErrorCode.RESULT_NOT_FOUND));
                Grade grade = gradeRepo.findByAttemptId(bestAttemptId)
                                .orElseThrow(() -> new AttemptException(AttemptErrorCode.ATTEMPT_NOT_GRADED));
                return buildResponse(attempt, grade, userId);
        }

        @SuppressWarnings("null")
        private AttemptResultResponse buildResponse(Attempt attempt, Grade grade, Long userId) {
                List<GradeItem> items = gradeItemRepo.findByGradeIdOrderByIdAsc(grade.getId());
                List<AttemptQuestion> questions = attemptQuestionRepo
                                .findByAttemptIdOrderByDisplayOrderAsc(attempt.getId());
                List<AttemptAnswer> answers = attemptAnswerRepo.findByAttemptId(attempt.getId());
                Set<Long> answeredQIds = answers.stream()
                                .filter(a -> a.getAnswerPayload() != null)
                                .map(AttemptAnswer::getAttemptQuestionId)
                                .collect(Collectors.toSet());
                // isBest + attemptCount — batch load grades for all submitted attempts of this
                // (session, user).
                List<Attempt> allAttempts = attemptRepo.findByExamSessionIdAndUserId(
                                attempt.getExamSessionId(), userId);
                List<Long> submittedIds = allAttempts.stream()
                                .filter(a -> a.getStatus() == AttemptStatus.SUBMITTED
                                                || a.getStatus() == AttemptStatus.GRADED)
                                .map(Attempt::getId).toList();
                Map<Long, Grade> gradeByAttempt = gradeRepo.findAllByAttemptIdIn(submittedIds).stream()
                                .collect(Collectors.toMap(Grade::getAttemptId, g -> g, (a, b) -> a));
                Map<Long, Attempt> attemptById = allAttempts.stream()
                                .collect(Collectors.toMap(Attempt::getId, a -> a, (a, b) -> a));
                List<BestCandidate> candidates = submittedIds.stream()
                                .map(id -> {
                                        Grade g = gradeByAttempt.get(id);
                                        Attempt a = attemptById.get(id);
                                        if (g == null || a == null)
                                                return null;
                                        return new BestCandidate(a.getId(), g.getPercentage(), g.getFinalScore(),
                                                        a.getSubmittedAt());
                                })
                                .filter(Objects::nonNull).toList();
                BestCandidate best = candidates.isEmpty() ? null
                                : candidates.stream().min(BestResultComparator.INSTANCE).orElse(null);
                boolean isBest = best != null && best.attemptId().equals(attempt.getId());
                int attemptCount = submittedIds.size();
                // per-question results (grading outcome only — no answer key)
                Map<Long, GradeItem> itemByAQ = items.stream()
                                .collect(Collectors.toMap(GradeItem::getAttemptQuestionId, i -> i, (a, b) -> a));
                List<QuestionResultView> questionResults = questions.stream()
                                .map(q -> {
                                        GradeItem gi = itemByAQ.get(q.getId());
                                        return new QuestionResultView(q.getId(), q.getExamQuestionId(),
                                                        q.getQuestionType(),
                                                        gi != null ? gi.getAwardedPoints() : ZERO,
                                                        gi != null ? gi.getMaxPoints() : q.getDefaultPoints(),
                                                        gi != null && gi.isCorrect(),
                                                        answeredQIds.contains(q.getId()));
                                }).toList();
                return new AttemptResultResponse(attempt.getId(), attempt.getExamSessionId(),
                                attempt.getStatus().name(), attempt.getSubmittedAt(), grade.getGradedAt(),
                                grade.getStatus().name(), grade.getFinalScore(), grade.getMaxScore(),
                                grade.getPercentage(), isBest, attemptCount, questionResults);
        }

        /**
         * Finds the BEST attempt id for (session, user) using the frozen comparator,
         * or null if none.
         */
        @SuppressWarnings("null")
        private Long findBestAttemptId(Long sessionId, Long userId) {
                List<Attempt> all = attemptRepo.findByExamSessionIdAndUserId(sessionId, userId);
                List<Long> submittedIds = all.stream()
                                .filter(a -> a.getStatus() == AttemptStatus.SUBMITTED
                                                || a.getStatus() == AttemptStatus.GRADED)
                                .map(Attempt::getId).toList();
                if (submittedIds.isEmpty())
                        return null;
                Map<Long, Grade> gradeByAttempt = gradeRepo.findAllByAttemptIdIn(submittedIds).stream()
                                .collect(Collectors.toMap(Grade::getAttemptId, g -> g, (a, b) -> a));
                Map<Long, Attempt> attemptById = all.stream()
                                .collect(Collectors.toMap(Attempt::getId, a -> a, (a, b) -> a));
                return submittedIds.stream()
                                .map(id -> {
                                        Grade g = gradeByAttempt.get(id);
                                        Attempt a = attemptById.get(id);
                                        if (g == null || a == null)
                                                return null;
                                        return new BestCandidate(a.getId(), g.getPercentage(), g.getFinalScore(),
                                                        a.getSubmittedAt());
                                })
                                .filter(Objects::nonNull)
                                .min(BestResultComparator.INSTANCE)
                                .map(BestCandidate::attemptId)
                                .orElse(null);
        }
}
