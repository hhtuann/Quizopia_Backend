package com.quizopia.backend.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real STOMP SUBSCRIBE authorization (Day 7 §15/§19, B1R4-A §5/§6, B1R4-A1 §6/§7/§8, B1R4-A2 §4/§5/§6/§8).
 *
 * <p>§4/§5 — personal rejection matrix: every rejected destination yields a captured generic ERROR frame
 * (no leak), zero {@code SERVER_TIME_SYNC} (probe), and zero inbound-accepted SUBSCRIBE
 * ({@link AcceptedSubscriptionProbe}); an aggregate loop proves all ERROR shapes are identical.
 *
 * <p>§6 — teacher topic matrix + spoofed-header non-elevation: a STUDENT with spoofed
 * {@code roles}/{@code username}/{@code permission} headers cannot subscribe to a teacher topic; a valid
 * observer stays connected and receives nothing.
 *
 * <p>§8 — missing-vs-unauthorized indistinguishability: the captured ERROR for nonexistent session,
 * foreign owner, cross-school, missing permission, and missing profile are all the SAME generic message.
 *
 * <p>All connections use {@link StompTestConnection} try-with-resources. No {@code Thread.sleep}.
 */
class WebSocketSubscribeIntegrationTests extends RealtimeStompTestBase {

    private static final String[] PERSONAL_REJECTED = {
            "/user/queue/attempt/extra",
            "/user/queue",
            "/queue/attempt",
            "/user/queue/attempt/",
            "/user//queue/attempt",
            "/user/queue/attempt%2Fextra",
            "/user/0/queue/attempt",
            "/user/queue/attempt/x/y",
    };

    private long studentId;
    private String studentToken;
    private long sessionId;
    private long teacherId;
    private String teacherToken;
    private long schoolId;
    private String foreignTeacherToken;
    private String crossSchoolTeacherToken;
    private String noProfileTeacherToken;

    @BeforeEach
    void setUp() {
        clock.setInstant(Instant.parse("2026-07-04T08:00:00Z"));
        String tag = UUID.randomUUID().toString().substring(0, 6);
        studentId = insertUserWithRole("sub-stu-" + tag, "STUDENT");
        studentToken = accessToken(studentId, "sub-stu", List.of("STUDENT"));
        sessionId = createTeacherOwnedOpenSession("sub-tch-" + tag);
        teacherId = teacherIdForSession(sessionId);
        teacherToken = accessToken(teacherId, "sub-tch", List.of("TEACHER"));
        schoolId = jdbc.queryForObject("SELECT school_id FROM exam_sessions WHERE id=" + sessionId, Long.class);

        long foreign = insertUserWithRole("sub-foreign-" + tag, "TEACHER");
        ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + foreign + "," + schoolId + ",'FT" + tag + "')");
        foreignTeacherToken = accessToken(foreign, "sub-foreign", List.of("TEACHER"));

