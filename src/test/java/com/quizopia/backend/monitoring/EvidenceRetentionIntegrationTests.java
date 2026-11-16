package com.quizopia.backend.monitoring;

import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenSession;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newTeacherUser;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.quizopia.backend.evidence.application.EvidenceQueryService;
import com.quizopia.backend.evidence.application.EvidenceRetentionJob;
import com.quizopia.backend.evidence.repository.MonitoringEvidenceRepository;
import com.quizopia.backend.media.storage.EvidenceStorage;
import com.quizopia.backend.monitoring.exception.MonitoringException;
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
 * D4 (issue #94) retention + audited playback: 30-day expiry flips rows to
 * EXPIRED only after the object is really deleted (no dead URLs), the 90-day
 * access audit sweeps, the session demo purge keeps metadata, and URL mints
 * are audited per teacher access.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
class EvidenceRetentionIntegrationTests {

    @Autowired private EvidenceRetentionJob retention;
    @Autowired private EvidenceQueryService queries;
    @Autowired private MonitoringEvidenceRepository evidence;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @MockitoBean private EvidenceStorage storage;

    private Instant base;
    private long teacherId;
    private long studentId;
    private long sessionId;
    private long attemptId;
    private UUID evidenceId;

    @BeforeEach
    void setUp() {
        base = Instant.parse("2026-11-15T08:00:00Z");
        clock.setInstant(base);
        studentId = newUser(jdbc, "retstudent-" + UUID.randomUUID().toString().substring(0, 8));
        teacherId = newTeacherUser(jdbc, "retteacher-" + UUID.randomUUID().toString().substring(0, 8));
        // TEACHER owns the session (evidence read gate = owner + permission)
        sessionId = insertOpenSession(jdbc, teacherId, base);
        attemptId = jdbc.queryForObject(
                "INSERT INTO attempts (exam_session_id, user_id, attempt_number, status, "
                        + "started_at, deadline_at) VALUES (?,?,1,'IN_PROGRESS',?,?) RETURNING id",
                Long.class, sessionId, studentId,
                java.sql.Timestamp.from(base), java.sql.Timestamp.from(base.plusSeconds(3600)));
        evidenceId = UUID.randomUUID();
        when(storage.head(anyString())).thenReturn(Optional.of(
                new EvidenceStorage.ObjectHead(10)));
        when(storage.presignedGet(anyString(), any())).thenReturn("http://signed");
    }

    private void insertEvidence(Instant createdAt, Instant expiresAt, String status,
            String objectKey) {
        jdbc.update("INSERT INTO monitoring_evidence "
                        + "(id, exam_session_id, attempt_id, kind, status, provenance, object_key, "
                        + "content_type, sha256, bytes, captured_at, created_at, expires_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                evidenceId, sessionId, attemptId, "SNAPSHOT", status, "CLIENT_CAPTURE",
                objectKey, "image/jpeg", "ab".repeat(32), 10,
                java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(createdAt),
                java.sql.Timestamp.from(expiresAt));
    }

    @Test
    void expiryDeletesObjectThenMarksExpiredMetadataOnly() {
        insertEvidence(base.minusSeconds(3600), base.minusSeconds(60), "AVAILABLE",
                "snapshots/" + attemptId + "/old.jpg");
        retention.expireEvidence();

        verify(storage).delete("snapshots/" + attemptId + "/old.jpg");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM monitoring_evidence WHERE id = ?", String.class, evidenceId))
                .isEqualTo("EXPIRED");
        // EXPIRED rows never hand out URLs
        assertThatThrownBy(() -> queries.mintPlaybackUrl(teacherId, sessionId, evidenceId))
                .isInstanceOf(MonitoringException.class);
    }

    @Test
    void objectDeleteFailureKeepsRowNonExpiredUntilObjectReallyGone() {
        when(storage.delete(anyString())).thenThrow(new RuntimeException("minio down"));
        insertEvidence(base.minusSeconds(3600), base.minusSeconds(60), "AVAILABLE",
                "snapshots/" + attemptId + "/stuck.jpg");
        retention.expireEvidence();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM monitoring_evidence WHERE id = ?", String.class, evidenceId))
                .isEqualTo("AVAILABLE");
    }

    @Test
    void auditRowsSweepAfter90Days() {
        insertEvidence(base, base.plusSeconds(86400 * 30), "AVAILABLE", "k");
        jdbc.update("INSERT INTO monitoring_evidence_access (evidence_id, user_id, action, accessed_at) "
                        + "VALUES (?,?, 'URL_MINTED', ?)",
                evidenceId, teacherId, java.sql.Timestamp.from(base.minusSeconds(91 * 86400L)));
        retention.sweepAccessLog();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_evidence_access WHERE accessed_at < ?",
                Long.class, java.sql.Timestamp.from(base.minusSeconds(90 * 86400L)))).isZero();
    }

    @Test
    void urlMintIsAudited() {
        insertEvidence(base, base.plusSeconds(86400 * 30), "AVAILABLE",
                "snapshots/" + attemptId + "/fresh.jpg");
        var signed = queries.mintPlaybackUrl(teacherId, sessionId, evidenceId);
        assertThat(signed.url()).isEqualTo("http://signed");
        Long audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_evidence_access WHERE evidence_id = ? "
                        + "AND user_id = ? AND action = 'URL_MINTED'",
                Long.class, evidenceId, teacherId);
        assertThat(audits).isEqualTo(1L);
    }

    @Test
    void foreignTeacherAndStudentGetNoUrls() {
        insertEvidence(base, base.plusSeconds(86400 * 30), "AVAILABLE",
                "snapshots/" + attemptId + "/priv.jpg");
        long foreign = newTeacherUser(jdbc, "retforeign-" + UUID.randomUUID().toString().substring(0, 8));
        assertThatThrownBy(() -> queries.mintPlaybackUrl(foreign, sessionId, evidenceId))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("MONITORING_ACCESS_DENIED"));
        assertThatThrownBy(() -> queries.mintPlaybackUrl(studentId, sessionId, evidenceId))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("MONITORING_ACCESS_DENIED"));
    }

    @Test
    void demoPurgeDeletesObjectsAndExpiresRowsKeepingMetadata() {
        insertEvidence(base, base.plusSeconds(86400 * 30), "AVAILABLE",
                "snapshots/" + attemptId + "/purge.jpg");
        int deleted = retention.purgeSession(sessionId);
        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM monitoring_evidence WHERE id = ?", String.class, evidenceId))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_evidence WHERE id = ?", Long.class, evidenceId))
                .isEqualTo(1L);
    }

    @Test
    void rollingOrphansDeletedOnlyWhenNothingInFlight() {
        // no active jobs → orphan under rolling/ deleted
        when(storage.list(anyString())).thenReturn(List.of("rolling/" + attemptId + "/orphan.mp4"));
        int deleted = retention.reconcileRollingOrphans(attemptId);
        verify(storage, org.mockito.Mockito.atLeastOnce())
                .delete("rolling/" + attemptId + "/orphan.mp4");
        assertThat(deleted).isGreaterThanOrEqualTo(1);
    }
}
