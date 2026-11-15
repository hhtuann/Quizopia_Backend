package com.quizopia.backend.monitoring;

import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenSession;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newTeacherUser;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.quizopia.backend.evidence.application.EvidenceRetentionJob;
import com.quizopia.backend.media.storage.EvidenceStorage;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import java.time.Instant;
import java.util.List;
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
 * P1 orphan reconciliation coverage (gate review): snapshots, clips, rolling
 * buffers and full recordings, each against its lifecycle — and the work list
 * must NOT be limited to attempts with rolling rows.
 *
 * <ul>
 *   <li>recording-ON attempt + snapshot orphan (orphan deleted, referenced
 *       recording kept)</li>
 *   <li>recording-OFF attempt + snapshot orphan, NO rolling rows (the case
 *       the old work list missed)</li>
 *   <li>orphan FULL recording object (no row references it)</li>
 *   <li>in-flight guard: ACTIVE job ⇒ nothing deleted; young PENDING
 *       snapshot row ⇒ nothing deleted</li>
 *   <li>stale PENDING snapshot row ⇒ ABANDONED_PENDING + object deleted</li>
 *   <li>storage outage during reconcile ⇒ nothing falsely reported</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
class EvidenceOrphanReconciliationIntegrationTests {

    @Autowired private EvidenceRetentionJob retention;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @MockitoBean private EvidenceStorage storage;

    private Instant base;
    private long teacherId;
    private long studentId;
    private long sessionId;
    private long attemptId;
    private UUID bindingId;

    @BeforeEach
    void setUp() {
        base = Instant.parse("2026-11-16T08:00:00Z");
        clock.setInstant(base);
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        teacherId = newTeacherUser(jdbc, "orpteacher" + sfx);
        studentId = newUser(jdbc, "orpstudent" + sfx);
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
        when(storage.delete(anyString())).thenReturn(true);
        when(storage.head(anyString()))
                .thenAnswer(inv -> Optional.of(new EvidenceStorage.ObjectHead(10)));
    }

    private void insertJobRow(String kind, String status, String egressId, String objectKey) {
        jdbc.update("INSERT INTO monitoring_recording_jobs "
                        + "(id, exam_session_id, attempt_id, binding_id, kind, egress_id, room_name, "
                        + "track_sid, status, pulse_index, started_at, ended_at, object_key, bytes, "
                        + "duration_ms) VALUES (?,?,?,?,?,?,?,?,?,0,?,?,?,5000,60000)",
                UUID.randomUUID(), sessionId, attemptId, bindingId, kind, egressId,
                "room-orp", "TR_ORP", status,
                java.sql.Timestamp.from(base), java.sql.Timestamp.from(base.plusSeconds(60)),
                objectKey);
    }

    private void insertEvidenceRow(String kind, String status, String objectKey,
            java.time.Instant updatedAt) {
        jdbc.update("INSERT INTO monitoring_evidence "
                        + "(id, exam_session_id, attempt_id, kind, status, provenance, object_key, "
                        + "content_type, sha256, bytes, captured_at, created_at, updated_at, expires_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), sessionId, attemptId, kind, status, "CLIENT_CAPTURE",
                objectKey, kind.equals("SNAPSHOT") ? "image/jpeg" : "video/mp4",
                "ab".repeat(32), 10, java.sql.Timestamp.from(base),
                java.sql.Timestamp.from(base), java.sql.Timestamp.from(updatedAt),
                java.sql.Timestamp.from(base.plusSeconds(86400 * 30)));
    }

    @Test
    void recordingOnAttemptSnapshotOrphanDeletedReferencedRecordingKept() {
        insertJobRow("FULL", "COMPLETE", "EG_ORP_1", "recordings/" + attemptId + "/live.mp4");
        insertEvidenceRow("SNAPSHOT", "AVAILABLE", "snapshots/" + attemptId + "/keep.jpg", base);
        when(storage.list(anyString())).thenAnswer(inv -> {
            String prefix = inv.getArgument(0);
            if (prefix.equals("snapshots/" + attemptId + "/")) {
                return List.of("snapshots/" + attemptId + "/keep.jpg",
                        "snapshots/" + attemptId + "/orphan.jpg");
            }
            if (prefix.equals("recordings/" + attemptId + "/")) {
                return List.of("recordings/" + attemptId + "/live.mp4");
            }
            return List.of();
        });

        int deleted = retention.reconcileAttemptOrphans(attemptId);

        assertThat(deleted).isEqualTo(1);
        verify(storage).delete("snapshots/" + attemptId + "/orphan.jpg");
        verify(storage, never()).delete("recordings/" + attemptId + "/live.mp4");
        verify(storage, never()).delete("snapshots/" + attemptId + "/keep.jpg");
    }

