package com.hhtuann.backend.classroom.dto;

import java.util.List;

/**
 * Bulk add-members result (partial success). Mirrors the exam-session
 * participant add pattern: valid rows are inserted even when other rows are
 * duplicated/invalid.
 */
public record AddMembersResponse(
        int added,
        List<Long> duplicated,
        List<Long> invalid
) {
}
