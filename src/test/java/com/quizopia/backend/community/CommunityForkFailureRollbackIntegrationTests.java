package com.quizopia.backend.community;

import com.quizopia.backend.community.application.CommunityForkService;
import com.quizopia.backend.question.application.QuestionCommunityPort;
import com.quizopia.backend.question.application.QuestionCommunityPortImpl;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Injected-failure rollback for the #47 fork (ADR-006 §8): a stub
 * {@link QuestionCommunityPort} performs the REAL clone and then throws
 * between the clone and the DRAFT creation steps — the single fork
 * transaction must roll back EVERYTHING (bank, questions, versions,
 * options, exam, sections, composition, options, ledger row), leaving
 * zero orphan rows. Deliberately NOT {@code @Transactional} so the
 * service transaction really commits/rolls back; a dedicated context
 * ({@code @DirtiesContext}) isolates the @Primary stub.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class,
        CommunityForkFailureRollbackIntegrationTests.ThrowAfterCloneConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommunityForkFailureRollbackIntegrationTests {

    @TestConfiguration
    static class ThrowAfterCloneConfig {
        /** Real clone, then a failure exactly BETWEEN clone and draft creation (§8 steps 6/7). */
        @org.springframework.context.annotation.Bean
        @Primary
        QuestionCommunityPort throwAfterClone(QuestionCommunityPortImpl real) {
            return (snapshot, ownerUserId) -> {
                real.cloneForFork(snapshot, ownerUserId);
                throw new IllegalStateException("injected failure between clone and draft");
            };
        }
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CommunityForkService forkService;

    private long publisherId;
    private long forkerId;
    private long publicationId;

    @BeforeEach
    void setUp() {
        publisherId = newUser("frbt");
        forkerId = newUser("frbf");
        long sourceExamId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'FRBEX%s', 'Rollback source exam', 'PUBLISHED', now(), 1)
                """.formatted(publisherId, System.nanoTime()));
        long bankId = insert("""
                INSERT INTO question_banks (created_by_user_id, code, name, status)
                VALUES (%d, 'FRBKB%s', 'Rollback source bank', 'ACTIVE')
                """.formatted(publisherId, System.nanoTime()));
        long questionId = insert("""
                INSERT INTO questions (question_bank_id, code, status, created_by)
                VALUES (%d, 'FRBQ%s', 'ACTIVE', %d)
                """.formatted(bankId, System.nanoTime(), publisherId));
        long versionId = insert("""
                INSERT INTO question_versions (question_id, version_number, question_type, content,
                                               difficulty, default_points, created_by)
                VALUES (%d, 1, 'SINGLE_CHOICE', 'Rollback source content', 'EASY', 1, %d)
                """.formatted(questionId, publisherId));
        for (int i = 0; i < 4; i++) {
            jdbc.update("""
                    INSERT INTO question_options (question_version_id, option_key, content,
                                                  is_correct, position)
                    VALUES (?, ?, ?, ?, ?)
                    """, versionId, String.valueOf((char) ('A' + i)), "opt " + i, i == 0, i);
        }
        long sectionId = insert("""
                INSERT INTO exam_sections (exam_id, title, position)
                VALUES (%d, 'Rollback section', 0)
                """.formatted(sourceExamId));
        long eqId = insert("""
                INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id,
                                            source_question_version_id, question_code, question_type,
                                            content, default_points, difficulty, position)
                VALUES (%d, %d, %d, %d, 'FRBEQ1', 'SINGLE_CHOICE', 'Rollback source content',
                        1, 'EASY', 0)
                """.formatted(sourceExamId, sectionId, questionId, versionId));
        jdbc.update("""
                INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct,
                                                   position)
                VALUES (?, 'A', 'opt 0', true, 0)
                """, eqId);
        publicationId = insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, reuse_policy,
                                                    published_at)
                VALUES (%d, %d, 'Rollback publication', 'Frb-Alias', 'PUBLISHED', 'VISIBLE',
                        'ALLOW_FORK', now())
                """.formatted(sourceExamId, publisherId));
    }

    @Test
    void injectedFailureBetweenCloneAndDraftLeavesZeroOrphanRows() {
        assertThatThrownBy(() -> forkService.fork(forkerId, publicationId, "rollback-key-01"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected failure");

        // Zero orphan rows across EVERY table the fork touches.
        assertThat(count("SELECT count(*) FROM question_banks WHERE created_by_user_id = ?",
                "question_banks (forker's cloned bank)")).isZero();
        assertThat(count("""
                        SELECT count(*) FROM questions q JOIN question_banks b ON b.id = q.question_bank_id
                        WHERE b.created_by_user_id = ?
                        """, "questions")).isZero();
        assertThat(count("""
                        SELECT count(*) FROM question_versions v WHERE v.created_by = ?
                        """, "question_versions")).isZero();
        assertThat(count("""
                        SELECT count(*) FROM question_options o
                        JOIN question_versions v ON v.id = o.question_version_id
                        WHERE v.created_by = ?
                        """, "question_options")).isZero();
        assertThat(count("SELECT count(*) FROM exams WHERE created_by_user_id = ?",
                "exams (fork DRAFT)")).isZero();
        assertThat(count("""
                        SELECT count(*) FROM exam_sections s
                        JOIN exams e ON e.id = s.exam_id WHERE e.created_by_user_id = ?
                        """, "exam_sections")).isZero();
        assertThat(count("""
                        SELECT count(*) FROM exam_questions q
                        JOIN exams e ON e.id = q.exam_id WHERE e.created_by_user_id = ?
                        """, "exam_questions")).isZero();
        assertThat(count("""
                        SELECT count(*) FROM exam_question_options o
                        JOIN exam_questions q ON q.id = o.exam_question_id
                        JOIN exams e ON e.id = q.exam_id WHERE e.created_by_user_id = ?
                        """, "exam_question_options")).isZero();
        assertThat(count("SELECT count(*) FROM community_fork_records WHERE actor_user_id = ?",
                "community_fork_records")).isZero();

        // And the source rows are still exactly the fixture.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM question_banks WHERE created_by_user_id = ?",
                Integer.class, publisherId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM exams WHERE created_by_user_id = ?",
                Integer.class, publisherId)).isEqualTo(1);
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private int count(String sql, String what) {
        return jdbc.queryForObject(sql, Integer.class, forkerId);
    }

    private long newUser(String prefix) {
        long userId = insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'FR', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, System.nanoTime(), prefix, System.nanoTime()));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'TEACHER'",
                userId);
        return userId;
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
