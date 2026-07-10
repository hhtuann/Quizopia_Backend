package com.hhtuann.backend.academic.dto;

import java.time.Instant;

public record SchoolResponse(
        Long id,
        String code,
        String name,
        String address,
        String status,
        Instant createdAt
) {
}
