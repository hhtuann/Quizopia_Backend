package com.quizopia.backend.classroom.dto.reporting;

import java.math.BigDecimal;
import java.util.List;

/**
 * CLASS-M3 (issue #54): one gradebook student row — the student's identity,
 * their membership-epoch relation to the class, one cell per selected
 * session column (same order as the sessions list) and per-student
 * aggregates computed over the window-begun sessions whose cohort contains
 * them: mean of BEST grade percentages (null when nothing graded — never
 * zero) and completion = submittedSessions / cohortSessions.
 */
public record GradebookStudentRow(
        Long userId,
        String displayName,
        boolean currentMember,
        List<GradebookCell> cells,
        BigDecimal meanPercentage,
        long cohortSessions,
        long submittedSessions,
        BigDecimal completionPercentage) {
}
