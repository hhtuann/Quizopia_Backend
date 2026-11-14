package com.quizopia.backend.monitoring.application;

import com.quizopia.backend.attempt.application.AttemptAuthorizationService;
import com.quizopia.backend.attempt.domain.model.Attempt;
import com.quizopia.backend.attempt.domain.model.AttemptAnswerHistory;
import com.quizopia.backend.attempt.repository.AttemptAnswerHistoryRepository;
import com.quizopia.backend.attempt.repository.AttemptRepository;
import com.quizopia.backend.monitoring.dto.AnswerHistoryResponse;
import com.quizopia.backend.monitoring.dto.AnswerHistoryResponse.AnswerHistoryItem;
import com.quizopia.backend.monitoring.exception.MonitoringErrorCode;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.realtime.authorization.RealtimeAuthorizationException;
import com.quizopia.backend.realtime.authorization.RealtimeAuthorizationService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the accepted-answer history (P4-A3, issue #82):
 * {@code GET /api/attempts/{attemptId}/answer-history}.
 *
 * <p>Authorization mirrors {@link AttemptTimelineService}: the attempt owner
 * (ATTEMPT_ACTIVITY_READ) or the session-owner teacher
 * (EXAM_SESSION_MONITOR). The history is immutable — appended only inside
 * the autosave transaction on accepted sequences.
 */
@Service
public class AnswerHistoryService {

    private final AttemptAuthorizationService auth;
    private final AttemptRepository attemptRepo;
    private final AttemptAnswerHistoryRepository historyRepo;
    private final RealtimeAuthorizationService realtimeAuthz;

    public AnswerHistoryService(AttemptAuthorizationService auth,
            AttemptRepository attemptRepo,
            AttemptAnswerHistoryRepository historyRepo,
            RealtimeAuthorizationService realtimeAuthz) {
        this.auth = auth;
        this.attemptRepo = attemptRepo;
        this.historyRepo = historyRepo;
        this.realtimeAuthz = realtimeAuthz;
    }

    static final int DEFAULT_LIMIT = 200;
    static final int MAX_LIMIT = 1000;

    @Transactional(readOnly = true)
    public AnswerHistoryResponse history(Long userId, Long attemptId, Long attemptQuestionId,
            Integer limit) {
        Attempt attempt = attemptRepo.findById(attemptId)
                .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.MONITORING_ATTEMPT_NOT_FOUND));
        if (attempt.getUserId().equals(userId)) {
            auth.requireLearnerWithPermission(userId, "ATTEMPT_ACTIVITY_READ");
        } else {
            try {
                realtimeAuthz.authorizeTeacherTopic(userId, attempt.getExamSessionId());
            } catch (RealtimeAuthorizationException e) {
                throw new MonitoringException(MonitoringErrorCode.MONITORING_ACCESS_DENIED);
            }
        }
        List<AttemptAnswerHistory> all = (attemptQuestionId == null)
                ? historyRepo.findByAttemptIdOrderByAcceptedAtAscIdAsc(attemptId)
                : historyRepo.findByAttemptIdAndAttemptQuestionIdOrderByAcceptedAtAscIdAsc(
                        attemptId, attemptQuestionId);
        // Bounded read surface (review REV-4): cap the returned items; the
        // total count stays truthful. The timeline covers deep pagination.
        int total = all.size();
        int effective = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<AttemptAnswerHistory> rows = all.size() > effective
                ? all.subList(0, effective) : all;
        return new AnswerHistoryResponse(attemptId, total,
                rows.stream()
                        .map(r -> new AnswerHistoryItem(
                                r.getAttemptQuestionId(),
                                r.getAcceptedSequence(),
                                r.getAnswerPayload(),
                                r.getAcceptedAt()))
                        .toList());
    }
}
