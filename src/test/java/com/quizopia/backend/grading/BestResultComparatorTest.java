package com.quizopia.backend.grading;

import com.quizopia.backend.grading.domain.BestCandidate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 8 §4.1 — BEST-result comparator unit tests. Proves the deterministic 4-level ordering:
 * percentage DESC → score DESC → submittedAt ASC → attemptId ASC. Pure (no Spring); uses compareTo.
 */
@SuppressWarnings({"null"})
class BestResultComparatorTest {

    private static final Instant T1 = Instant.parse("2026-07-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-07-01T11:00:00Z");

    private record C(long id, String pct, String score, Instant submitted) {
        BestCandidate toCandidate() { return new BestCandidate(id, pct != null ? new BigDecimal(pct) : null, new BigDecimal(score), submitted); }
    }

    private BestCandidate best(C... candidates) {
        return List.of(candidates).stream()
                .map(C::toCandidate)
                .min(BestResultComparator.INSTANCE) // min = best (sorts first)
                .orElseThrow();
    }

    @Test void higherPercentageWins() {
        assertThat(best(new C(1, "80", "8", T1), new C(2, "90", "9", T2)).attemptId()).isEqualTo(2L);
    }

    @Test void lowerLaterAttemptDoesNotReplaceBest() {
        assertThat(best(new C(1, "90", "9", T1), new C(2, "50", "5", T2)).attemptId()).isEqualTo(1L);
    }

    @Test void equalPercentageHigherScoreWins() {
        assertThat(best(new C(1, "80", "4", T1), new C(2, "80", "8", T2)).attemptId()).isEqualTo(2L);
    }

    @Test void equalPercentageAndScoreEarlierSubmittedWins() {
        assertThat(best(new C(1, "80", "8", T1), new C(2, "80", "8", T2)).attemptId()).isEqualTo(1L);
    }

    @Test void fullTieLowerAttemptIdWins() {
        assertThat(best(new C(5, "80", "8", T1), new C(3, "80", "8", T1)).attemptId()).isEqualTo(3L);
    }

    @Test void compareToBasedPercentageEquality() {
        // 80.00 vs 80.0000 — compareTo treats them equal; falls through to score tie-break.
        assertThat(best(
                new C(1, "80.00", "8", T1),
                new C(2, "80.0000", "9", T2)).attemptId()).isEqualTo(2L);
    }

    @Test void nullPercentageSortsLast() {
        assertThat(best(new C(1, null, "9", T1), new C(2, "10", "1", T2)).attemptId()).isEqualTo(2L);
    }

    @Test void threeWayOrdering() {
        // Best: highest percentage; among equal: higher score; among equal: earlier submission.
        BestCandidate top = best(
                new C(10, "50", "5", T1),   // 50%
                new C(20, "90", "9", T2),   // 90% — best
                new C(30, "90", "9", T1));  // 90% but earlier → should be 30 not 20? No: 90%==90%, 9==9, T1<T2 → 30 wins
        assertThat(top.attemptId()).isEqualTo(30L);
    }
}
