package com.hhtuann.backend.realtime;

import com.hhtuann.backend.realtime.event.RealtimeEventEnvelope;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural data-leak test (Day 7 §20): the realtime envelope must not carry any answer / answer-key /
 * score / grade / identity-beyond-studentProfileId / submission / client-instance field. The envelope
 * is a record, so its components are enumerated by reflection — a future edit adding a forbidden field
 * fails this test.
 */
class RealtimeDataLeakStructuralTest {

    private static final Set<String> FORBIDDEN = Set.of(
            "answerPayload", "answerKey", "expectedAnswer", "requiredInputLength", "selectedOption",
            "selectedOptionKeys", "responses", "numericValue", "isCorrect", "correct", "explanation",
            "score", "grade", "studentCode", "username", "email", "userId", "schoolId",
            "submissionIdempotencyKey", "clientInstanceId");

    @Test
    void envelopeHasNoForbiddenComponent() {
        assertThat(RealtimeEventEnvelope.class.isRecord()).isTrue();
        for (RecordComponent rc : RealtimeEventEnvelope.class.getRecordComponents()) {
            assertThat(FORBIDDEN).as("RealtimeEventEnvelope.%s must not exist", rc.getName())
                    .doesNotContain(rc.getName());
        }
    }

    @Test
    void onlyAllowedComponentsExist() {
        Set<String> allowed = Set.of("eventId", "eventType", "sessionId", "attemptId", "studentProfileId",
                "occurredAt", "serverTime", "activeCount");
        for (RecordComponent rc : RealtimeEventEnvelope.class.getRecordComponents()) {
            assertThat(allowed).contains(rc.getName());
        }
    }
}
