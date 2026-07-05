package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.dto.AttemptDetailResponse;
import com.hhtuann.backend.attempt.dto.AttemptDetailResponse.OptionView;
import com.hhtuann.backend.attempt.dto.AttemptDetailResponse.QuestionView;
import com.hhtuann.backend.attempt.dto.AttemptDetailResponse.SavedAnswerView;
import com.hhtuann.backend.attempt.dto.AvailableSessionsResponse;
import com.hhtuann.backend.attempt.dto.AvailableSessionsResponse.AvailableSessionItem;
import com.hhtuann.backend.attempt.dto.MyAttemptsResponse;
import com.hhtuann.backend.attempt.dto.MyAttemptsResponse.MyAttemptListItem;
import com.hhtuann.backend.attempt.dto.SaveAnswerResponse;
import com.hhtuann.backend.attempt.dto.StartAttemptResponse;
import com.hhtuann.backend.attempt.dto.SubmitResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural data-leak test (A3.2-2): asserts by reflection that no student-facing attempt DTO
 * (detail, my, start, available — top-level + nested records) declares a component whose name could
 * carry answer/grade/identity/internal data. This complements the per-response JSON assertions and
 * catches a leak even if a future edit adds a forbidden field.
 *
 * <p>Plain unit test (no Spring context / DB) — fast and deterministic.
 */
class AttemptDataLeakStructuralTest {

    /** Forbidden component names on any student-facing attempt DTO (answer key, grading internals, identity, internal).
     *  Day 8: "score" (student's own grade summary) and "correct" (per-question pass/fail) are legitimate. */
    private static final Set<String> FORBIDDEN = Set.of(
            "answerKey", "expectedAnswer", "requiredInputLength", "isCorrect",
            "explanation", "grade", "gradingDetails", "submissionIdempotencyKey",
            "studentProfileId", "schoolId", "clientInstanceId", "userId");

    private static final List<Class<?>> STUDENT_DTOS = List.of(
            AttemptDetailResponse.class, QuestionView.class, OptionView.class, SavedAnswerView.class,
            MyAttemptsResponse.class, MyAttemptListItem.class,
            SaveAnswerResponse.class,
            SubmitResponse.class,
            StartAttemptResponse.class, StartAttemptResponse.QuestionView.class,
            StartAttemptResponse.QuestionView.OptionView.class,
            AvailableSessionsResponse.class, AvailableSessionItem.class, AvailableSessionItem.ExamInfo.class);

    @Test
    void noStudentDtoDeclaresForbiddenField() {
        for (Class<?> dto : STUDENT_DTOS) {
            assertThat(dto.isRecord()).as("%s is a record", dto.getSimpleName()).isTrue();
            for (RecordComponent rc : dto.getRecordComponents()) {
                assertThat(FORBIDDEN)
                        .as("%s.%s must not be a student-facing field", dto.getSimpleName(), rc.getName())
                        .doesNotContain(rc.getName());
            }
        }
    }
}
