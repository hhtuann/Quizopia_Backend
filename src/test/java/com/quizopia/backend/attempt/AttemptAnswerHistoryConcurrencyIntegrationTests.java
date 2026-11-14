package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.application.AttemptAutosaveService;
import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.application.AttemptSubmitService;
import com.quizopia.backend.attempt.dto.SaveAnswerRequest;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4-A3 (issue #82) — concurrency: the accepted-answer history stays exactly
 * one-row-per-accepted-sequence under true parallel autosaves, and stays
 * CONSISTENT with the answer table when submit races autosave. Lock order
 * (attempt row only) is preserved by the unchanged service code — this test
 * proves the two new accepted-path writes cannot duplicate or diverge.
 *
 * <p>{@code NOT_SUPPORTED} + real production service calls from multiple
 * threads (house concurrency-test reliability contract: latches for
 * rendezvous, cleanup in finally).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttemptAnswerHistoryConcurrencyIntegrationTests {

    @Autowired private AttemptAutosaveService autosaveService;
    @Autowired private AttemptSubmitService submitService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private PlatformTransactionManager txm;
    @Autowired private ObjectMapper objectMapper;
    private long userId;
    private long attemptId;
    private long aqId;
    private long glId;
    private long subjId;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-11-14T08:00:00Z");
        clock.setInstant(now);
        String tag = "ahc" + System.nanoTime() % 1_000_000;
        userId = newUser(tag);
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + userId + "," + roleId + ")");
        glId = ins("INSERT INTO grade_levels (code, name) VALUES ('GL" + tag + "','G')");
        subjId = ins("INSERT INTO subjects (grade_level_id, code, name) VALUES (" + glId + ",'" + tag + "M','M')");
        long bank = ins("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES (" + userId + "," + subjId + ",'B','Bank')");
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q'," + userId + ")");
        long qv = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + userId + ")");
        long exam = ins("INSERT INTO exams (created_by_user_id, subject_id, code, title, status, published_at, total_points) VALUES (" + userId + "," + subjId + ",'E','E','PUBLISHED',now(),1)");
        long sec = ins("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + exam + ",'S',0)");
        long eq = ins("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + exam + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
        long session = ins("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at, duration_minutes) VALUES (" + exam + ",'S','S','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + userId + ",'" + now.minusSeconds(3600) + "',60)");
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) VALUES (" + userId + "," + session + "," + userId + ")");
        attemptId = attemptService.startAttempt(userId, session, new StartAttemptRequest(null)).attemptId();
        aqId = jdbc.queryForObject("SELECT id FROM attempt_questions WHERE attempt_id=" + attemptId + " AND exam_question_id=" + eq, Long.class);
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
        new TransactionTemplate(txm).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM attempt_activity_events WHERE attempt_id = ?", attemptId);
            jdbc.update("DELETE FROM attempt_answer_history WHERE attempt_id = ?", attemptId);
            jdbc.update("DELETE FROM attempt_answers WHERE attempt_id = ?", attemptId);
            jdbc.update("DELETE FROM idempotency_records WHERE attempt_id = ?", attemptId);
            jdbc.update("DELETE FROM grade_items WHERE grade_id IN (SELECT id FROM grades WHERE attempt_id = ?)", attemptId);
            jdbc.update("DELETE FROM grades WHERE attempt_id = ?", attemptId);
            jdbc.update("DELETE FROM attempt_questions WHERE attempt_id = ?", attemptId);
            jdbc.update("DELETE FROM attempts WHERE id = ?", attemptId);
            jdbc.update("DELETE FROM exam_session_participants WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM exam_sessions WHERE created_by = ?", userId);
            jdbc.update("DELETE FROM exam_question_options WHERE exam_question_id IN (SELECT id FROM exam_questions WHERE exam_id IN (SELECT id FROM exams WHERE created_by_user_id = ?))", userId);
            jdbc.update("DELETE FROM exam_questions WHERE exam_id IN (SELECT id FROM exams WHERE created_by_user_id = ?)", userId);
            jdbc.update("DELETE FROM exam_sections WHERE exam_id IN (SELECT id FROM exams WHERE created_by_user_id = ?)", userId);
            jdbc.update("DELETE FROM exams WHERE created_by_user_id = ?", userId);
            jdbc.update("DELETE FROM question_versions WHERE question_id IN (SELECT id FROM questions WHERE question_bank_id IN (SELECT id FROM question_banks WHERE created_by_user_id = ?))", userId);
            jdbc.update("DELETE FROM questions WHERE question_bank_id IN (SELECT id FROM question_banks WHERE created_by_user_id = ?)", userId);
            jdbc.update("DELETE FROM question_banks WHERE created_by_user_id = ?", userId);
            jdbc.update("DELETE FROM subjects WHERE id = ?", subjId);
            jdbc.update("DELETE FROM grade_levels WHERE id = ?", glId);
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        });
    }

    @Test
    void concurrentDistinctSequencesYieldExactlyOneHistoryRowEach() throws Exception {
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new java.util.ArrayList<>();
        for (int i = 1; i <= threads; i++) {
            final long seq = i;
            final String key = String.valueOf((char) ('A' + (i % 4)));
            futures.add(pool.submit(() -> {
                start.await();
                return autosaveService.saveAnswer(userId, attemptId, req(key, seq)).accepted();
            }));
        }
        start.countDown();
        int acceptedCount = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(30, TimeUnit.SECONDS)) {
                acceptedCount++;
            }
        }

        // Exactly-one-per-accepted: history rows == accepted saves, all
        // DISTINCT sequences (the UNIQUE (attempt, question, sequence)
        // witness), and the final answer row holds the max accepted sequence
        // (whichever thread's save committed last).
        Integer historyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempt_answer_history WHERE attempt_id=?", Integer.class, attemptId);
        Integer distinctSequences = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT accepted_sequence) FROM attempt_answer_history WHERE attempt_id=?",
                Integer.class, attemptId);
        assertThat(historyRows).isEqualTo(acceptedCount);
        assertThat(distinctSequences).isEqualTo(acceptedCount);
        Long maxAccepted = jdbc.queryForObject(
                "SELECT COALESCE(MAX(accepted_sequence), 0) FROM attempt_answer_history WHERE attempt_id=?",
                Long.class, attemptId);
        Long answerSeq = jdbc.queryForObject(
                "SELECT sequence_number FROM attempt_answers WHERE attempt_id=? AND attempt_question_id=?",
                Long.class, attemptId, aqId);
        assertThat(answerSeq).isEqualTo(maxAccepted);

        // Same count of server ANSWER_ACCEPTED events as history rows.
        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempt_activity_events WHERE attempt_id=? AND event_type='ANSWER_ACCEPTED'",
                Integer.class, attemptId);
        assertThat(events).isEqualTo(acceptedCount);
    }

    @Test
    void historyStaysConsistentWhenSubmitRacesAutosave() throws Exception {
        // One accepted save first (committed baseline).
        assertThat(autosaveService.saveAnswer(userId, attemptId, req("A", 1)).accepted()).isTrue();

        CountDownLatch start = new CountDownLatch(1);
        Future<Object> autosave = pool.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Rejection surfaces as AttemptException (submit won the lock →
            // 409 invalid state); acceptance returns TRUE.
            try {
                return autosaveService.saveAnswer(userId, attemptId, req("C", 2)).accepted();
            } catch (RuntimeException e) {
                return e;
            }
        });
        Future<Object> submit = pool.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            submitService.submitAttempt(userId, attemptId,
                    new com.quizopia.backend.attempt.dto.SubmitRequest("race-submit-key-1"));
            return null;
        });
        start.countDown();
        boolean autosaveAccepted = Boolean.TRUE.equals(autosave.get(30, TimeUnit.SECONDS));
        submit.get(30, TimeUnit.SECONDS);

        // INVARIANT (deterministic under either lock-acquisition order): the
        // set of history sequences equals the set of sequences that actually
        // committed into attempt_answers — history never contains a sequence
        // the answer table rolled back, and never misses an accepted one.
        List<Long> answerSeq = jdbc.queryForList(
                "SELECT sequence_number FROM attempt_answers WHERE attempt_id=? AND attempt_question_id=?",
                Long.class, attemptId, aqId);
        List<Long> historySeq = jdbc.queryForList(
                "SELECT accepted_sequence FROM attempt_answer_history WHERE attempt_id=? AND attempt_question_id=? ORDER BY accepted_sequence",
                Long.class, attemptId, aqId);
        // First save always committed before the race.
        assertThat(historySeq).first().isEqualTo(1L);
        if (autosaveAccepted) {
            assertThat(answerSeq).containsExactly(2L);
            assertThat(historySeq).containsExactly(1L, 2L);
        } else {
            // Submit won the lock; the autosave arrived after submit → 409
            // invalid state (or stale) — no history for seq 2 either way.
            assertThat(historySeq).containsExactly(1L);
        }
        // Submit graded regardless.
        Integer grades = jdbc.queryForObject(
                "SELECT COUNT(*) FROM grades WHERE attempt_id=?", Integer.class, attemptId);
        assertThat(grades).isEqualTo(1);
    }

    // === helpers ===

    private SaveAnswerRequest req(String key, long seq) {
        return new SaveAnswerRequest(aqId, null, single(key), seq, null);
    }

    private JsonNode single(String key) {
        try {
            return objectMapper.readTree("{\"selectedOptionKey\":\"" + key + "\"}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Inserts a user row and returns its id. */
    private long newUser(String tag) {
        return jdbc.queryForObject("INSERT INTO users (username, email, password_hash, display_name) VALUES ('"
                + tag + "','" + tag + "@t.com','h','" + tag + "') RETURNING id", Long.class);
    }

    private long ins(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
