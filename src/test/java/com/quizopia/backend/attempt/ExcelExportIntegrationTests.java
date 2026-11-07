package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.application.AttemptSubmitService;
import com.quizopia.backend.attempt.application.ExcelExportService;
import com.quizopia.backend.attempt.dto.SubmitRequest;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class ExcelExportIntegrationTests {

    @Autowired private ExcelExportService exportService;
    @Autowired private AttemptSubmitService submitService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private MutableClock clock;

    private long teacherUserId;
    private long sessionId;
    private String tag;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 6);
        clock.setInstant(Instant.parse("2026-07-06T08:00:00Z"));
        Instant now = Instant.parse("2026-07-06T08:00:00Z");
        teacherUserId = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('tch-" + tag + "','t" + tag + "@t.com','h','Teacher')");
        long tr = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + teacherUserId + "," + tr + ")");
        long gl = ins("INSERT INTO grade_levels (code, name) VALUES ('GL" + tag + "','G')");
        long subj = ins("INSERT INTO subjects (grade_level_id, code, name) VALUES (" + gl + ",'M','Math')");
        long bank = ins("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES (" + teacherUserId + "," + subj + ",'B" + tag + "','Bank')");
        long exam = ins("INSERT INTO exams (created_by_user_id, subject_id, code, title, status, published_at, total_points) VALUES (" + teacherUserId + "," + subj + ",'E" + tag + "','Exam','PUBLISHED',now(),1)");
        long sec = ins("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + exam + ",'S',0)");
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q" + tag + "'," + teacherUserId + ")");
        ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + teacherUserId + ")");
        long eq = ins("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + exam + "," + sec + "," + q + ",(SELECT id FROM question_versions WHERE question_id=" + q + "),'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
        sessionId = ins("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at, duration_minutes) VALUES (" + exam + ",'S" + tag + "','t','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + teacherUserId + ",'" + now.minusSeconds(3600) + "',60)");
    }

    @Test
    void emptySessionProducesValidWorkbook() throws Exception {
        byte[] bytes = exportService.export(teacherUserId, sessionId);
        assertThat(bytes).isNotEmpty();
        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getSheet("Results")).isNotNull();
            assertThat(wb.getSheet("Statistics")).isNotNull();
            // Results sheet: header row only (no data rows)
            assertThat(wb.getSheet("Results").getPhysicalNumberOfRows()).isEqualTo(1);
        }
    }

    @Test
    void teacherOwnerProducesValidResultsSheetWithSubmittedStudent() throws Exception {
        createStudentAndSubmit();
        em.flush(); em.clear();
        byte[] bytes = exportService.export(teacherUserId, sessionId);
        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet results = wb.getSheet("Results");
            assertThat(results).isNotNull();
            // Header + 1 data row
            assertThat(results.getPhysicalNumberOfRows()).isEqualTo(2);
            // Headers
            assertThat(results.getRow(0).getCell(0).getStringCellValue()).isEqualTo("No.");
            assertThat(results.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Student Code");
            assertThat(results.getRow(0).getCell(9).getStringCellValue()).isEqualTo("Status");
            // Score cell should be numeric
            assertThat(results.getRow(1).getCell(6).getCellType()).isEqualTo(CellType.NUMERIC);
        }
    }

    @Test
    void contentTypeAndFilename() {
        assertThat(ExcelExportService.contentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(ExcelExportService.filename(42L)).startsWith("quizopia-session-42-results-").endsWith(".xlsx");
    }

    @Test
    void studentDeniedExport() {
        // A genuine STUDENT (not the session creator) must be denied under the
        // resource-based policy: only the session's creator may export.
        // (The fixture teacher is the creator, so reusing that user would be
        // legitimately authorized regardless of the role string.)
        long su = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('sdx" + tag + "','sdx" + tag + "@t.com','h','Student')");
        long sr = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + su + "," + sr + ")");
        assertThatThrownBy(() -> exportService.export(su, sessionId))
                .isInstanceOf(AttemptException.class);
    }

    @Test
    void nonOwningTeacherDenied() {
        long other = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('ot','ot@t.com','h','OT')");
        long tr = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + other + "," + tr + ")");
        assertThatThrownBy(() -> exportService.export(other, sessionId))
                .isInstanceOf(AttemptException.class);
    }

    private void createStudentAndSubmit() {
        long su = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('stu" + UUID.randomUUID().toString().substring(0, 4) + "','s@t.com','h','Student')");
        long sr = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + su + "," + sr + ")");
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) VALUES (" + su + "," + sessionId + "," + teacherUserId + ")");
        long attemptId = attemptService.startAttempt(su, sessionId, new com.quizopia.backend.attempt.dto.StartAttemptRequest(null)).attemptId();
        em.flush(); em.clear();
        submitService.submitAttempt(su, attemptId, new SubmitRequest("exp-key-" + UUID.randomUUID()));
    }

    private long ins(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
