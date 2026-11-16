package com.quizopia.backend.monitoring;

import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.enableMonitoringWithPolicy;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenSession;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newTeacherUser;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.quizopia.backend.evidence.application.ClipService;
import com.quizopia.backend.evidence.application.EvidenceProperties;
import com.quizopia.backend.evidence.application.FfmpegRunner;
import com.quizopia.backend.evidence.domain.MonitoringEvidence;
import com.quizopia.backend.evidence.repository.MonitoringEvidenceRepository;
import com.quizopia.backend.media.storage.EvidenceStorage;
import com.quizopia.backend.media.storage.EvidenceStorage.ObjectHead;
import com.quizopia.backend.media.storage.EvidenceStorage.StoredObject;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * D3 (issue #93) clip extraction — coverage honesty with a stubbed ffmpeg
 * (the REAL ffmpeg path runs in {@code EvidenceClipRealFfmpegTests} + the
 * browser e2e): NO_COVERAGE never fabricates, real gaps surface as PARTIAL
 * with machine-readable coverage, recording-ON vs rolling modes pick the
 * right provenance, teacher auth matrix, idempotent clip reuse.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class,
        ClipServiceIntegrationTests.ClipTestConfig.class})
class ClipServiceIntegrationTests {

    /** Replaces the ffmpeg runner: writes a real temp MP4-shaped file. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ClipTestConfig {
        @Bean
        @Primary
        FfmpegRunner stubFfmpegRunner(EvidenceProperties properties) {
            return new FfmpegRunner(properties) {
                @Override
                public Result extract(List<SegmentInput> segments) {
                    try {
                        Path out = Files.createTempFile("quizopia-clip-test", ".mp4");
                        Files.write(out, new byte[]{0, 0, 0, 18, 'f', 't', 'y', 'p',
                                'i', 's', 'o', 'm', 0, 0, 2, 0, 0, 0});
                        return new Result.Ok(out, 18);
                    } catch (java.io.IOException e) {
                        return new Result.Failed("IO");
                    }
                }
            };
        }
    }

    @Autowired private ClipService clips;
    @Autowired private MonitoringEvidenceRepository evidence;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @MockitoBean private EvidenceStorage storage;

    private Instant base;
    private long teacherId;
    private long foreignTeacherId;
    private long studentId;
    private long sessionId;
    private long attemptId;
    private long alertId;

    @BeforeEach
    void setUp() throws Exception {
        base = Instant.parse("2026-11-15T08:00:00Z");
        clock.setInstant(base);
        studentId = newUser(jdbc, "clipstudent-" + UUID.randomUUID().toString().substring(0, 8));
        teacherId = newTeacherUser(jdbc, "clipteacher-" + UUID.randomUUID().toString().substring(0, 8));
        foreignTeacherId = newTeacherUser(jdbc, "clipforeign-" + UUID.randomUUID().toString().substring(0, 8));
        sessionId = insertOpenSession(jdbc, teacherId, base);
        attemptId = jdbc.queryForObject(
                "INSERT INTO attempts (exam_session_id, user_id, attempt_number, status, "
                        + "started_at, deadline_at) VALUES (?,?,1,'IN_PROGRESS',?,?) RETURNING id",
                Long.class, sessionId, studentId,
                java.sql.Timestamp.from(base), java.sql.Timestamp.from(base.plusSeconds(3600)));
        alertId = insertAlert(base.minusSeconds(10), base.plusSeconds(10));
        when(storage.head(anyString())).thenReturn(Optional.of(new ObjectHead(1)));
        when(storage.presignedGet(anyString(), any())).thenReturn("http://storage/signed");
        when(storage.put(anyString(), any(), anyString()))
                .thenAnswer(inv -> {
                    byte[] bytes = inv.getArgument(1);
                    return new StoredObject("deadbeef".repeat(8), bytes == null ? 0 : bytes.length);
                });
    }

    private UUID bindingId;

    private void insertBindingIfNeeded() {
        if (bindingId != null) {
            return;
        }
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
    }

    private long insertAlert(Instant first, Instant last) {
        return jdbc.queryForObject(
                "INSERT INTO monitoring_ai_alerts (attempt_id, exam_session_id, user_id, "
                        + "alert_type, status, first_detected_at, last_detected_at, "
                        + "detection_count, model_id, model_version, provenance) "
                        + "VALUES (?,?,?,?, 'OPEN', ?, ?, 1, 'yolo26s', '8.4.64-onnx', "
                        + "'CLIENT_MODEL') RETURNING id",
                Long.class, attemptId, sessionId, studentId, "PHONE_DETECTED",
                java.sql.Timestamp.from(first), java.sql.Timestamp.from(last));
    }

    private void insertSegment(String kind, String egressId, Instant from, Instant to,
            String objectKey) {
        insertBindingIfNeeded();
        jdbc.update("INSERT INTO monitoring_recording_jobs "
                        + "(id, exam_session_id, attempt_id, binding_id, kind, egress_id, room_name, "
                        + "track_sid, status, pulse_index, started_at, ended_at, object_key, bytes, duration_ms) "
                        + "VALUES (?,?,?,?,?,?,?,?,'COMPLETE',0,?,?,?,5000,60000)",
                UUID.randomUUID(), sessionId, attemptId, bindingId, kind, egressId,
                "room-1", "TR_1",
                java.sql.Timestamp.from(from), java.sql.Timestamp.from(to), objectKey);
    }

    @Test
    void fullCoverageSingleSegmentProducesAvailableClip() {
        enableMonitoringWithPolicy(jdbc, sessionId, false, true, true);
        insertSegment("FULL", "EG_FULL_1", base.minusSeconds(300), base.plusSeconds(300),
                "recordings/" + attemptId + "/full.mp4");

        MonitoringEvidence clip = clips.requestClip(teacherId, sessionId, alertId);

        assertThat(clip.getStatus()).isEqualTo("AVAILABLE");
        assertThat(clip.getProvenance()).isEqualTo("FULL_RECORDING");
        assertThat(clip.getObjectKey()).isEqualTo("clips/" + clip.getId() + ".mp4");
        assertThat(clip.getBytes()).isEqualTo(18L);
        assertThat(clip.getError()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempt_activity_events WHERE event_type='EVIDENCE_CLIP_READY'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void gapInsideWindowYieldsPartialWithMachineReadableGaps() {
        enableMonitoringWithPolicy(jdbc, sessionId, false, false, true);
        // rolling pulses covering [−60,−10] and [+5,+90]; gap of 15s inside
        insertSegment("ROLLING", "EG_R1", base.minusSeconds(60), base.minusSeconds(10),
                "rolling/" + attemptId + "/r1.mp4");
        insertSegment("ROLLING", "EG_R2", base.plusSeconds(5), base.plusSeconds(90),
                "rolling/" + attemptId + "/r2.mp4");

        MonitoringEvidence clip = clips.requestClip(teacherId, sessionId, alertId);

        assertThat(clip.getStatus()).isEqualTo("PARTIAL");
        assertThat(clip.getProvenance()).isEqualTo("ROLLING_BUFFER");
        assertThat(clip.getCoverageJson()).contains("\"gaps\"").contains("\"from\":\"2026-11-15T07:59:50Z\"");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempt_activity_events WHERE event_type='EVIDENCE_CLIP_FAILED' "
                        + "AND attempt_id = ?", Long.class, attemptId)).isZero();
    }

    @Test
    void noFootageIsHonestNoCoverageNeverFabricated() {
        enableMonitoringWithPolicy(jdbc, sessionId, false, false, true);

        MonitoringEvidence clip = clips.requestClip(teacherId, sessionId, alertId);

        assertThat(clip.getStatus()).isEqualTo("FAILED");
        assertThat(clip.getError()).isEqualTo("NO_COVERAGE");
        assertThat(clip.getObjectKey()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempt_activity_events WHERE event_type='EVIDENCE_CLIP_FAILED' "
                        + "AND attempt_id = ?", Long.class, attemptId)).isEqualTo(1L);
    }

    @Test
    void wrongKindFootageDoesNotCover() {
        enableMonitoringWithPolicy(jdbc, sessionId, false, true, true); // FULL mode
        // only ROLLING rows exist — a FULL-recording clip has no coverage
        insertSegment("ROLLING", "EG_R9", base.minusSeconds(60), base.plusSeconds(60),
                "rolling/" + attemptId + "/r9.mp4");

        MonitoringEvidence clip = clips.requestClip(teacherId, sessionId, alertId);
        assertThat(clip.getStatus()).isEqualTo("FAILED");
        assertThat(clip.getError()).isEqualTo("NO_COVERAGE");
    }

    @Test
    void clipRequestIsIdempotentWhileAvailable() {
        enableMonitoringWithPolicy(jdbc, sessionId, false, true, true);
        insertSegment("FULL", "EG_FULL_2", base.minusSeconds(300), base.plusSeconds(300),
                "recordings/" + attemptId + "/full2.mp4");

        MonitoringEvidence first = clips.requestClip(teacherId, sessionId, alertId);
        MonitoringEvidence second = clips.requestClip(teacherId, sessionId, alertId);
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_evidence WHERE alert_id = ?", Long.class, alertId))
                .isEqualTo(1L);
    }

    @Test
    void foreignTeacherGetsGenericDenial() {
        enableMonitoringWithPolicy(jdbc, sessionId, false, true, true);
        insertSegment("FULL", "EG_FULL_3", base.minusSeconds(300), base.plusSeconds(300),
                "recordings/" + attemptId + "/full3.mp4");
        assertThatThrownBy(() -> clips.requestClip(foreignTeacherId, sessionId, alertId))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("MONITORING_ACCESS_DENIED"));
    }

    @Test
    void clipsDisabledPolicyRejects() {
        enableMonitoringWithPolicy(jdbc, sessionId, false, false, false);
        assertThatThrownBy(() -> clips.requestClip(teacherId, sessionId, alertId))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("EVIDENCE_CLIP_REJECTED"));
    }

    @Test
    void ffmpegUnavailableFailsHonestly() {
        enableMonitoringWithPolicy(jdbc, sessionId, false, true, true);
        insertSegment("FULL", "EG_FULL_4", base.minusSeconds(300), base.plusSeconds(300),
                "recordings/" + attemptId + "/full4.mp4");
        // make the put path fail → STORAGE_UNAVAILABLE honesty (extract still
        // ran; the runner itself is stubbed at the config level)
        when(storage.put(anyString(), any(), anyString()))
                .thenThrow(new RuntimeException("minio down"));
        MonitoringEvidence clip = clips.requestClip(teacherId, sessionId, alertId);
        assertThat(clip.getStatus()).isEqualTo("FAILED");
        assertThat(clip.getError()).isEqualTo("STORAGE_UNAVAILABLE");
    }
}
