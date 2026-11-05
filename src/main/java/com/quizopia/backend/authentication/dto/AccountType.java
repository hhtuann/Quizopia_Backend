package com.quizopia.backend.authentication.dto;

/**
 * Account type selectable during public registration. Identity-M1: STUDENT is
 * the only accepted value; TEACHER is rejected (teacher access comes from the
 * authenticated self-upgrade flow, M2).
 */
public enum AccountType {
    STUDENT,
    TEACHER
}
