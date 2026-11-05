package com.quizopia.backend.realtime.authorization;

import com.quizopia.backend.exam.domain.model.ExamSession;
import com.quizopia.backend.exam.repository.ExamRepository;
import com.quizopia.backend.exam.repository.ExamSessionRepository;
import com.quizopia.backend.identity.repository.RolePermissionRepository;
import com.quizopia.backend.workspace.application.WorkspaceAccessService;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Authorizes a STOMP {@code SUBSCRIBE} to {@code /topic/exam-sessions/{sessionId}}:
 * effective {@code EXAM_SESSION_MONITOR} permission [DB] → session exists AND the
 * caller may manage it (session creator with valid workspace access, or the
 * exam workspace's OWNER/ADMIN with an ACTIVE membership — issue #4 P0-2).
 *
 * <p>Deny-by-default, no JWT-authority trust, no role hierarchy. Every failure
 * path (missing permission, foreign session, revoked creator membership,
 * missing/malformed session) throws the same generic
 * {@link RealtimeAuthorizationException} so the response does not distinguish
 * "session missing" from "not owner" — no existence leak.
 */
@Component
public class RealtimeAuthorizationService {

    private final RolePermissionRepository rolePermissionRepository;
    private final ExamSessionRepository sessionRepository;
    private final ExamRepository examRepository;
    private final WorkspaceAccessService workspaceAccessService;
    private final Clock clock;

    public RealtimeAuthorizationService(RolePermissionRepository rolePermissionRepository,
                                        ExamSessionRepository sessionRepository,
                                        ExamRepository examRepository,
                                        WorkspaceAccessService workspaceAccessService,
                                        Clock clock) {
        this.rolePermissionRepository = rolePermissionRepository;
        this.sessionRepository = sessionRepository;
        this.examRepository = examRepository;
        this.workspaceAccessService = workspaceAccessService;
        this.clock = clock;
    }

    /**
     * Throws {@link RealtimeAuthorizationException} on any authorization failure
     * (generic — no reason leaked); returns normally only when the caller holds
     * the monitor permission and may manage the session per workspace policy.
     */
    public void authorizeTeacherTopic(Long userId, Long sessionId) {
        Instant now = Instant.now(clock);
        if (!rolePermissionRepository.findEffectivePermissionCodesByUserId(userId, now)
                .contains("EXAM_SESSION_MONITOR")) {
            throw denied();
        }
        ExamSession session = sessionRepository.findById(sessionId)
                .orElseThrow(RealtimeAuthorizationService::denied);
        var exam = examRepository.findById(session.getExamId())
                .orElseThrow(RealtimeAuthorizationService::denied);
        boolean allowed;
        if (session.getCreatedBy().equals(userId)) {
            // Creator: still needs valid workspace access (P0-2).
            try {
                workspaceAccessService.requireResourceRead(userId, exam.getWorkspaceId());
                allowed = true;
            } catch (com.quizopia.backend.workspace.exception.WorkspaceException ex) {
                allowed = false;
            }
        } else {
            try {
                workspaceAccessService.requireResourceManage(userId, exam.getWorkspaceId(),
                        exam.getCreatedByUserUserId());
                allowed = true;
            } catch (com.quizopia.backend.workspace.exception.WorkspaceException ex) {
                allowed = false;
            }
        }
        if (!allowed) {
            throw denied();
        }
    }

    private static RealtimeAuthorizationException denied() {
        return new RealtimeAuthorizationException();
    }
}
