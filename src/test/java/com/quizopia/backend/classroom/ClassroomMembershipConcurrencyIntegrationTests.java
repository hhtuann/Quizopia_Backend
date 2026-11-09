package com.quizopia.backend.classroom;

import com.quizopia.backend.classroom.application.ClassroomMembershipService;
import com.quizopia.backend.classroom.application.RosterReconciliationService;
import com.quizopia.backend.classroom.exception.ClassroomException;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLASS-M1 (issue #50): leave-race invariants. NOT transactional: the
 * worker threads must run in their own committed transactions against the
 * shared container, so fixtures are cleaned up in {@link #afterEach()} by
 * the per-test {@code tag}.
 *
 * <ul>
 *   <li>Concurrent double-leave: exactly ONE winner; final state has no
 *       membership and exactly ONE DECLINED opt-out row (direct-add path).</li>
 *   <li>Leave racing verified-email reconciliation: whatever the interleaving,
 *       a committed opt-out can never end up re-added automatically — the
 *       final state is always "no membership, no live LINKED entry".</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({ PostgresTestContainerConfiguration.class, TestClockConfig.class })
class ClassroomMembershipConcurrencyIntegrationTests {

    @Autowired
    private ClassroomMembershipService membershipService;
    @Autowired
    private com.quizopia.backend.classroom.application.ClassroomService classroomService;
    @Autowired
    private RosterReconciliationService reconciliationService;
    @Autowired
    private JdbcTemplate jdbc;

    private String tag;
    private long teacherId;
    private long learnerId;
    private long classroomId;
    private final List<Long> users = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tag = "cc" + java.util.UUID.randomUUID().toString().substring(0, 8);
        teacherId = insertUser("tch" + tag, "Teacher " + tag, "TEACHER", false);
        learnerId = insertUser("lrn" + tag, "Learner " + tag, "STUDENT", false);
        users.add(teacherId);
        users.add(learnerId);
        classroomId = insert("INSERT INTO classrooms (owner_user_id, code, name) VALUES ("
                + teacherId + ",'" + tag + "','CC " + tag + "') RETURNING id");
    }

    @AfterEach
    void afterEach() {
        // FK-safe order; every row this test created is tag-scoped. The
        // reconcile path COMMITS notifications rows on the worker thread
        // (no outer tx there), so notifications must go FIRST — deleting
        // users before notifications violates fk_notifications_user.
        jdbc.update("DELETE FROM notifications WHERE user_id IN ("
                + users.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")) + ")");
        jdbc.update("DELETE FROM classroom_roster_entries WHERE classroom_id=" + classroomId);
        jdbc.update("DELETE FROM classroom_members WHERE classroom_id=" + classroomId);
        jdbc.update("DELETE FROM classrooms WHERE id=" + classroomId);
        for (Long userId : users) {
            jdbc.update("DELETE FROM user_roles WHERE user_id=" + userId);
            jdbc.update("DELETE FROM users WHERE id=" + userId);
        }
    }

    @Test
    void concurrentDoubleLeaveHasExactlyOneWinnerAndOneOptOutRow() throws Exception {
        jdbc.update("INSERT INTO classroom_members (classroom_id, user_id) VALUES (" + classroomId + "," + learnerId + ")");
        int successes = runConcurrently(2,
                () -> {
                    membershipService.leaveClassroom(learnerId, classroomId);
                    return null;
                });

        assertThat(successes).as("exactly one of the two concurrent leaves wins").isEqualTo(1);
        assertThat(membershipCount()).isZero();
        // Direct-add path: exactly one synthetic DECLINED opt-out row.
        assertThat(rosterRows("DECLINED")).isEqualTo(1);
    }

    @Test
    void leaveRacingReconciliationNeverLeavesAnAutoRelink() throws Exception {
        String email = "lrn" + tag + "@t.com";
        // PENDING invitation exists; learner is ALSO a direct-add member.
        jdbc.update("INSERT INTO classroom_roster_entries (classroom_id, email_normalized, status, "
                + "full_name, created_by_user_id) VALUES (" + classroomId + ",'" + email
                + "','PENDING','Learner " + tag + "'," + teacherId + ")");
        jdbc.update("INSERT INTO classroom_members (classroom_id, user_id) VALUES (" + classroomId + "," + learnerId + ")");

        // Learner's email becomes verified (reconciliation input), racing the leave.
        jdbc.update("UPDATE users SET email_verified_at=now() WHERE id=" + learnerId);
        runConcurrently(2,
                () -> {
                    membershipService.leaveClassroom(learnerId, classroomId);
                    return null;
                },
                () -> {
                    reconciliationService.reconcile(learnerId, email);
                    return null;
                });

        // Whatever the interleaving: no membership survives the committed
        // opt-out, and no live LINKED roster row remains.
        assertThat(membershipCount())
                .as("a committed opt-out must never be auto-relinked").isZero();
        assertThat(rosterRows("LINKED"))
                .as("no stale LINKED entry may survive leave").isZero();
    }

    @Test
    void leaveRacingDirectReAddNeverBypassesTheOptOut() throws Exception {
        jdbc.update("INSERT INTO classroom_members (classroom_id, user_id) VALUES (" + classroomId + "," + learnerId + ")");

        // Final-M1 gate F1: whichever commits first, the committed opt-out
        // can never be bypassed — final state is ALWAYS "no membership +
        // standing DECLINED" (addMembers takes the same user-row lock as
        // leave, so the DECLINED check and the insert serialize with it).
        runConcurrently(2,
                () -> {
                    membershipService.leaveClassroom(learnerId, classroomId);
                    return null;
                },
                () -> {
                    classroomService.addMembers(teacherId, classroomId,
                            new com.quizopia.backend.classroom.dto.AddMembersRequest(
                                    java.util.List.of(learnerId)));
                    return null;
                });

        assertThat(membershipCount())
                .as("opt-out must survive a racing direct re-add").isZero();
        assertThat(rosterRows("DECLINED"))
                .as("the opt-out record must exist").isEqualTo(1);
    }

    // ============================================================
    // Harness
    // ============================================================

    /** Runs the callables concurrently (real transactions) and returns the
     *  number that completed WITHOUT throwing. */
    private int runConcurrently(int threads, Callable<?>... tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (ClassroomException expectedRace) {
                        return "lost-race";
                    }
                }));
            }
            start.countDown();
            int successes = 0;
            for (Future<Object> future : futures) {
                Object outcome = future.get(30, TimeUnit.SECONDS);
                if (!"lost-race".equals(outcome)) {
                    successes++;
                }
            }
            return successes;
        } finally {
            pool.shutdownNow();
        }
    }

    private int membershipCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM classroom_members WHERE classroom_id=" + classroomId
                        + " AND user_id=" + learnerId, Integer.class);
        return count == null ? 0 : count;
    }

    private int rosterRows(String status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM classroom_roster_entries WHERE classroom_id=" + classroomId
                        + " AND status='" + status + "'", Integer.class);
        return count == null ? 0 : count;
    }

    private long insertUser(String username, String displayName, String role, boolean verified) {
        long id = insert("INSERT INTO users (username, email, password_hash, display_name"
                + (verified ? ", email_verified_at" : "") + ") VALUES ('" + username + "','"
                + username + "@t.com','h','" + displayName + "'"
                + (verified ? ",now()" : "") + ") RETURNING id");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT " + id
                + ", id FROM roles WHERE code='" + role + "'");
        return id;
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
