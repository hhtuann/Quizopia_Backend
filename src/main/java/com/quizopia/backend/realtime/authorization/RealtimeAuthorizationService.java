package com.quizopia.backend.realtime.authorization;

import com.quizopia.backend.exam.domain.model.ExamSession;
import com.quizopia.backend.exam.repository.ExamSessionRepository;
import com.quizopia.backend.identity.repository.RolePermissionRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Authorizes a STOMP {@code SUBSCRIBE} to {@code /topic/exam-sessions/{sessionId}}:
 * effective {@code EXAM_SESSION_MONITOR} permission [DB] → session exists AND
 * the caller created it (user-centric ownership).
 *
 * <p>Deny-by-default, no JWT-authority trust, no role hierarchy. Every failure
 * path (missing permission, foreign session, missing/malformed session) throws
 * the same generic {@link RealtimeAuthorizationException} so the response does
 * not distinguish "session missing" from "not owner" — no existence leak.
 */
@Component
public class RealtimeAuthorizationService {

    private final RolePermissionRepository rolePermissionRepository;
    private final ExamSessionRepository sessionRepository;
    private final Clock clock;

    public RealtimeAuthorizationService(RolePermissionRepository rolePermissionRepository,
                                        ExamSessionRepository sessionRepository,
                                        Clock clock) {
        this.rolePermissionRepository = rolePermissionRepository;
        this.sessionRepository = sessionRepository;
        this.clock = clock;
    }

    /**
     * Throws {@link RealtimeAuthorizationException} on any authorization failure
     * (generic — no reason leaked); returns normally only when the caller holds
     * the monitor permission and owns the session.
     */
    public void authorizeTeacherTopic(Long userId, Long sessionId) {
        Instant now = Instant.now(clock);
        if (!rolePermissionRepository.findEffectivePermissionCodesByUserId(userId, now)
                .contains("EXAM_SESSION_MONITOR")) {
            throw denied();
        }
        ExamSession session = sessionRepository.findById(sessionId)
                .orElseThrow(RealtimeAuthorizationService::denied);
        if (!session.getCreatedBy().equals(userId)) {
            throw denied();
        }
    }

    private static RealtimeAuthorizationException denied() {
        return new RealtimeAuthorizationException();
    }
}
