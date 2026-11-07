package com.quizopia.backend.exam.application;

import com.quizopia.backend.question.domain.model.QuestionDifficulty;
import com.quizopia.backend.question.domain.model.QuestionType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The #47 snapshot records carry answer material ({@code answerKey},
 * {@code isCorrect}) and are contractually never serialized or logged
 * (ADR-006 §6). Their {@code toString} must mirror the
 * {@code ExamQuestion}/{@code ExamQuestionOption} convention —
 * {@code answerKey} rendered as {@code <redacted>}, {@code isCorrect}
 * omitted — so an accidental log of a poisoned snapshot leaks nothing.
 */
class ExamCommunityPortSnapshotToStringRedactionTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Unique poison marker planted inside the answer key JSON. */
    private static final String ANSWER_POISON = "zq7-answer-leak";

    @Test
    void toStringOfPoisonedSnapshotContainsNoAnswerMarkers() {
        ExamCommunityPort.SnapshotOption correctOption =
                new ExamCommunityPort.SnapshotOption("A", "opt A", true, 0);
        ExamCommunityPort.SnapshotOption wrongOption =
                new ExamCommunityPort.SnapshotOption("B", "opt B", false, 1);
        ExamCommunityPort.SnapshotQuestion question = new ExamCommunityPort.SnapshotQuestion(
                0, QuestionType.SINGLE_CHOICE, "poisoned stem", BigDecimal.ONE,
                QuestionDifficulty.EASY, "explanation",
                json("{\"" + ANSWER_POISON + "\":[\"A\"]}"), json("{\"origin\":\"test\"}"),
                42L, 43L, List.of(correctOption, wrongOption));
        ExamCommunityPort.SnapshotSection section = new ExamCommunityPort.SnapshotSection(
                0, "section", "section instructions", "S1", List.of(question));
        ExamCommunityPort.PublishedCompositionSnapshot snapshot =
                new ExamCommunityPort.PublishedCompositionSnapshot(
                        json("{\"0\":0,\"1\":10}"), "exam instructions", 30, 7L, 8L,
                        "poisoned title", "description", List.of(section));

        // The answerKey value never renders, at any nesting depth.
        assertThat(snapshot.toString()).doesNotContain(ANSWER_POISON);
        assertThat(question.toString()).doesNotContain(ANSWER_POISON);
        assertThat(question.toString()).contains("answerKey=<redacted>");

        // isCorrect never renders: no boolean answer marker anywhere.
        assertThat(snapshot.toString())
                .doesNotContain("isCorrect")
                .doesNotContain("true")
                .doesNotContain("false");
        assertThat(correctOption.toString())
                .doesNotContain("isCorrect")
                .doesNotContain("true")
                .doesNotContain("false");
        assertThat(wrongOption.toString()).doesNotContain("false");

        // Non-answer fields stay for debuggability.
        assertThat(snapshot.toString()).contains("poisoned title").contains("S1");
        assertThat(question.toString()).contains("poisoned stem").contains("42");
        assertThat(correctOption.toString()).contains("optionKey=A").contains("opt A");
    }

    private static JsonNode json(String json) {
        return MAPPER.readTree(json);
    }
}
