package com.hhtuann.backend.authentication.dto;

/**
 * Account type selectable during public registration. {@code STUDENT} is the
 * default when the request omits {@code accountType}; {@code TEACHER} requires
 * a valid teacher invite code.
 */
public enum AccountType {
    STUDENT,
    TEACHER
}
