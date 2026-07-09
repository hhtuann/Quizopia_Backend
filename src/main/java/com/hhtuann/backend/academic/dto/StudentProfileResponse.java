package com.hhtuann.backend.academic.dto;

public record StudentProfileResponse(
        Long id,
        String studentCode,
        Long schoolId,
        Long userId,
        String username,
        String displayName,
        String enrollmentStatus
) {
}
