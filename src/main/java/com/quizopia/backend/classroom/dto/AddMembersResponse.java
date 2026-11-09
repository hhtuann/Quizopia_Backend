package com.quizopia.backend.classroom.dto;

import java.util.List;

/**
 * Bulk add-members result (partial success). Mirrors the exam-session
 * participant add pattern: valid rows are inserted even when other rows are
 * duplicated/invalid.
 */
public record AddMembersResponse(
        int added,
        List<Long> duplicated,
        List<Long> invalid,
        /** CLASS-M1 gate F1 (#50 decision 6): users REFUSED because they
         *  previously left this classroom (standing DECLINED opt-out) — the
         *  teacher must send an invitation the learner explicitly accepts. */
        List<Long> declined
) {
}
