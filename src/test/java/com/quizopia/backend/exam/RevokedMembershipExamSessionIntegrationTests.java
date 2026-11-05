package com.quizopia.backend.exam;

import com.quizopia.backend.academic.domain.model.GradeLevel;
import com.quizopia.backend.academic.domain.model.School;
import com.quizopia.backend.academic.domain.model.Subject;
import com.quizopia.backend.academic.domain.model.TeacherProfile;
import com.quizopia.backend.academic.repository.GradeLevelRepository;
import com.quizopia.backend.academic.repository.SchoolRepository;
import com.quizopia.backend.academic.repository.SubjectRepository;
import com.quizopia.backend.academic.repository.TeacherProfileRepository;
import com.quizopia.backend.attempt.application.SessionResultService;
import com.quizopia.backend.attempt.application.SessionStatisticsService;
import com.quizopia.backend.exam.application.ExamService;
import com.quizopia.backend.exam.application.ExamSessionService;
import com.quizopia.backend.exam.domain.model.SessionVisibility;
import com.quizopia.backend.exam.dto.CreateExamSessionRequest;
import com.quizopia.backend.exam.dto.CreateExamRequest;
import com.quizopia.backend.exam.dto.PublishedExamSummary;
import com.quizopia.backend.exam.dto.TeacherExamEditorResponse;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest;
import com.quizopia.backend.exam.exception.ExamErrorCode;
import com.quizopia.backend.exam.exception.ExamException;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.realtime.authorization.RealtimeAuthorizationService;
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
 * P0-2 reproduction (issue #4): the creator of an ORGANIZATION-workspace
 * Exam/Session must lose ALL management and read access when their
 * membership is SUSPENDED or REMOVED — across exam routes, nested session
 * routes, lifecycle, lists, results/statistics, roster and WebSocket.
 * Personal-workspace owners and org ADMINs keep their access.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class RevokedMembershipExamSessionIntegrationTests {

        @Autowired
        private UserRepository userRepo;
        @Autowired
        private SchoolRepository schoolRepo;
        @Autowired
        private GradeLevelRepository glRepo;
        @Autowired
        private SubjectRepository subjectRepo;
        @Autowired
        private TeacherProfileRepository teacherRepo;
        @Autowired
        private ExamService examService;
        @Autowired
        private ExamSessionService sessionService;
        @Autowired
        private SessionResultService sessionResultService;
        @Autowired
        private SessionStatisticsService statisticsService;
        @Autowired
        private RealtimeAuthorizationService realtimeAuthorizationService;
        @Autowired
        private JdbcTemplate jdbc;
        @jakarta.persistence.PersistenceContext
        private jakarta.persistence.EntityManager em;

        private Long creatorUserId;
        private Long adminUserId;
        private Long schoolId;
        private Long subjectId;
        private Long examId;
        private Long sessionId;

        @BeforeEach
        void setUp() {
                User creator = userRepo.saveAndFlush(
                                new User("rv-ex-creator", "rvec@test.com", "hash", "RV EX Creator"));
                creatorUserId = creator.getId();
                jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", creatorUserId);
                User admin = userRepo.saveAndFlush(
                                new User("rv-ex-admin", "rvea@test.com", "hash", "RV EX Admin"));
                adminUserId = admin.getId();
                jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                                + "SELECT ?, id FROM roles WHERE code = 'ACADEMIC_ADMIN'", adminUserId);

                School school = schoolRepo.saveAndFlush(new School("RV-EX-SCH", "RV EX School"));
                schoolId = school.getId();
                GradeLevel gl = glRepo.saveAndFlush(new GradeLevel(schoolId, "GL", "Grade"));
                Subject subject = subjectRepo.saveAndFlush(new Subject(schoolId, gl.getId(), "SUB", "Subject"));
                subjectId = subject.getId();
                teacherRepo.saveAndFlush(new TeacherProfile(creatorUserId, schoolId, "TC-RVEX"));

                // Org workspace with memberships: creator=EDITOR, admin=ADMIN.
                jdbc.update("INSERT INTO organizations (legacy_school_id, code, name) VALUES (?,?,?)",
                                schoolId, "RV-EX-ORG", "RV EX Org");
                Long orgWsId = jdbc.queryForObject(
                                "INSERT INTO workspaces (workspace_type, name, organization_id) "
                                                + "VALUES ('ORGANIZATION','RV EX Org',"
                                                + "(SELECT id FROM organizations WHERE legacy_school_id = " + schoolId
                                                + ")) RETURNING id", Long.class);
                jdbc.update("INSERT INTO workspace_memberships (workspace_id, user_id, role, status) "
                                + "VALUES (?,?, 'EDITOR', 'ACTIVE')", orgWsId, creatorUserId);
                jdbc.update("INSERT INTO workspace_memberships (workspace_id, user_id, role, status) "
                                + "VALUES (?,?, 'ADMIN', 'ACTIVE')", orgWsId, adminUserId);

                // Creator builds a published exam + session in the org workspace.
                long bank = insert("INSERT INTO question_banks (workspace_id, created_by_user_id, school_id, "
                                + "subject_id, owner_teacher_id, code, name) VALUES (" + orgWsId + ","
                                + creatorUserId + "," + schoolId + "," + subjectId + ","
                                + "(SELECT id FROM teacher_profiles WHERE user_id = " + creatorUserId
                                + "),'RV-B','B')");
                long q = insert("INSERT INTO questions (question_bank_id, code, status, created_by) "
                                + "VALUES (" + bank + ",'q1','ACTIVE'," + creatorUserId + ")");
                long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, "
                                + "content, difficulty, default_points, metadata, created_by) VALUES (" + q
                                + ",1,'SINGLE_CHOICE','rv q','MEDIUM',1,'{}'::jsonb," + creatorUserId + ")");
                for (Object[] o : new Object[][] { { "A", true, 0 }, { "B", false, 1 }, { "C", false, 2 },
                                { "D", false, 3 } }) {
                        insert("INSERT INTO question_options (question_version_id, option_key, content, "
                                        + "is_correct, position) VALUES (" + v + ",'" + o[0] + "','opt'," + o[1]
                                        + "," + o[2] + ")");
                }

                examId = examService.createExam(creatorUserId,
                                new CreateExamRequest(orgWsId, subjectId, null, "RV-EX", "RV Exam", null)).id();
                examService.updateDraftComposition(creatorUserId, examId,
                                new UpdateDraftCompositionRequest(0, 30, null, List.of(
                                                new UpdateDraftCompositionRequest.CompositionSectionRequest(
                                                                0, "S", null,
                                                                List.of(new UpdateDraftCompositionRequest.CompositionQuestionRequest(
                                                                                q, 0, null))))));
                PublishedExamSummary published = examService.publishExam(creatorUserId, examId, null);
                assertThat(published.status()).isEqualTo("PUBLISHED");

                sessionId = sessionService.createSession(creatorUserId, examId,
                                new CreateExamSessionRequest(examId, "RV-SES", "RV Session",
                                                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600),
                                                1, 30, SessionVisibility.PUBLIC, null)).id();
        }

        private void revokeCreator(String status) {
                jdbc.update("UPDATE workspace_memberships SET status = ? WHERE user_id = " + creatorUserId
                                + " AND workspace_id = (SELECT workspace_id FROM exams WHERE id = " + examId + ")",
                                status);
                em.clear(); // production revocation happens in another session
        }

        // ---------------- Exam management ----------------

        @Test
        void suspendedCreatorCannotReadExamDetail() {
                revokeCreator("SUSPENDED");
                assertThatThrownBy(() -> examService.getExamDetail(creatorUserId, examId))
                                .isInstanceOfSatisfying(ExamException.class,
                                                ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_NOT_FOUND));
        }

        @Test
        void removedCreatorCannotCompose() {
                revokeCreator("REMOVED");
                assertThatThrownBy(() -> examService.updateDraftComposition(creatorUserId, examId,
                                new UpdateDraftCompositionRequest(0, null, null, List.of())))
                                .isInstanceOf(ExamException.class);
        }

        @Test
        void removedCreatorCannotArchiveOrDuplicate() {
                revokeCreator("REMOVED");
                assertThatThrownBy(() -> examService.archiveExam(creatorUserId, examId))
                                .isInstanceOf(ExamException.class);
                assertThatThrownBy(() -> examService.duplicateExam(creatorUserId, examId))
                                .isInstanceOf(ExamException.class);
        }

        @Test
        void removedCreatorExamDisappearsFromList() {
                revokeCreator("REMOVED");
                var page = examService.listMyExams(creatorUserId, null, null, null, 0, 20, null);
                assertThat(page.items()).isEmpty();
        }

        // ---------------- Session management ----------------

        @Test
        void removedCreatorCannotReadSessionDetail() {
                revokeCreator("REMOVED");
                assertThatThrownBy(() -> sessionService.getSessionDetail(creatorUserId, examId, sessionId))
                                .isInstanceOf(ExamException.class);
        }

        @Test
        void removedCreatorCannotRunLifecycleOrClassesOrRoster() {
                revokeCreator("REMOVED");
                assertThatThrownBy(() -> sessionService.scheduleSession(creatorUserId, examId, sessionId))
                                .isInstanceOf(ExamException.class);
                assertThatThrownBy(() -> sessionService.listClasses(creatorUserId, examId, sessionId))
                                .isInstanceOf(ExamException.class);
                assertThatThrownBy(() -> sessionService.listRoster(creatorUserId, examId, sessionId))
                                .isInstanceOf(ExamException.class);
        }

        @Test
        void removedCreatorSessionDisappearsFromList() {
                revokeCreator("REMOVED");
                var page = sessionService.listSessions(creatorUserId, null, null, null, 0, 20, null);
                assertThat(page.items()).isEmpty();
        }

        // ---------------- Results / statistics / realtime ----------------

        @Test
        void removedCreatorCannotReadResultsOrStatistics() {
                revokeCreator("REMOVED");
                assertThatThrownBy(() -> sessionResultService.getAllBestResults(creatorUserId, "TEACHER", sessionId))
                                .isInstanceOf(Exception.class);
                assertThatThrownBy(() -> statisticsService.getStatistics(creatorUserId, "TEACHER", sessionId))
                                .isInstanceOf(Exception.class);
        }

        @Test
        void removedCreatorCannotSubscribeMonitorTopic() {
                revokeCreator("REMOVED");
                assertThatThrownBy(() -> realtimeAuthorizationService.authorizeTeacherTopic(creatorUserId, sessionId))
                                .isInstanceOf(Exception.class);
        }

        // ---------------- Valid access preserved ----------------

        @Test
        void orgAdminStillManagesExamAndSession() {
                revokeCreator("REMOVED");
                // admin did not create the exam — OWNER/ADMIN membership grants management
                TeacherExamEditorResponse detail = examService.getExamDetail(adminUserId, examId);
                assertThat(detail.status()).isEqualTo("PUBLISHED");
                sessionService.getSessionDetail(adminUserId, examId, sessionId);
                sessionResultService.getAllBestResults(adminUserId, "ACADEMIC_ADMIN", sessionId);
                statisticsService.getStatistics(adminUserId, "ACADEMIC_ADMIN", sessionId);
        }

        @Test
        void activeCreatorKeepsAccess() {
                revokeCreator("ACTIVE");
                examService.getExamDetail(creatorUserId, examId);
                sessionService.getSessionDetail(creatorUserId, examId, sessionId);
        }

        private long insert(String sql) {
                return jdbc.queryForObject(sql + " RETURNING id", Long.class);
        }
}
