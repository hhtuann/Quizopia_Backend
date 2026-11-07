package com.quizopia.backend.exam.application;

import com.quizopia.backend.exam.domain.model.ExamStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Read-only {@link ExamCommunityPort} implementation (ADR-006 §6): one
 * projection query over the exam row plus its composition counts. Never
 * writes, never locks, never exposes composition content — only counts.
 */
@Component
public class ExamCommunityPortImpl implements ExamCommunityPort {

    private final JdbcTemplate jdbc;

    public ExamCommunityPortImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PublicationEligibility> checkPublicationEligibility(Long examId, Long userId) {
        List<PublicationEligibility> rows = jdbc.query("""
                        SELECT e.created_by_user_id AS exam_owner_id,
                               e.status             AS exam_status,
                               COALESCE(e.duration_minutes, 0) AS duration_minutes,
                               (SELECT count(*) FROM exam_sections s WHERE s.exam_id = e.id)  AS section_count,
                               (SELECT count(*) FROM exam_questions q WHERE q.exam_id = e.id) AS question_count
                        FROM exams e
                        WHERE e.id = ? AND e.created_by_user_id = ?
                        """,
                (rs, i) -> new PublicationEligibility(
                        rs.getLong("exam_owner_id"),
                        ExamStatus.valueOf(rs.getString("exam_status")),
                        rs.getInt("duration_minutes"),
                        rs.getInt("section_count"),
                        rs.getInt("question_count")),
                examId, userId);
        return rows.stream().findFirst();
    }
}
