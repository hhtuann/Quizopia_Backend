package com.quizopia.backend.realtime.authorization;

import com.quizopia.backend.academic.domain.model.TeacherProfile;
import com.quizopia.backend.academic.repository.TeacherProfileRepository;
import com.quizopia.backend.exam.domain.model.ExamSession;
import com.quizopia.backend.exam.repository.ExamSessionRepository;
import com.quizopia.backend.identity.repository.RolePermissionRepository;
import com.quizopia.backend.identity.repository.UserRoleRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Authorizes a STOMP {@code SUBSCRIBE} to {@code /topic/exam-sessions/{sessionId}}: active TEACHER
 * role [DB] → effective {@code EXAM_SESSION_MONITOR} [DB] → TeacherProfile → session exists AND is
 * owned by the caller AND same school.
 *
 * <p>Deny-by-default, no JWT-authority trust, no role hierarchy (SYSTEM_ADMIN without TEACHER cannot
 * subscribe). Every failure path (missing role, revoked permission, missing profile, foreign owner,
 * cross-school, missing/malformed session) throws the same generic {@link RealtimeAuthorizationException}
 * so the response does not distinguish "session missing" from "not owner" — no existence leak.
 */
@Component
public class RealtimeAuthorizationService {

    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final ExamSessionRepository sessionRepository;
    private final Clock clock;

    public RealtimeAuthorizationService(UserRoleRepository userRoleRepository,
                                        RolePermissionRepository rolePermissionRepository,
                                        TeacherProfileRepository teacherProfileRepository,
                                        ExamSessionRepository sessionRepository, Clock clock) {
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.teacherProfileRepository = teacherProfileRepository;
        this.sessionRepository = sessionRepository;
        this.clock = clock;
    }

    /**
     * Throws {@link RealtimeAuthorizationException} on any authorization failure (generic — no reason
     * leaked); returns normally only when the caller owns the session in their school with the monitor
     * permission.
     */
    public void authorizeTeacherTopic(Long userId, Long sessionId) {
        Instant now = Instant.now(clock);
        if (!userRoleRepository.findActiveRoleCodesByUserId(userId, now).contains("TEACHER")) {
            throw denied();
        }
        if (!rolePermissionRepository.findEffectivePermissionCodesByUserId(userId, now).contains("EXAM_SESSION_MONITOR")) {
            throw denied();
        }
        TeacherProfile profile = teacherProfileRepository.findByUserId(userId)
                .orElseThrow(RealtimeAuthorizationService::denied);
        ExamSession session = sessionRepository.findById(sessionId)
                .orElseThrow(RealtimeAuthorizationService::denied);
        if (!session.getOwnerTeacherId().equals(profile.getId())
                || !session.getSchoolId().equals(profile.getSchoolId())) {
            throw denied();
        }
    }

    private static RealtimeAuthorizationException denied() {
        return new RealtimeAuthorizationException();
    }
}
