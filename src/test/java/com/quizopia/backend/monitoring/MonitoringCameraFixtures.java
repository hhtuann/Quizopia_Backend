package com.quizopia.backend.monitoring;

import org.springframework.jdbc.core.JdbcTemplate;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared JdbcTemplate fixtures for the P4-B camera webhook/reconciliation
 * tests (V37 tables). Mirrors the established P4-A fixture style (raw SQL
 * against the Flyway-migrated Testcontainers schema, unique codes via UUID
 * suffixes). Monitoring policy/room/preflight/binding rows are inserted
 * AFTER attempt creation so the P4-A/P4-B start gate never interferes —
 * these tests exercise webhook routing, not the gate.
 */
final class MonitoringCameraFixtures {

    private MonitoringCameraFixtures() {
    }

    record PreflightBinding(UUID preflightId, UUID bindingId, String mediaIdentity) {
    }

    static long insertReturningId(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }

    static long newUser(JdbcTemplate jdbc, String username) {
        long id = insertReturningId(jdbc,
                "INSERT INTO users (username, email, password_hash, display_name) VALUES ('"
                        + username + "','" + username + "@t.com','h','" + username.toUpperCase() + "')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + id + "," + roleId + ")");
        return id;
    }

    /**
     * OPEN exam session (window around {@code base}, maxAttempts 3) with one
     * published single-choice question; {@code userId} is a participant. NOT
     * yet monitored — call {@link #enableMonitoring} after starting attempts.
     */
    static long insertOpenSession(JdbcTemplate jdbc, long userId, Instant base) {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        long gl = insertReturningId(jdbc,
                "INSERT INTO grade_levels (code, name) VALUES ('GL" + sfx + "','G')");
        long subj = insertReturningId(jdbc,
                "INSERT INTO subjects (grade_level_id, code, name) VALUES (" + gl + ",'SUB" + sfx + "','Math')");
        long bank = insertReturningId(jdbc,
                "INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES ("
                        + userId + "," + subj + ",'BNK" + sfx + "','Bank')");
        long exam = insertReturningId(jdbc,
                "INSERT INTO exams (created_by_user_id, subject_id, code, title, status, published_at, total_points) VALUES ("
                        + userId + "," + subj + ",'EXM" + sfx + "','Exam','PUBLISHED','" + base + "',4)");
        long sec = insertReturningId(jdbc,
                "INSERT INTO exam_sections (exam_id, title, position) VALUES (" + exam + ",'S',0)");
        long q = insertReturningId(jdbc,
                "INSERT INTO questions (question_bank_id, code, created_by) VALUES ("
                        + bank + ",'Q" + sfx + "'," + userId + ")");
        long qv = insertReturningId(jdbc,
                "INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES ("
                        + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + userId + ")");
        long eq = insertReturningId(jdbc,
                "INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES ("
                        + exam + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES "
                + "(" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),"
                + "(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
        long session = insertReturningId(jdbc,
                "INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at, duration_minutes) VALUES ("
                        + exam + ",'SES" + sfx + "','Sess','OPEN','" + base.minusSeconds(3600) + "','"
                        + base.plusSeconds(7200) + "',3," + userId + ",'" + base.minusSeconds(3600) + "',60)");
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) VALUES ("
                + userId + "," + session + "," + userId + ")");
        return session;
    }

    static void enableMonitoring(JdbcTemplate jdbc, long sessionId) {
        jdbc.update("INSERT INTO session_monitoring_policies (exam_session_id, monitoring_enabled) "
                + "VALUES (?, true)", sessionId);
    }

    /** Inserts the session's media room; returns the generated room name. */
    static String insertRoom(JdbcTemplate jdbc, long sessionId) {
        String roomName = "room-" + UUID.randomUUID();
        jdbc.update("INSERT INTO monitoring_media_rooms (exam_session_id, room_name) VALUES (?, ?)",
                sessionId, roomName);
        return roomName;
    }

    /**
     * VERIFIED preflight (unexpired) + its binding; {@code activeTo} null =
     * active binding, non-null = already terminated (only allowed for bound
     * bindings by the V37 CHECK).
     */
    static PreflightBinding insertPreflightAndBinding(JdbcTemplate jdbc, long sessionId, long userId,
            Long attemptId, Instant base, Instant activeTo) {
        UUID preflightId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        String identity = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO monitoring_preflights "
                + "(id, exam_session_id, user_id, media_identity, status, expires_at) "
                + "VALUES (?,?,?,?, 'VERIFIED', ?)",
                preflightId, sessionId, userId, identity, Timestamp.from(base.plusSeconds(3600)));
        jdbc.update("INSERT INTO monitoring_media_bindings "
                + "(id, exam_session_id, user_id, preflight_id, attempt_id, media_identity, epoch, active_to) "
                + "VALUES (?,?,?,?,?,?,1,?)",
                bindingId, sessionId, userId, preflightId, attemptId, identity,
                activeTo == null ? null : Timestamp.from(activeTo));
        return new PreflightBinding(preflightId, bindingId, identity);
    }

    static void insertOpenCameraConnection(JdbcTemplate jdbc, UUID bindingId, String trackSid, Instant at) {
        jdbc.update("INSERT INTO monitoring_camera_connections "
                + "(id, binding_id, track_sid, connected_at, last_verified_at) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), bindingId, trackSid, Timestamp.from(at), Timestamp.from(at));
    }

    static void insertClosedCameraConnection(JdbcTemplate jdbc, UUID bindingId, String trackSid,
            Instant connectedAt, Instant disconnectedAt) {
        jdbc.update("INSERT INTO monitoring_camera_connections "
                + "(id, binding_id, track_sid, connected_at, disconnected_at, close_reason) "
                + "VALUES (?,?,?,?,?, 'fixture')",
                UUID.randomUUID(), bindingId, trackSid,
                Timestamp.from(connectedAt), Timestamp.from(disconnectedAt));
    }

    /** Marks an attempt GRADED while honouring the submission invariant CHECK. */
    static void finishAttempt(JdbcTemplate jdbc, long attemptId, Instant at) {
        jdbc.update("UPDATE attempts SET status='GRADED', submitted_at=?, "
                        + "submission_idempotency_key=? WHERE id=?",
                Timestamp.from(at), "fx-" + attemptId, attemptId);
    }

    static long cameraEventCount(JdbcTemplate jdbc, long attemptId, String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM attempt_activity_events WHERE attempt_id=? AND event_type=?",
                Long.class, attemptId, eventType);
    }

    /** Total camera-lifecycle events — excludes the start flow's ATTEMPT_STARTED baseline. */
    static long cameraEventTotal(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM attempt_activity_events WHERE event_type IN "
                        + "('CAMERA_CONNECTED','CAMERA_DISCONNECTED','CAMERA_RECONNECTED',"
                        + "'CAMERA_REVOKED','MONITORING_GAP')",
                Long.class);
    }

    static List<Map<String, Object>> eventsOf(JdbcTemplate jdbc, long attemptId, String eventType) {
        return jdbc.queryForList("SELECT * FROM attempt_activity_events WHERE attempt_id=? AND event_type=?",
                attemptId, eventType);
    }

    /** 64-char hex dedupe key, deterministic per tag (duplicate tests reuse tags). */
    static String dedupeKey(String tag) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(tag.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
