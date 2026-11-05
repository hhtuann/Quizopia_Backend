package com.quizopia.backend.classroom.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * {@code POST /api/classrooms/{id}/roster} — teacher adds a pending roster
 * entry. Email is the matching address (validated format here; ownership is
 * proven later by verification). DOB/phones are encrypted at rest and never
 * appear in logs.
 */
public record CreateRosterEntryRequest(
        @NotBlank @Size(max = 150) String fullName,
        @Size(max = 50) String studentNumber,
        @Pattern(regexp = "^(MALE|FEMALE|OTHER)?$", message = "Gender must be MALE, FEMALE, OTHER or empty")
        String gender,
        @Past LocalDate dob,
        @Pattern(regexp = "^[+0-9 ().-]{5,20}$", message = "Invalid phone format")
        String phone,
        @Pattern(regexp = "^[+0-9 ().-]{5,20}$", message = "Invalid phone format")
        String parentPhone,
        @NotBlank @Email @Size(max = 254) String email
) {
}
