package com.hhtuann.backend.exam.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PublishedExamVersionSummary(
        Integer versionNumber, Instant publishedAt, BigDecimal totalPoints
) {}
