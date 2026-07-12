package com.quizopia.backend.attempt.dto;

/** One bucket of the 10-bucket score distribution. */
public record ScoreDistributionBucket(int lowerBound, int upperBound, boolean upperInclusive, int count) {
}
