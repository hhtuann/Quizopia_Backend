package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.application.AttemptSubmitService;
import com.hhtuann.backend.attempt.application.ExcelExportService;
import com.hhtuann.backend.attempt.dto.SubmitRequest;
import com.hhtuann.backend.attempt.exception.AttemptException;
import com.hhtuann.backend.testsupport.MutableClock;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import com.hhtuann.backend.testsupport.TestClockConfig;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
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
        long school = ins("INSERT INTO schools (code, name) VALUES ('SC" + tag + "','School')");
        long gl = ins("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
        long subj = ins("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','Math')");
        ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacherUserId + "," + school + ",'TC" + tag + "')");
        long bank = ins("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + ",(SELECT id FROM teacher_profiles WHERE user_id=" + teacherUserId + "),'B" + tag + "','Bank')");
        long exam = ins("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + ",(SELECT id FROM teacher_profiles WHERE user_id=" + teacherUserId + "),'E" + tag + "','Exam')");
        long ver = ins("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',1,now()," + teacherUserId + ")");
        long sec = ins("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q" + tag + "'," + teacherUserId + ")");
        ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + teacherUserId + ")");
        long eq = ins("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q + ",(SELECT id FROM question_versions WHERE question_id=" + q + "),'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
        sessionId = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES (" + school + "," + ver + ",(SELECT id FROM teacher_profiles WHERE user_id=" + teacherUserId + "),'S" + tag + "','t','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + teacherUserId + ",'" + now.minusSeconds(3600) + "')");
    }

    @Test
    void emptySessionProducesValidWorkbook() throws Exception {
        byte[] bytes = exportService.export(teacherUserId, "TEACHER", sessionId);
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
        byte[] bytes = exportService.export(teacherUserId, "TEACHER", sessionId);
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
        assertThatThrownBy(() -> exportService.export(teacherUserId, "STUDENT", sessionId))
                .isInstanceOf(AttemptException.class);
    }

    @Test
    void nonOwningTeacherDenied() {
        long other = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('ot','ot@t.com','h','OT')");
        long tr = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + other + "," + tr + ")");
        assertThatThrownBy(() -> exportService.export(other, "TEACHER", sessionId))
                .isInstanceOf(AttemptException.class);
    }

    private void createStudentAndSubmit() {
        long su = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('stu" + UUID.randomUUID().toString().substring(0, 4) + "','s@t.com','h','Student')");
        long sr = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + su + "," + sr + ")");
        long school = jdbc.queryForObject("SELECT school_id FROM exam_sessions WHERE id = " + sessionId, Long.class);
        long sp = ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + su + "," + school + ",'SC" + tag + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + sessionId + "," + sp + "," + teacherUserId + ")");
        long attemptId = attemptService.startAttempt(su, sessionId, new com.hhtuann.backend.attempt.dto.StartAttemptRequest(null)).attemptId();
        em.flush(); em.clear();
        submitService.submitAttempt(su, attemptId, new SubmitRequest("exp-key-" + UUID.randomUUID()));
    }

    private long ins(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
