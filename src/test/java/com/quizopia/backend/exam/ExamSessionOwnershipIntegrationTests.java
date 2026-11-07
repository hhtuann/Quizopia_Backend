package com.quizopia.backend.exam;

import com.quizopia.backend.exam.application.ExamService;
import com.quizopia.backend.exam.application.ExamSessionParticipantService;
import com.quizopia.backend.exam.application.ExamSessionService;
import com.quizopia.backend.exam.dto.CreateExamRequest;
import com.quizopia.backend.exam.dto.CreateExamSessionRequest;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest.CompositionQuestionRequest;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest.CompositionSectionRequest;
import com.quizopia.backend.exam.dto.UpdateExamSessionRequest;
import com.quizopia.backend.exam.exception.ExamErrorCode;
import com.quizopia.backend.exam.exception.ExamException;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Creator-only session access (ported from the revoked-membership era, issue
 * #4 P0-2): in the user-centric model ownership is {@code created_by == caller}.
 * A non-creator TEACHER gets 403 {@code EXAM_SESSION_ACCESS_DENIED} on session
 * get/update/participant management (and 404 when the session does not exist);
 * the creator keeps full access. Replaces the organization-membership
 * revocation semantics removed with workspaces.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ExamSessionOwnershipIntegrationTests {

    @Autowired private UserRepository userRepo;
    @Autowired private ExamService examService;
    @Autowired private ExamSessionService sessionService;
    @Autowired private ExamSessionParticipantService participantService;
    @Autowired private JdbcTemplate jdbc;

    private Long creatorUserId;
    private Long otherTeacherUserId;
    private Long examId;
    private Long sessionId;
    private Long participantRowId;

    @BeforeEach
    void setUp() {
        creatorUserId = teacher("own-ex-creator", "OWN EX Creator");
        otherTeacherUserId = teacher("own-ex-other", "OWN EX Other");

        long bank = insert("INSERT INTO question_banks (created_by_user_id, code, name) VALUES ("
                + creatorUserId + ",'OWN-B','Bank')");
        long q = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) "
                + "VALUES (" + bank + ",'q1','ACTIVE',1," + creatorUserId + ")");
        long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, "
                + "difficulty, default_points, metadata, created_by) VALUES (" + q
                + ",1,'SINGLE_CHOICE','own q','MEDIUM',1,'{}'::jsonb," + creatorUserId + ")");
        for (Object[] o : new Object[][] {{"A", true, 0}, {"B", false, 1}, {"C", false, 2}, {"D", false, 3}}) {
            jdbc.update("INSERT INTO question_options (question_version_id, option_key, content, is_correct, "
                    + "position) VALUES (" + v + ",'" + o[0] + "','opt'," + o[1] + "," + o[2] + ")");
        }

        examId = examService.createExam(creatorUserId,
                new CreateExamRequest(null, null, "OWN-EX", "Ownership Exam", null)).id();
        examService.updateDraftComposition(creatorUserId, examId,
                new UpdateDraftCompositionRequest(0, 30, null, List.of(
                        new CompositionSectionRequest(0, "S", null,
                                List.of(new CompositionQuestionRequest(q, 0, null))))));
        examService.publishExam(creatorUserId, examId, null);

        sessionId = sessionService.createSession(creatorUserId, examId,
                new CreateExamSessionRequest(examId, "OWN-SES", "Ownership Session",
                        Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 1, 30, null, null)).id();

        long studentUserId = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('own-ex-stu','ownstu@test.com','hash','OWN EX Student')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'STUDENT'",
                studentUserId);
        participantRowId = insert("INSERT INTO exam_session_participants (exam_session_id, user_id, added_by) "
                + "VALUES (" + sessionId + "," + studentUserId + "," + creatorUserId + ")");
    }

    // ---------------- Creator keeps access ----------------

    @Test
    void creatorReadsUpdatesAndListsParticipants() {
        var detail = sessionService.getSessionDetail(creatorUserId, examId, sessionId);
        assertThat(detail.id()).isEqualTo(sessionId);
        Integer version = jdbc.queryForObject("SELECT version FROM exam_sessions WHERE id = ?",
                Integer.class, sessionId);
        sessionService.updateSession(creatorUserId, examId, sessionId, new UpdateExamSessionRequest(
                version, "Renamed", Instant.now().minusSeconds(60), Instant.now().plusSeconds(7200), 2));
        var participants = participantService.listParticipants(creatorUserId, sessionId, null, 0, 20, null);
        assertThat(participants.items()).hasSize(1);
    }

    // ---------------- Non-creator teacher: 403 on get/update/participants ----------------

    @Test
    void nonCreatorCannotReadSessionDetail() {
        assertThatThrownBy(() -> sessionService.getSessionDetail(otherTeacherUserId, examId, sessionId))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED));
    }

    @Test
    void nonCreatorCannotUpdateSession() {
        Integer version = jdbc.queryForObject("SELECT version FROM exam_sessions WHERE id = ?",
                Integer.class, sessionId);
        assertThatThrownBy(() -> sessionService.updateSession(otherTeacherUserId, examId, sessionId,
                new UpdateExamSessionRequest(version, "Hijacked", Instant.now().minusSeconds(60),
                        Instant.now().plusSeconds(7200), 2)))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED));
    }

    @Test
    void nonCreatorCannotListOrBlockParticipants() {
        assertThatThrownBy(() -> participantService.listParticipants(otherTeacherUserId, sessionId, null, 0, 20, null))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED));
        assertThatThrownBy(() -> participantService.blockParticipant(otherTeacherUserId, sessionId, participantRowId))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED));
        assertThatThrownBy(() -> participantService.unblockParticipant(otherTeacherUserId, sessionId, participantRowId))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED));
    }

    @Test
    void nonCreatorCannotRunLifecycleOrClassesOrRoster() {
        assertThatThrownBy(() -> sessionService.scheduleSession(otherTeacherUserId, examId, sessionId))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED));
        assertThatThrownBy(() -> sessionService.listClasses(otherTeacherUserId, examId, sessionId))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED));
        assertThatThrownBy(() -> sessionService.listRoster(otherTeacherUserId, examId, sessionId))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ExamErrorCode.EXAM_SESSION_ACCESS_DENIED));
    }

    // ---------------- 404 anti-enumeration on absent sessions ----------------

    @Test
    void absentSessionYieldsNotFoundForAnyone() {
        assertThatThrownBy(() -> sessionService.getSessionDetail(creatorUserId, examId, 999_999_999L))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ExamErrorCode.EXAM_SESSION_NOT_FOUND));
        assertThatThrownBy(() -> sessionService.getSessionDetail(otherTeacherUserId, examId, 999_999_999L))
                .isInstanceOfSatisfying(ExamException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ExamErrorCode.EXAM_SESSION_NOT_FOUND));
    }

    // ---------------- Helpers ----------------

    private Long teacher(String username, String displayName) {
        User user = userRepo.saveAndFlush(new User(username, username + "@test.com", "hash", displayName));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'TEACHER'",
                user.getId());
        return user.getId();
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }

}