        long xschool = insertUserWithRole("sub-xschool-" + tag, "TEACHER");
        long school2 = ins("INSERT INTO schools (code, name) VALUES ('XS" + tag + "','X')");
        ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + xschool + "," + school2 + ",'XT" + tag + "')");
        crossSchoolTeacherToken = accessToken(xschool, "sub-xschool", List.of("TEACHER"));

        long noprofile = insertUserWithRole("sub-noprofile-" + tag, "TEACHER");
        noProfileTeacherToken = accessToken(noprofile, "sub-noprofile", List.of("TEACHER"));
    }

    // ============================ §2 carry-over: SERVER_TIME_SYNC basic shape ============================

    @Test
    void personalQueueSubscriptionReceivesExactlyOneServerTimeSync() throws Exception {
        try (StompTestConnection conn = connect(studentToken)) {
            List<String> received = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            conn.subscribe("/user/queue/attempt", stringHandler(received, latch));
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(received).hasSize(1);
            assertThat(received.get(0)).contains("SERVER_TIME_SYNC").contains("serverTime")
                    .doesNotContain("sessionId").doesNotContain("attemptId")
                    .doesNotContain("studentProfileId").doesNotContain("activeCount")
                    .doesNotContain("answerKey").doesNotContain("score");
        }
    }

    @Test
    void connectOnlyReceivesNoServerTimeSync() {
        try (StompTestConnection conn = connect(studentToken)) {
            assertNoOutbound(serverTimeSyncMessage(), 2);
        }
    }

    // ============================ §4/§5 — personal subscribe matrix ============================

    @Test
    void teacherAcceptedOnPersonalQueue() throws Exception {
        try (StompTestConnection conn = connect(teacherToken)) {
            CountDownLatch latch = new CountDownLatch(1);
            conn.subscribe("/user/queue/attempt", noopHandler(latch));
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            awaitAccepted(acceptedDestination("/user/queue/attempt"), 5);
        }
    }

    @Test
    void exactPersonalQueueAccepted() throws Exception {
        try (StompTestConnection conn = connect(studentToken)) {
            CountDownLatch latch = new CountDownLatch(1);
            conn.subscribe("/user/queue/attempt", noopHandler(latch));
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            awaitAccepted(acceptedDestination("/user/queue/attempt"), 5);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/user/queue/attempt/extra", "/user/queue", "/queue/attempt", "/user/queue/attempt/",
            "/user//queue/attempt", "/user/queue/attempt%2Fextra", "/user/0/queue/attempt",
            "/user/queue/attempt/x/y"
    })
    void personalDestinationIsRejected(String destination) {
        try (StompTestConnection conn = connect(studentToken)) {
            conn.subscribe(destination, noopHandler(new CountDownLatch(1)));
            String error = conn.awaitError(5);          // client-side ERROR frame received
            conn.awaitDisconnect();                       // the right connection is closed
            assertErrorHasNoLeak(error, studentToken, destination);
            assertNoOutbound(serverTimeSyncMessage(), 1);  // zero SERVER_TIME_SYNC for this session
            assertNoAccepted(acceptedDestination(destination), 1); // zero inbound-accepted SUBSCRIBE
        }
    }

    @Test
    void personalRejectionErrorsAreGenericAndIdentical() {
        // Aggregate proof: every rejected personal destination produces the SAME generic ERROR shape.
        List<String> errors = Stream.of(PERSONAL_REJECTED)
                .map(this::capturePersonalRejectionError).toList();
        String first = errors.get(0);
        assertThat(errors).as("all personal-rejection ERROR frames share one generic shape").allMatch(first::equals);
        errors.forEach(e -> assertErrorHasNoLeak(e, studentToken, null));
    }

    // ============================ §6 — spoofed-header non-elevation ============================

    @Test
    void spoofedHeadersDoNotElevateStudentToTeacherTopic() throws Exception {
        long uid = insertUserWithRole("spoof-", "STUDENT");
        String token = accessToken(uid, "spoof", List.of("STUDENT"));
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        connectHeaders.add("roles", "[\"TEACHER\"]");
        connectHeaders.add("username", "admin");
        connectHeaders.add("permission", "EXAM_SESSION_MONITOR");

        // A valid teacher observer (the session owner) stays subscribed + connected throughout.
        try (StompTestConnection observer = connect(teacherToken)) {
            List<String> observerReceived = new CopyOnWriteArrayList<>();
            observer.subscribe("/topic/exam-sessions/" + sessionId, stringHandler(observerReceived, new CountDownLatch(1)));
            awaitAccepted(acceptedDestination("/topic/exam-sessions/" + sessionId), 5); // observer's sub accepted

            try (StompTestConnection conn = connectWith(connectHeaders)) {
                assertThat(conn.isConnected()).isTrue(); // CONNECT succeeds — spoofed headers ignored
                conn.subscribe("/topic/exam-sessions/" + sessionId, noopHandler(new CountDownLatch(1)));
                String error = conn.awaitError(5);
                conn.awaitDisconnect();
                assertErrorHasNoLeak(error, token, "/topic/exam-sessions/" + sessionId);
                assertThat(error).doesNotContain("EXAM_SESSION_MONITOR").doesNotContain("TEACHER");
            }

            // Observer unaffected: still connected, received no topic MESSAGE, no sync fanned to it.
            assertThat(observer.isConnected()).isTrue();
            assertThat(observerReceived).isEmpty();
            assertNoOutbound(serverTimeSyncMessage(), 1);
            // Only the observer's topic subscription was inbound-accepted (passed inbound interceptors); the spoofed one was not.
            assertThat(acceptedProbe.snapshot().stream()
                    .filter(a -> ("/topic/exam-sessions/" + sessionId).equals(a.destination())).count())
                    .as("only the valid observer's topic SUBSCRIBE was accepted").isEqualTo(1L);
        }
    }

    // ============================ §6 — teacher topic authorization matrix ============================

    @Test
    void teacherOwnerTopicAccepted() throws Exception {
        try (StompTestConnection conn = connect(teacherToken)) {
            conn.subscribe("/topic/exam-sessions/" + sessionId, noopHandler(new CountDownLatch(1)));
            awaitAccepted(acceptedDestination("/topic/exam-sessions/" + sessionId), 5); // authorized: SessionSubscribeEvent fired (inbound receipt — NOT broker registration; B2F8)
            assertThat(conn.isConnected()).isTrue();
        }
    }

    @Test void permissionRevokedTeacherRejected() {
        jdbc.update("DELETE FROM role_permissions WHERE role_id=(SELECT id FROM roles WHERE code='TEACHER') "
                + "AND permission_id=(SELECT id FROM permissions WHERE code='EXAM_SESSION_MONITOR')");
        try {
            assertTopicSubscribeRejected(teacherToken, "/topic/exam-sessions/" + sessionId);
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_id, permission_id) VALUES "
                    + "((SELECT id FROM roles WHERE code='TEACHER'),"
                    + "(SELECT id FROM permissions WHERE code='EXAM_SESSION_MONITOR'))");
        }
    }

    @Test void studentRejectedOnTeacherTopic() { assertTopicSubscribeRejected(studentToken, "/topic/exam-sessions/" + sessionId); }
    @Test void nonTeacherWithProfileRejected() {
        long uid = insertUserWithRole("noteach-", "STUDENT");
        ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + uid + "," + schoolId + ",'NT')");
        assertTopicSubscribeRejected(accessToken(uid, "noteach", List.of("STUDENT")), "/topic/exam-sessions/" + sessionId);
    }
    @Test void systemAdminOnlyRejected() {
        long admin = insertUserWithRole("sysadmin-", "SYSTEM_ADMIN");
        assertTopicSubscribeRejected(accessToken(admin, "sysadmin", List.of("SYSTEM_ADMIN")), "/topic/exam-sessions/" + sessionId);
    }
    @Test void foreignTeacherSameSchoolRejected() { assertTopicSubscribeRejected(foreignTeacherToken, "/topic/exam-sessions/" + sessionId); }
    @Test void crossSchoolTeacherRejected() { assertTopicSubscribeRejected(crossSchoolTeacherToken, "/topic/exam-sessions/" + sessionId); }
    @Test void missingTeacherProfileRejected() { assertTopicSubscribeRejected(noProfileTeacherToken, "/topic/exam-sessions/" + sessionId); }
    @Test void nonexistentSessionRejected() { assertTopicSubscribeRejected(teacherToken, "/topic/exam-sessions/99999999"); }
    @Test void zeroSessionIdRejected() { assertTopicSubscribeRejected(teacherToken, "/topic/exam-sessions/0"); }
    @Test void negativeSessionIdRejected() { assertTopicSubscribeRejected(teacherToken, "/topic/exam-sessions/-1"); }
    @Test void longOverflowSessionIdRejected() { assertTopicSubscribeRejected(teacherToken, "/topic/exam-sessions/99999999999999999999999999"); }
    @Test void nonnumericSessionIdRejected() { assertTopicSubscribeRejected(teacherToken, "/topic/exam-sessions/not-a-number"); }
    @Test void extraPathSegmentRejected() { assertTopicSubscribeRejected(teacherToken, "/topic/exam-sessions/" + sessionId + "/extra"); }
    @Test void encodedSlashSessionIdRejected() { assertTopicSubscribeRejected(teacherToken, "/topic/exam-sessions/1%2F2"); }
    @Test void prefixTamperedDestinationRejected() { assertTopicSubscribeRejected(teacherToken, "/topic-/exam-sessions/" + sessionId); }
    @Test void suffixTamperedDestinationRejected() { assertTopicSubscribeRejected(teacherToken, "/topic/exam-sessions/" + sessionId + "x"); }

    // ============================ §5/§8 — missing-vs-unauthorized ERROR indistinguishability ============================

    @Test
    void teacherTopicRejectionsAreIndistinguishable() {
        String nonexistent = captureTopicRejectionError(teacherToken, "/topic/exam-sessions/99999999");
        String foreign = captureTopicRejectionError(foreignTeacherToken, "/topic/exam-sessions/" + sessionId);
        String crossSchool = captureTopicRejectionError(crossSchoolTeacherToken, "/topic/exam-sessions/" + sessionId);
        String noProfile = captureTopicRejectionError(noProfileTeacherToken, "/topic/exam-sessions/" + sessionId);

        jdbc.update("DELETE FROM role_permissions WHERE role_id=(SELECT id FROM roles WHERE code='TEACHER') "
                + "AND permission_id=(SELECT id FROM permissions WHERE code='EXAM_SESSION_MONITOR')");
        String noPermission;
        try {
            noPermission = captureTopicRejectionError(teacherToken, "/topic/exam-sessions/" + sessionId);
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_id, permission_id) VALUES "
                    + "((SELECT id FROM roles WHERE code='TEACHER'),"
                    + "(SELECT id FROM permissions WHERE code='EXAM_SESSION_MONITOR'))");
        }

        assertThat(nonexistent)
                .as("all teacher-topic rejections share the same generic ERROR message")
                .isEqualTo(foreign).isEqualTo(crossSchool).isEqualTo(noProfile).isEqualTo(noPermission);
        List.of(nonexistent, foreign, crossSchool, noProfile, noPermission)
                .forEach(e -> assertErrorHasNoLeak(e, teacherToken, null));
    }

    // ============================ helpers ============================

    private void assertTopicSubscribeRejected(String token, String destination) {
        try (StompTestConnection conn = connect(token)) {
            conn.subscribe(destination, noopHandler(new CountDownLatch(1)));
            String error = conn.awaitError(5);
            conn.awaitDisconnect();
            assertErrorHasNoLeak(error, token, destination);
            assertNoAccepted(acceptedDestination(destination), 1); // zero inbound-accepted SUBSCRIBE
        }
    }

    private String captureTopicRejectionError(String token, String destination) {
        try (StompTestConnection conn = connect(token)) {
            conn.subscribe(destination, noopHandler(new CountDownLatch(1)));
            String error = conn.awaitError(5);
            conn.awaitDisconnect();
            return error;
        }
    }

    private String capturePersonalRejectionError(String destination) {
        try (StompTestConnection conn = connect(studentToken)) {
            conn.subscribe(destination, noopHandler(new CountDownLatch(1)));
            String error = conn.awaitError(5);
            conn.awaitDisconnect();
            return error;
        }
    }

    /** Generic no-leak contract for a captured ERROR frame: no token, exception class, stack/internal
     *  path, SQL, permission code, schema column, or destination interpretation. */
    private static void assertErrorHasNoLeak(String error, String token, String destination) {
        assertThat(error)
                .as("ERROR frame must not leak sensitive data")
                .doesNotContain(token == null ? "<<<no-token>>>" : token) // raw token (if any)
                .doesNotContain("MessagingException")
                .doesNotContain("RealtimeAuthorizationException")
                .doesNotContain("org.springframework")
                .doesNotContain("at com.")
                .doesNotContain("SELECT")
                .doesNotContain("constraint")
                .doesNotContain("EXAM_SESSION_MONITOR")
                .doesNotContain("school_id")
                .doesNotContain("owner_teacher_id");
        if (destination != null) {
            assertThat(error).as("ERROR must not echo the tampered destination").doesNotContain(destination);
        }
    }

    private StompFrameHandler stringHandler(List<String> sink, CountDownLatch latch) {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders h) { return byte[].class; }
            @Override public void handleFrame(StompHeaders h, Object payload) {
                sink.add(new String((byte[]) payload, java.nio.charset.StandardCharsets.UTF_8));
                latch.countDown();
            }
        };
    }

    private StompFrameHandler noopHandler(CountDownLatch latch) {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders h) { return byte[].class; }
            @Override public void handleFrame(StompHeaders h, Object payload) { latch.countDown(); }
        };
    }
}
