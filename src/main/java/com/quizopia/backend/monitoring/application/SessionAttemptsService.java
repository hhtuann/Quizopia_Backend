package com.quizopia.backend.monitoring.application;

import com.quizopia.backend.attempt.repository.AttemptRepository;
import com.quizopia.backend.monitoring.dto.SessionAttemptsResponse;
import com.quizopia.backend.monitoring.dto.SessionAttemptsResponse.Item;
import com.quizopia.backend.monitoring.exception.MonitoringErrorCode;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.realtime.authorization.RealtimeAuthorizationException;
import com.quizopia.backend.realtime.authorization.RealtimeAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teacher-side attempt listing for the Activity panel picker (P4-A4, #83):
 * unlike the graded best-results report, this includes IN_PROGRESS attempts
 * so a live exam can be monitored. Authorization mirrors the timeline/STOMP
 * gate (session owner + EXAM_SESSION_MONITOR, generic denial).
 */
@Service
public class SessionAttemptsService {

    private final AttemptRepository attemptRepo;
    private final RealtimeAuthorizationService realtimeAuthz;

    public SessionAttemptsService(AttemptRepository attemptRepo,
            RealtimeAuthorizationService realtimeAuthz) {
        this.attemptRepo = attemptRepo;
        this.realtimeAuthz = realtimeAuthz;
    }

    @Transactional(readOnly = true)
    public SessionAttemptsResponse attempts(Long userId, Long sessionId) {
        try {
            realtimeAuthz.authorizeTeacherTopic(userId, sessionId);
        } catch (RealtimeAuthorizationException e) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_ACCESS_DENIED);
        }
        return new SessionAttemptsResponse(sessionId,
                attemptRepo.findByExamSessionIdOrderByIdAsc(sessionId).stream()
                        .map(a -> new Item(a.getId(), a.getUserId(), a.getAttemptNumber(),
                                a.getStatus().name(), a.getStartedAt(), a.getDeadlineAt(),
                                a.getSubmittedAt()))
                        .toList());
    }
}
