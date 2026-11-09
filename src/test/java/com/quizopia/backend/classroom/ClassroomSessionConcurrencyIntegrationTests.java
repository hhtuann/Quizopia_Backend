package com.quizopia.backend.classroom;

import com.quizopia.backend.exam.application.ClassroomSessionService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLASS-M2 (issue #52): assignment concurrency invariants. NOT transactional:
 * workers run in their own committed transactions against the shared
 * container; fixtures are tag-scoped and cleaned in {@link #afterEach()}.
 *
 * <ul>
 *   <li>Concurrent assignments of the same session to DIFFERENT classrooms
 *       both survive (additive interface — no replace-set clobbering).</li>
 *   <li>Concurrent duplicate assignment of the same (class, session) pair
 *       converges to exactly one association (ON CONFLICT DO NOTHING).</li>
 *   <li>A racing assign/unassign of the same pair never corrupts the table —
 *       the final state is exactly 0 or 1 association.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({ PostgresTestContainerConfiguration.class, TestClockConfig.class })
class ClassroomSessionConcurrencyIntegrationTests {

    @Autowired
    private ClassroomSessionService classroomSessionService;
    @Autowired
    private JdbcTemplate jdbc;

    private String tag;
    private long teacherId;
    private long sessionId;
    private long classA1;
    private long classA2;
    private long subjectId;
    private long gradeLevelId;

    @BeforeEach
    void setUp() {
        tag = "sc" + java.util.UUID.randomUUID().toString().substring(0, 8);
        teacherId = insertUser("tch" + tag, "Teacher " + tag);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT " + teacherId
                + ", id FROM roles WHERE code='TEACHER'");
        gradeLevelId = insert("INSERT INTO grade_levels (code, name) VALUES ('" + tag + "','G')");
        subjectId = insert("INSERT INTO subjects (grade_level_id, code, name) VALUES ("
                + gradeLevelId + ",'" + tag + "S','S')");
        long exam = insert("INSERT INTO exams (created_by_user_id, subject_id, code, title, status, "
                + "published_at, total_points) VALUES (" + teacherId + "," + subjectId + ",'" + tag
                + "','E','PUBLISHED',now(),1)");
        sessionId = insert("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, "
                + "ends_at, max_attempts, created_by, visibility, duration_minutes) VALUES ("
                + exam + ",'" + tag + "','S','SCHEDULED', now() - interval '2 hours', "
                + "now() + interval '2 days', 1," + teacherId + ",'CLASS_RESTRICTED',30)");
        classA1 = insert("INSERT INTO classrooms (owner_user_id, code, name) VALUES ("
                + teacherId + ",'" + tag + "1','C1')");
        classA2 = insert("INSERT INTO classrooms (owner_user_id, code, name) VALUES ("
                + teacherId + ",'" + tag + "2','C2')");
    }

    @AfterEach
    void afterEach() {
        // FK-safe order; every row this test created is tag-scoped.
        jdbc.update("DELETE FROM exam_session_classes WHERE exam_session_id=" + sessionId);
        jdbc.update("DELETE FROM exam_sessions WHERE id=" + sessionId);
        jdbc.update("DELETE FROM exams WHERE subject_id=" + subjectId);
        jdbc.update("DELETE FROM subjects WHERE id=" + subjectId);
        jdbc.update("DELETE FROM grade_levels WHERE id=" + gradeLevelId);
        jdbc.update("DELETE FROM classrooms WHERE id IN (" + classA1 + "," + classA2 + ")");
        jdbc.update("DELETE FROM user_roles WHERE user_id=" + teacherId);
        jdbc.update("DELETE FROM users WHERE id=" + teacherId);
    }

    @Test
    void concurrentAssignmentsToDifferentClassroomsBothSurvive() throws Exception {
        runConcurrently(
                () -> {
                    classroomSessionService.assignSessionToClassroom(teacherId, classA1, sessionId);
                    return null;
                },
                () -> {
                    classroomSessionService.assignSessionToClassroom(teacherId, classA2, sessionId);
                    return null;
                });

        assertThat(associationsFor(sessionId))
                .as("additive interface: both classroom associations survive")
                .containsExactlyInAnyOrder(classA1, classA2);
    }

    @Test
    void concurrentDuplicateAssignConvergesToOneAssociation() throws Exception {
        runConcurrently(
                () -> {
                    classroomSessionService.assignSessionToClassroom(teacherId, classA1, sessionId);
                    return null;
                },
                () -> {
                    classroomSessionService.assignSessionToClassroom(teacherId, classA1, sessionId);
                    return null;
                });

        assertThat(associationsFor(sessionId))
                .as("junction composite PK + ON CONFLICT DO NOTHING")
                .containsExactly(classA1);
    }

    @Test
    void racingAssignAndUnassignNeverCorruptsTheAssociation() throws Exception {
        // Review M2-QA7: multiple rounds so BOTH commit orders occur — a
        // single ≤1 assertion could pass even if one worker always lost.
        boolean sawAssigned = false;
        boolean sawUnassigned = false;
        for (int round = 0; round < 12; round++) {
            runConcurrently(
                    () -> {
                        classroomSessionService.assignSessionToClassroom(teacherId, classA1, sessionId);
                        return null;
                    },
                    () -> {
                        classroomSessionService.unassignSessionFromClassroom(teacherId, classA1, sessionId);
                        return null;
                    });

            // Whichever commit wins: rows are exactly the pair or empty —
            // never duplicated, never touching the other classroom.
            List<Long> rows = associationsFor(sessionId);
            assertThat(rows.size()).as("round %d: 0 or 1 association", round).isLessThanOrEqualTo(1);
            if (rows.contains(classA1)) {
                sawAssigned = true;
            } else if (rows.isEmpty()) {
                sawUnassigned = true;
            }
            // Reset for the next round (idempotent either way).
            jdbc.update("DELETE FROM exam_session_classes WHERE exam_session_id=" + sessionId
                    + " AND classroom_id=" + classA1);
        }
        assertThat(sawAssigned).as("assign must win at least one round").isTrue();
        assertThat(sawUnassigned).as("unassign must win at least one round").isTrue();
    }

    // ============================================================
    // Harness
    // ============================================================

    private List<Long> associationsFor(long sid) {
        return jdbc.queryForList(
                "SELECT classroom_id FROM exam_session_classes WHERE exam_session_id=" + sid,
                Long.class);
    }

    private void runConcurrently(java.util.concurrent.Callable<?>... tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (java.util.concurrent.Callable<?> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            for (Future<Object> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private long insertUser(String username, String displayName) {
        return insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('" + username + "','" + username + "@t.com','h','" + displayName + "')");
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
