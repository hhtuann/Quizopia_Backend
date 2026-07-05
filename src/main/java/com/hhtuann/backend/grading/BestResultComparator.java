package com.hhtuann.backend.grading;

import com.hhtuann.backend.grading.domain.BestCandidate;

import java.math.BigDecimal;
import java.util.Comparator;

/**
 * Day 8 BEST-result comparator — deterministic ordering for selecting the best attempt per
 * (student, exam session). All BigDecimal comparisons use {@code compareTo} (not {@code equals}).
 *
 * <p>Order (the "best" candidate sorts FIRST):
 * <ol>
 *   <li>percentage DESC (higher percentage wins);</li>
 *   <li>score DESC (higher raw/final score wins);</li>
 *   <li>submittedAt ASC (earlier submission wins);</li>
 *   <li>attemptId ASC (lower stable id wins — deterministic final tie-break).</li>
 * </ol>
 *
 * <p>Null percentage/score sort LAST (treated as worst). This comparator never returns 0 for
 * distinct candidates (the attemptId tie-break is unique), so it is a total order.
 */
public final class BestResultComparator implements Comparator<BestCandidate> {

    public static final BestResultComparator INSTANCE = new BestResultComparator();

    private BestResultComparator() {
    }

    @Override
    public int compare(BestCandidate a, BestCandidate b) {
        // 1. percentage DESC — nulls last.
        int byPercentage = nullsLastDesc(a.percentage(), b.percentage());
        if (byPercentage != 0) return byPercentage;
        // 2. score DESC — nulls last.
        int byScore = nullsLastDesc(a.score(), b.score());
        if (byScore != 0) return byScore;
        // 3. submittedAt ASC — nulls last.
        int bySubmitted = nullsLastAsc(a.submittedAt(), b.submittedAt());
        if (bySubmitted != 0) return bySubmitted;
        // 4. attemptId ASC — deterministic final tie-break (non-null for valid candidates).
        return a.attemptId().compareTo(b.attemptId());
    }

    private static int nullsLastDesc(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;  // a is "less" (sorts after)
        if (b == null) return -1; // b is "less" (sorts after)
        return b.compareTo(a);    // DESC: higher first
    }

    private static <T extends Comparable<T>> int nullsLastAsc(T a, T b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return a.compareTo(b); // ASC: earlier first
    }
}
