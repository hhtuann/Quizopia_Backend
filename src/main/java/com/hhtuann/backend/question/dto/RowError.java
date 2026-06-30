package com.hhtuann.backend.question.dto;

/**
 * A single row-level validation error from the Excel import parser.
 * Row errors do NOT abort the whole workbook; they are collected and returned
 * as part of {@link ImportResult#errors()}.
 */
public record RowError(
        int rowNumber,
        String questionCode,
        String field,
        String code,
        String message
) {}
