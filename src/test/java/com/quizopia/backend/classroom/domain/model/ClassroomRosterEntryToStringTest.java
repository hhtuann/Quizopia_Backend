package com.quizopia.backend.classroom.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review M3-FINAL-04: the entity's {@code toString()} must never carry PII —
 * it can end up in logs/exception messages. Allowed: technical identifiers
 * and lifecycle state only (id, classroomId, status, timestamps). Never:
 * full name, emails (raw or canonical), DOB, phones, or ciphertext.
 */
class ClassroomRosterEntryToStringTest {

    private static final String SENSITIVE_NAME = "Nguyen Thi Sensitivity";
    private static final String SENSITIVE_EMAIL = "sensitive.kid@example.com";
    private static final String SENSITIVE_CIPHERTEXT = "v1:AAAAdeadbeef";

    @Test
    void toStringOmitsAllPersonalData() {
        Classroom classroom = Classroom.independent(42L, 7L, "C1", "Class");
        ClassroomRosterEntry entry = new ClassroomRosterEntry(
                classroom, SENSITIVE_EMAIL, SENSITIVE_NAME, 9L);
        // Populate every sensitive field with recognizable marker values.
        entry.setStudentNumber("HS-SECRET");
        entry.setGender("FEMALE");
        entry.setDobEncrypted(SENSITIVE_CIPHERTEXT);
        entry.setPhoneEncrypted(SENSITIVE_CIPHERTEXT);
        entry.setParentPhoneEncrypted(SENSITIVE_CIPHERTEXT);
        entry.setEmailEncrypted(SENSITIVE_CIPHERTEXT);
        entry.setFullName("Another Secret Name");

        String repr = entry.toString();

        // No PII of any kind.
        assertThat(repr).doesNotContain(SENSITIVE_NAME);
        assertThat(repr).doesNotContain("Another Secret Name");
        assertThat(repr).doesNotContain(SENSITIVE_EMAIL);
        assertThat(repr).doesNotContain("sensitive.kid");
        assertThat(repr).doesNotContain("example.com");
        assertThat(repr).doesNotContain("HS-SECRET");
        assertThat(repr).doesNotContain("FEMALE");
        assertThat(repr).doesNotContain(SENSITIVE_CIPHERTEXT);
        assertThat(repr).doesNotContain("v1:");

        // Technical identifiers and state remain for debugging.
        assertThat(repr).contains("ClassroomRosterEntry");
        assertThat(repr).contains("id=");
        assertThat(repr).contains("classroomId=");
        assertThat(repr).contains("status=");
        assertThat(repr).contains("PENDING");
    }

    @Test
    void linkedAndDeclinedStatesStayDebuggableWithoutPii() {
        Classroom classroom = Classroom.independent(1L, 2L, "C2", "Class");
        ClassroomRosterEntry entry = new ClassroomRosterEntry(
                classroom, "link-me@example.com", "Link Me", 3L);
        entry.linkTo(77L, java.time.Instant.parse("2026-01-01T00:00:00Z"));

        String repr = entry.toString();
        assertThat(repr).contains("LINKED");
        assertThat(repr).contains("linkedAt=2026-01-01T00:00:00Z");
        assertThat(repr).doesNotContain("Link Me");
        assertThat(repr).doesNotContain("link-me@example.com");
    }
}