    @Test
    void recordingOffAttemptWithOnlySnapshotsIsNotMissed() {
        // NO rolling/job rows at all — the old work list (rolling rows only)
        // skipped this attempt entirely.
        insertEvidenceRow("SNAPSHOT", "AVAILABLE", "snapshots/" + attemptId + "/a.jpg", base);
        when(storage.list(anyString())).thenReturn(List.of(
                "snapshots/" + attemptId + "/a.jpg",
                "snapshots/" + attemptId + "/orphan-after-failed-finalize.jpg"));

        // the work list is built from evidence rows too
        assertThat(retention.reconcileAllOrphansExposesWorkList()).contains(attemptId);
        int deleted = retention.reconcileAllOrphans();

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        verify(storage, atLeastOnce())
                .delete("snapshots/" + attemptId + "/orphan-after-failed-finalize.jpg");
    }

    @Test
    void orphanFullRecordingObjectDeleted() {
        insertJobRow("FULL", "COMPLETE", "EG_ORP_2", "recordings/" + attemptId + "/real.mp4");
        when(storage.list(anyString())).thenAnswer(inv -> {
            String prefix = inv.getArgument(0);
            if (prefix.equals("recordings/" + attemptId + "/")) {
                return List.of("recordings/" + attemptId + "/real.mp4",
                        "recordings/" + attemptId + "/ghost.mp4");
            }
            return List.of();
        });

        int deleted = retention.reconcileAttemptOrphans(attemptId);
        assertThat(deleted).isEqualTo(1);
        verify(storage).delete("recordings/" + attemptId + "/ghost.mp4");
    }

    @Test
    void activeJobSkipsEntireAttempt() {
        insertJobRow("FULL", "ACTIVE", "EG_ORP_3", null);
        when(storage.list(anyString())).thenReturn(List.of("snapshots/" + attemptId + "/x.jpg"));

        assertThat(retention.reconcileAttemptOrphans(attemptId)).isZero();
        verify(storage, never()).delete(anyString());
    }

    @Test
    void youngPendingSnapshotRowDefersOrphanPass() {
        // reservation won, PUT done, finalize tx lost < grace window ago
        insertEvidenceRow("SNAPSHOT", "PENDING", null, base.plusSeconds(60));
        when(storage.list(anyString())).thenReturn(List.of(
                "snapshots/" + attemptId + "/maybe-mine.jpg"));

        assertThat(retention.reconcileAttemptOrphans(attemptId)).isZero();
        verify(storage, never()).delete(anyString());
    }

    @Test
    void stalePendingSnapshotFailsHonestlyAndCleansObject() {
        UUID rowId = UUID.randomUUID();
        String key = "snapshots/" + attemptId + "/abandoned.jpg";
        jdbc.update("INSERT INTO monitoring_evidence "
                        + "(id, exam_session_id, attempt_id, kind, status, provenance, object_key, "
                        + "content_type, sha256, bytes, captured_at, created_at, updated_at, expires_at) "
                        + "VALUES (?,?,?,'SNAPSHOT','PENDING','CLIENT_CAPTURE',?, 'image/jpeg',"
                        + "NULL,NULL,?,?,?,?)",
                rowId, sessionId, attemptId, key,
                java.sql.Timestamp.from(base.minusSeconds(8 * 3600)),
                java.sql.Timestamp.from(base.minusSeconds(8 * 3600)),
                java.sql.Timestamp.from(base.minusSeconds(8 * 3600)), // updated 8h ago (stale)
                java.sql.Timestamp.from(base.plusSeconds(86400 * 30)));

        retention.sweepStalePending();

        assertThat(jdbc.queryForObject(
                "SELECT status FROM monitoring_evidence WHERE id = ?", String.class, rowId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT error FROM monitoring_evidence WHERE id = ?", String.class, rowId))
                .isEqualTo("ABANDONED_PENDING");
        verify(storage).delete(key);
    }

    @Test
    void outageDuringReconcileReportsNothingFalse() {
        when(storage.list(anyString())).thenReturn(List.of("snapshots/" + attemptId + "/o.jpg"));
        when(storage.delete(anyString())).thenThrow(new RuntimeException("minio down"));
        when(storage.head(anyString())).thenThrow(new RuntimeException("minio down"));

        assertThat(retention.reconcileAttemptOrphans(attemptId)).isZero();
    }
}
