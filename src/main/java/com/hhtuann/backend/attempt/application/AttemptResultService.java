package com.hhtuann.backend.attempt.application;

import com.hhtuann.backend.academic.domain.model.StudentProfile;
import com.hhtuann.backend.attempt.domain.model.Attempt;
import com.hhtuann.backend.attempt.domain.model.AttemptAnswer;
import com.hhtuann.backend.attempt.domain.model.AttemptQuestion;
import com.hhtuann.backend.attempt.domain.model.AttemptStatus;
import com.hhtuann.backend.attempt.domain.model.Grade;
import com.hhtuann.backend.attempt.domain.model.GradeItem;
import com.hhtuann.backend.attempt.dto.AttemptResultResponse;
import com.hhtuann.backend.attempt.dto.QuestionResultView;
import com.hhtuann.backend.attempt.exception.AttemptErrorCode;
import com.hhtuann.backend.attempt.exception.AttemptException;
import com.hhtuann.backend.attempt.repository.AttemptAnswerRepository;
import com.hhtuann.backend.attempt.repository.AttemptQuestionRepository;
import com.hhtuann.backend.attempt.repository.AttemptRepository;
import com.hhtuann.backend.attempt.repository.GradeItemRepository;
import com.hhtuann.backend.attempt.repository.GradeRepository;
import com.hhtuann.backend.grading.BestResultComparator;
import com.hhtuann.backend.grading.domain.BestCandidate;
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
         * Role-aware attempt result (reads persisted snapshot; no re-grading; no answer
         * key).
         */
        public AttemptResultResponse getAttemptResult(Long userId, String primaryRole, Long attemptId) {
                Attempt attempt = attemptRepo.findById(attemptId)
                                .orElseThrow(() -> new AttemptException(AttemptErrorCode.RESULT_NOT_FOUND));
                // Role-aware authorization
                if ("STUDENT".equals(primaryRole)) {
                        StudentProfile profile = auth.requireStudentWithPermission(userId, "ATTEMPT_READ");
                        if (!Objects.equals(attempt.getStudentProfileId(), profile.getId())
                                        || !Objects.equals(attempt.getSchoolId(), profile.getSchoolId())) {
                                throw new AttemptException(AttemptErrorCode.RESULT_ACCESS_DENIED);
                        }
                } else if ("TEACHER".equals(primaryRole)) {
                        authorizeTeacherOwnsSession(userId, attempt.getExamSessionId());
                } else if ("SYSTEM_ADMIN".equals(primaryRole) || "ACADEMIC_ADMIN".equals(primaryRole)) {
                        // allowed
                } else {
                        throw new AttemptException(AttemptErrorCode.RESULT_ACCESS_DENIED);
                }
                if (attempt.getStatus() != AttemptStatus.SUBMITTED && attempt.getStatus() != AttemptStatus.GRADED) {
                        throw new AttemptException(AttemptErrorCode.ATTEMPT_NOT_SUBMITTED);
                }
                Grade grade = gradeRepo.findByAttemptId(attemptId)
                                .orElseThrow(() -> new AttemptException(AttemptErrorCode.ATTEMPT_NOT_GRADED));
                return buildResponse(attempt, grade, attempt.getStudentProfileId());
        }

        private void authorizeTeacherOwnsSession(Long userId, Long sessionId) {
                Integer owns = jdbc.queryForObject(
                                "SELECT count(*) FROM exam_sessions s JOIN teacher_profiles tp ON tp.id = s.owner_teacher_id "
                                                + "WHERE s.id = ? AND tp.user_id = ?",
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
                StudentProfile profile = auth.requireStudentWithPermission(userId, "ATTEMPT_READ");
                Long bestAttemptId = findBestAttemptId(sessionId, profile.getId());
                if (bestAttemptId == null) {
                        throw new AttemptException(AttemptErrorCode.RESULT_NOT_FOUND);
                }
                Attempt attempt = attemptRepo.findById(bestAttemptId)
                                .orElseThrow(() -> new AttemptException(AttemptErrorCode.RESULT_NOT_FOUND));
                Grade grade = gradeRepo.findByAttemptId(bestAttemptId)
                                .orElseThrow(() -> new AttemptException(AttemptErrorCode.ATTEMPT_NOT_GRADED));
                return buildResponse(attempt, grade, profile.getId());
        }

        @SuppressWarnings("null")
        private AttemptResultResponse buildResponse(Attempt attempt, Grade grade, Long studentProfileId) {
                List<GradeItem> items = gradeItemRepo.findByGradeIdOrderByIdAsc(grade.getId());
                List<AttemptQuestion> questions = attemptQuestionRepo
                                .findByAttemptIdOrderByDisplayOrderAsc(attempt.getId());
                List<AttemptAnswer> answers = attemptAnswerRepo.findByAttemptId(attempt.getId());
                Set<Long> answeredQIds = answers.stream()
                                .filter(a -> a.getAnswerPayload() != null)
                                .map(AttemptAnswer::getAttemptQuestionId)
                                .collect(Collectors.toSet());
                // isBest + attemptCount — batch load grades for all submitted attempts of this
                // (session, student).
                List<Attempt> allAttempts = attemptRepo.findByExamSessionIdAndStudentProfileId(
                                attempt.getExamSessionId(), studentProfileId);
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
         * Finds the BEST attempt id for (session, student) using the frozen comparator,
         * or null if none.
         */
        @SuppressWarnings("null")
        private Long findBestAttemptId(Long sessionId, Long studentProfileId) {
                List<Attempt> all = attemptRepo.findByExamSessionIdAndStudentProfileId(sessionId, studentProfileId);
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
