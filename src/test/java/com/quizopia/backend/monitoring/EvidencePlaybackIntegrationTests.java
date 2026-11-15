package com.quizopia.backend.monitoring;

import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenSession;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newTeacherUser;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.quizopia.backend.evidence.application.EvidenceQueryService;
import com.quizopia.backend.evidence.application.EvidenceRetentionJob;
import com.quizopia.backend.media.storage.EvidenceStorage;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Final-gate blockers P1-1/P1-2/P1-3 (issues #91–#94):
 *
 * <ul>
 *   <li>P1-3 playback URL for recording segments: teacher-only (permission +
 *       ownership), job-of-session, COMPLETE + object + not-deleted + not
 *       expired; every mint audited with the recording target; IDOR matrix;
 *       honest 409s for non-playable states.</li>
 *   <li>P1-1 30-day retention now covers FULL/ROLLING objects (expires_at,
 *       time-travel, retry).</li>
 *   <li>P1-2 false-deletion honesty: storage outage ⇒ NOTHING marked;
 *       partial failure ⇒ only verified rows marked; purge covers
 *       recordings too.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
class EvidencePlaybackIntegrationTests {

    @Autowired private EvidenceQueryService queries;
    @Autowired private EvidenceRetentionJob retention;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @MockitoBean private EvidenceStorage storage;

    private Instant base;
    private long teacherId;
    private long foreignTeacherId;
    private long studentId;
    private long sessionId;
    private long attemptId;
    private UUID bindingId;

    @BeforeEach
    void setUp() {
        base = Instant.parse("2026-11-15T08:00:00Z");
        clock.setInstant(base);
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        teacherId = newTeacherUser(jdbc, "pbteacher" + sfx);
        foreignTeacherId = newTeacherUser(jdbc, "pbforeign" + sfx);
        studentId = newUser(jdbc, "pbstudent" + sfx);
        sessionId = insertOpenSession(jdbc, teacherId, base);
        attemptId = jdbc.queryForObject(
                "INSERT INTO attempts (exam_session_id, user_id, attempt_number, status, "
                        + "started_at, deadline_at) VALUES (?,?,1,'IN_PROGRESS',?,?) RETURNING id",
                Long.class, sessionId, studentId,
                java.sql.Timestamp.from(base), java.sql.Timestamp.from(base.plusSeconds(3600)));
        bindingId = UUID.randomUUID();
        UUID preflightId = UUID.randomUUID();
        jdbc.update("INSERT INTO monitoring_preflights "
                        + "(id, exam_session_id, user_id, media_identity, status, expires_at) "
                        + "VALUES (?,?,?,?, 'VERIFIED', ?)",
                preflightId, sessionId, studentId, UUID.randomUUID().toString(),
                java.sql.Timestamp.from(base.plusSeconds(3600)));
        jdbc.update("INSERT INTO monitoring_media_bindings "
                        + "(id, exam_session_id, user_id, preflight_id, attempt_id, media_identity, epoch) "
                        + "VALUES (?,?,?,?,?,?,1)",
                bindingId, sessionId, studentId, preflightId, attemptId,
                UUID.randomUUID().toString());
        when(storage.presignedGet(anyString(), any())).thenReturn("http://signed/segment");
        when(storage.head(anyString())).thenReturn(Optional.of(new EvidenceStorage.ObjectHead(1)));
    }

    /** Inserts one recording job row; returns its id. */
    private UUID insertJob(String kind, String status, Instant startedAt, Instant endedAt,
            String objectKey, Instant expiresAt, boolean objectDeleted) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO monitoring_recording_jobs "
                        + "(id, exam_session_id, attempt_id, binding_id, kind, egress_id, room_name, "
                        + "track_sid, status, pulse_index, started_at, ended_at, object_key, bytes, "
                        + "duration_ms, expires_at, object_deleted) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,0,?,?,?,5000,60000,?,?)",
                id, sessionId, attemptId, bindingId, kind,
                "EG_PB_" + UUID.randomUUID().toString().substring(0, 8),
                "room-pb", "TR_PB", status,
                startedAt == null ? null : java.sql.Timestamp.from(startedAt),
                endedAt == null ? null : java.sql.Timestamp.from(endedAt),
                objectKey, expiresAt == null ? null : java.sql.Timestamp.from(expiresAt),
                objectDeleted);
        return id;
    }

    // ================= P1-3: playback =================

    @Test
    void completeSegmentYieldsAuditedPlaybackUrl() {
        UUID jobId = insertJob("FULL", "COMPLETE", base, base.plusSeconds(60),
                "recordings/" + attemptId + "/full.mp4", base.plusSeconds(86400 * 30), false);
        var signed = queries.recordingPlaybackUrl(teacherId, sessionId, jobId);
        assertThat(signed.url()).isEqualTo("http://signed/segment");
        assertThat(signed.expiresAt()).isAfter(base);
        Long audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_evidence_access "
                        + "WHERE recording_job_id = ? AND user_id = ? AND action = 'URL_MINTED'",
                Long.class, jobId, teacherId);
        assertThat(audits).isEqualTo(1L);
    }

    @Test
    void idorMatrixForeignTeacherAndStudentGetGenericDenials() {
        UUID jobId = insertJob("FULL", "COMPLETE", base, base.plusSeconds(60),
                "recordings/" + attemptId + "/full.mp4", base.plusSeconds(86400 * 30), false);
        assertThatThrownBy(() -> queries.recordingPlaybackUrl(foreignTeacherId, sessionId, jobId))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("MONITORING_ACCESS_DENIED"));
        assertThatThrownBy(() -> queries.recordingPlaybackUrl(studentId, sessionId, jobId))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("MONITORING_ACCESS_DENIED"));
    }

    @Test
    void jobOfAnotherSessionIsAPlainMiss() {
        UUID jobId = insertJob("FULL", "COMPLETE", base, base.plusSeconds(60),
                "recordings/" + attemptId + "/full.mp4", base.plusSeconds(86400 * 30), false);
        // a session the teacher does NOT own → same 403 gate before the 404
        long otherSession = insertOpenSession(jdbc, studentId, base);
        assertThatThrownBy(() -> queries.recordingPlaybackUrl(teacherId, otherSession, jobId))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("MONITORING_ACCESS_DENIED"));
        assertThatThrownBy(() -> queries.recordingPlaybackUrl(teacherId, sessionId,
                UUID.randomUUID()))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("EVIDENCE_NOT_FOUND"));
    }

    @Test
    void nonPlayableStatesAreHonest409s() {
        assertThatThrownBy(() -> queries.recordingPlaybackUrl(teacherId, sessionId,
                insertJob("FULL", "ACTIVE", base, null, null, null, false)))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("EVIDENCE_CLIP_REJECTED"));
        assertThatThrownBy(() -> queries.recordingPlaybackUrl(teacherId, sessionId,
                insertJob("FULL", "COMPLETE", base, base.plusSeconds(60),
                        "recordings/" + attemptId + "/x.mp4", base.plusSeconds(86400 * 30), true)))
                .as("object_deleted segment never yields a URL")
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("EVIDENCE_CLIP_REJECTED"));
        assertThatThrownBy(() -> queries.recordingPlaybackUrl(teacherId, sessionId,
                insertJob("FULL", "COMPLETE", base, base.plusSeconds(60),
                        "recordings/" + attemptId + "/y.mp4", base.minusSeconds(60), false)))
                .as("expired segment never yields a URL")
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("EVIDENCE_CLIP_REJECTED"));
    }

    // ================= P1-1: recording retention =================

    @Test
    void expiredRecordingObjectDeletedRowKeptAsMetadata() {
        UUID jobId = insertJob("FULL", "COMPLETE", base, base.plusSeconds(60),
                "recordings/" + attemptId + "/expired.mp4", base.minusSeconds(60), false);
        retention.expireRecordings();
        assertThat(jdbc.queryForObject(
                "SELECT object_deleted FROM monitoring_recording_jobs WHERE id = ?",
                Boolean.class, jobId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_recording_jobs WHERE id = ?",
                Long.class, jobId)).isEqualTo(1L);
    }

    @Test
    void notYetExpiredRecordingsStay() {
        insertJob("FULL", "COMPLETE", base, base.plusSeconds(60),
                "recordings/" + attemptId + "/fresh.mp4", base.plusSeconds(86400 * 30), false);
        retention.expireRecordings();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_recording_jobs WHERE object_deleted = true "
                        + "AND exam_session_id = ?",
                Long.class, sessionId)).isZero();
    }

    // ================= P1-2: false-deletion honesty =================

    @Test
    void storageOutageMarksNothingAndRetrySucceeds() {
        UUID jobId = insertJob("FULL", "COMPLETE", base, base.plusSeconds(60),
                "recordings/" + attemptId + "/stuck.mp4", base.minusSeconds(60), false);
        when(storage.delete(anyString())).thenThrow(new RuntimeException("minio down"));
        when(storage.head(anyString())).thenThrow(new RuntimeException("minio down"));
        retention.expireRecordings();
        assertThat(jdbc.queryForObject(
                "SELECT object_deleted FROM monitoring_recording_jobs WHERE id = ?",
                Boolean.class, jobId)).isFalse();

        // outage over: retry deletes and flags honestly (doReturn — when()
        // would itself invoke the still-throwing stub)
        org.mockito.Mockito.doReturn(true).when(storage).delete(anyString());
        org.mockito.Mockito.doReturn(Optional.of(new EvidenceStorage.ObjectHead(1)))
                .when(storage).head(anyString());
        retention.expireRecordings();
        assertThat(jdbc.queryForObject(
                "SELECT object_deleted FROM monitoring_recording_jobs WHERE id = ?",
                Boolean.class, jobId)).isTrue();
    }

    @Test
    void purgeCoversRecordingsSnapshotsAndClips() {
        UUID jobId = insertJob("FULL", "COMPLETE", base, base.plusSeconds(60),
                "recordings/" + attemptId + "/purge.mp4", base.plusSeconds(86400 * 30), false);
        jdbc.update("INSERT INTO monitoring_evidence "
                        + "(id, exam_session_id, attempt_id, kind, status, provenance, object_key, "
                        + "content_type, sha256, bytes, captured_at, created_at, expires_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), sessionId, attemptId, "SNAPSHOT", "AVAILABLE",
                "CLIENT_CAPTURE", "snapshots/" + attemptId + "/p.jpg", "image/jpeg",
                "cd".repeat(32), 10, java.sql.Timestamp.from(base),
                java.sql.Timestamp.from(base), java.sql.Timestamp.from(base.plusSeconds(86400 * 30)));
        when(storage.delete(anyString())).thenReturn(true);
        int deleted = retention.purgeSession(sessionId);
        assertThat(deleted).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT object_deleted FROM monitoring_recording_jobs WHERE id = ?",
                Boolean.class, jobId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_evidence WHERE status = 'EXPIRED'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void partialStorageFailureMarksOnlyVerifiedRows() {
        UUID okJob = insertJob("FULL", "COMPLETE", base, base.plusSeconds(60),
                "recordings/" + attemptId + "/ok.mp4", base.plusSeconds(86400 * 30), false);
        UUID stuckJob = insertJob("FULL", "COMPLETE", base.plusSeconds(61), base.plusSeconds(120),
                "recordings/" + attemptId + "/stuck2.mp4", base.plusSeconds(86400 * 30), false);
        when(storage.delete(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (key.contains("stuck2")) {
                throw new RuntimeException("minio flaky");
            }
            return true;
        });
        when(storage.head(anyString())).thenReturn(Optional.of(new EvidenceStorage.ObjectHead(1)));
        retention.purgeSession(sessionId);
        assertThat(jdbc.queryForObject(
                "SELECT object_deleted FROM monitoring_recording_jobs WHERE id = ?",
                Boolean.class, okJob)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT object_deleted FROM monitoring_recording_jobs WHERE id = ?",
                Boolean.class, stuckJob)).as("unverified delete must NOT be flagged").isFalse();
    }
}
