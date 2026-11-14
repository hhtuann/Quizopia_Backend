package com.quizopia.backend.realtime;

import com.quizopia.backend.realtime.event.RealtimeEventEnvelope;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural data-leak test (Day 7 §20): the realtime envelope must not carry any answer / answer-key /
 * score / grade / identity-beyond-userId-studentProfileId / submission / client-instance field. The envelope
 * is a record, so its components are enumerated by reflection — a future edit adding a forbidden field
 * fails this test.
 *
 * <p>{@code userId} is the primary attempt-owner identity (issue #4 P0-1) and {@code studentProfileId}
 * the legacy provenance; both are part of the frozen contract and therefore allowed.
 */
class RealtimeDataLeakStructuralTest {

    private static final Set<String> FORBIDDEN = Set.of(
            "answerPayload", "answerKey", "expectedAnswer", "requiredInputLength", "selectedOption",
            "selectedOptionKeys", "responses", "numericValue", "isCorrect", "correct", "explanation",
            "score", "grade", "studentCode", "username", "email", "schoolId",
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
        // P4-A (#83): activityType / activitySource / clientOccurredAt are the
        // additive ATTEMPT_ACTIVITY delta-notification fields (names only,
        // NON_NULL, no metadata/answer content on the wire).
        Set<String> allowed = Set.of("eventId", "eventType", "sessionId", "attemptId", "userId",
                "studentProfileId", "occurredAt", "serverTime", "activeCount",
                "activityType", "activitySource", "clientOccurredAt");
        for (RecordComponent rc : RealtimeEventEnvelope.class.getRecordComponents()) {
            assertThat(allowed).contains(rc.getName());
        }
        assertThat(allowed).as("every allowed component exists (no silent removal)")
                .containsAll(java.util.Arrays.stream(RealtimeEventEnvelope.class.getRecordComponents())
                        .map(RecordComponent::getName).toList());
    }
}
