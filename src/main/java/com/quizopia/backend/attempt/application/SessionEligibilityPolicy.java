package com.quizopia.backend.attempt.application;

import com.quizopia.backend.exam.domain.model.ExamSession;
import com.quizopia.backend.exam.domain.model.SessionVisibility;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Shared visibility-based eligibility check for exam sessions — the single
 * gate reused by {@code AttemptService.startAttempt} (start) and
 * {@code CameraPreflightService} (camera preflight) so the preflight can
 * never promise a start the start path would refuse.
 *
 * <p>Rules (user-based, issue #4 P0-1):
 * <ul>
 *   <li>PUBLIC: any ACTIVE platform user with attempt capability.</li>
 *   <li>CLASS_RESTRICTED: user is a member of at least one classroom
 *       assigned to the session via {@code exam_session_classes}.</li>
 *   <li>BLOCKED participant (P0-R7/R8): an explicit BLOCKED row for this
 *       user denies NEW starts for both visibilities; already IN_PROGRESS
 *       attempts may still complete (their data is never destroyed).</li>
 * </ul>
 */
@Component
public class SessionEligibilityPolicy {

    private final JdbcTemplate jdbc;

    public SessionEligibilityPolicy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** See class javadoc; pure read, no locks. */
    public boolean isEligible(Long userId, ExamSession session) {
        // Blocked participants are denied regardless of visibility (P0-R7/R8).
        Integer blocked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_session_participants bp "
                        + "WHERE bp.exam_session_id = ? AND bp.user_id = ? AND bp.status = 'BLOCKED'",
                Integer.class, session.getId(), userId);
        if (blocked != null && blocked > 0) {
            return false;
        }
        if (session.getVisibility() == SessionVisibility.PUBLIC) {
            return true;
        }
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_session_classes esc "
                        + "JOIN classroom_members cm ON cm.classroom_id = esc.classroom_id "
                        + "WHERE esc.exam_session_id = ? AND cm.user_id = ?",
                Integer.class, session.getId(), userId);
        return cnt != null && cnt > 0;
    }
}
