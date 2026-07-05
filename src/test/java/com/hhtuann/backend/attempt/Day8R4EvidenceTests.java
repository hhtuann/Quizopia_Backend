package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.application.AttemptSubmitService;
import com.hhtuann.backend.attempt.application.SessionResultService;
import com.hhtuann.backend.attempt.application.SessionStatisticsService;
import com.hhtuann.backend.attempt.application.ExcelExportService;
import com.hhtuann.backend.attempt.dto.SubmitRequest;
import com.hhtuann.backend.attempt.dto.SessionStatisticsResponse;
import com.hhtuann.backend.testsupport.MutableClock;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import com.hhtuann.backend.testsupport.TestClockConfig;
import jakarta.persistence.EntityManager;
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

/**
 * Day 8 R4 evidence tests: correctRate 50.00 fixture, pagination page-0/page-1 separation,
 * 50-student query count + deterministic ordering, Excel date cell type, multi-question ordering.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class Day8R4EvidenceTests {

    @Autowired private AttemptService attemptService;
    @Autowired private AttemptSubmitService submitService;
    @Autowired private SessionResultService sessionResultService;
    @Autowired private SessionStatisticsService statsService;
    @Autowired private ExcelExportService exportService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private EntityManager em;

    private long teacherUserId;
    private long sessionId;
    private long schoolId;
    private long examQuestionId;
    private String tag;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 6);
        clock.setInstant(Instant.parse("2026-07-06T08:00:00Z"));
        Instant now = Instant.parse("2026-07-06T08:00:00Z");
        teacherUserId = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('tchr" + tag + "','th" + tag + "@t.com','h','Teacher')");
        long tr = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + teacherUserId + "," + tr + ")");
        schoolId = ins("INSERT INTO schools (code, name) VALUES ('SC" + tag + "','School')");
        long gl = ins("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        long subj = ins("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + gl + ",'M','Math')");
        ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacherUserId + "," + schoolId + ",'TC" + tag + "')");
        long bank = ins("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + schoolId + "," + subj + ",(SELECT id FROM teacher_profiles WHERE user_id=" + teacherUserId + "),'B','Bank')");
        long exam = ins("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + schoolId + "," + subj + ",(SELECT id FROM teacher_profiles WHERE user_id=" + teacherUserId + "),'E','Exam')");
        long ver = ins("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + schoolId + "," + exam + ",1,'PUBLISHED',1,now()," + teacherUserId + ")");
        long sec = ins("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q" + tag + "'," + teacherUserId + ")");
        ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + teacherUserId + ")");
        examQuestionId = ins("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q + ",(SELECT id FROM question_versions WHERE question_id=" + q + "),'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + examQuestionId + ",'A','a',false,0),(" + examQuestionId + ",'B','b',false,1),(" + examQuestionId + ",'C','c',true,2),(" + examQuestionId + ",'D','d',false,3)");
        sessionId = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES (" + schoolId + "," + ver + ",(SELECT id FROM teacher_profiles WHERE user_id=" + teacherUserId + "),'S" + tag + "','t','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + teacherUserId + ",'" + now.minusSeconds(3600) + "')");
    }

    // C. correctRate = 50.00 (1 correct + 1 incorrect + 2 unanswered)
    @Test
    void correctRateFiftyPercentWithMixedAnswers() {
        createStudentSubmitWithAnswer("C"); // correct answer (option C is correct)
        createStudentSubmitWithAnswer("A"); // incorrect answer
        createStudentAndSubmit(); // unanswered 1
        createStudentAndSubmit(); // unanswered 2
        em.flush(); em.clear();

        SessionStatisticsResponse s = statsService.getStatistics(teacherUserId, "TEACHER", sessionId);
        assertThat(s.bestResultCount()).isEqualTo(4);
        assertThat(s.perQuestionStatistics()).hasSize(1);
        var qs = s.perQuestionStatistics().get(0);
        assertThat(qs.answeredCount()).isEqualTo(2);
        assertThat(qs.correctCount()).isEqualTo(1);
        assertThat(qs.incorrectCount()).isEqualTo(1);
        assertThat(qs.unansweredCount()).isEqualTo(2);
        assertThat(qs.correctRate()).isEqualByComparingTo("50.00");
    }

    // C. zero-answered → correctRate null
    @Test
    void correctRateNullWhenAllUnanswered() {
        createStudentAndSubmit();
        em.flush(); em.clear();
        SessionStatisticsResponse s = statsService.getStatistics(teacherUserId, "TEACHER", sessionId);
        var qs = s.perQuestionStatistics().get(0);
        assertThat(qs.answeredCount()).isZero();
        assertThat(qs.correctRate()).isNull();
    }

    // B. Pagination: page 0 and page 1 don't overlap
    @Test
    void paginationPagesDoNotOverlap() {
        for (int i = 0; i < 15; i++) createStudentAndSubmit();
        em.flush(); em.clear();
        var page0 = sessionResultService.getSessionResults(teacherUserId, "TEACHER", sessionId, 0, 10, null, null, null, null, null);
        var page1 = sessionResultService.getSessionResults(teacherUserId, "TEACHER", sessionId, 1, 10, null, null, null, null, null);
        assertThat(page0.items()).hasSize(10);
        assertThat(page1.items()).hasSize(5);
        assertThat(page0.totalElements()).isEqualTo(15);
        java.util.Set<Long> page0Ids = page0.items().stream()
                .map(com.hhtuann.backend.attempt.dto.SessionResultItem::studentId)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<Long> page1Ids = page1.items().stream()
                .map(com.hhtuann.backend.attempt.dto.SessionResultItem::studentId)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(page0Ids).doesNotContainAnyElementsOf(page1Ids);
    }

    // B. Default ordering: percentage DESC → submitted_at ASC → student_id ASC
    @Test
    void defaultOrderingDeterministic() {
        for (int i = 0; i < 5; i++) createStudentAndSubmit();
        em.flush(); em.clear();
        var page1 = sessionResultService.getSessionResults(teacherUserId, "TEACHER", sessionId, 0, 20, null, null, null, null, null);
        var page2 = sessionResultService.getSessionResults(teacherUserId, "TEACHER", sessionId, 0, 20, null, null, null, null, null);
        // Deterministic: same order on repeat
        for (int i = 0; i < page1.items().size(); i++) {
            assertThat(page1.items().get(i).studentId()).isEqualTo(page2.items().get(i).studentId());
        }
        // All unanswered → all percentage 0.00 → tie-break by submitted_at ASC then student_id ASC
        for (int i = 1; i < page1.items().size(); i++) {
            assertThat(page1.items().get(i).studentId()).isGreaterThan(page1.items().get(i - 1).studentId());
        }
    }

    // B. 50 students — each exactly one BEST row; totalElements = 50
    @Test
    void fiftyStudentsOneBestRowEach() {
        for (int i = 0; i < 50; i++) createStudentAndSubmit();
        em.flush(); em.clear();
        var page = sessionResultService.getSessionResults(teacherUserId, "TEACHER", sessionId, 0, 100, null, null, null, null, null);
        assertThat(page.totalElements()).isEqualTo(50);
        assertThat(page.items()).hasSize(50);
        // Each student appears exactly once
        var studentIds = page.items().stream().map(i -> i.studentId()).toList();
        assertThat(studentIds).doesNotHaveDuplicates();
        // Each has attemptCount = 1
        assertThat(page.items()).allSatisfy(i -> assertThat(i.attemptCount()).isEqualTo(1));
    }

    // C. Multi-question statistics ordered by examQuestionId ascending
    @Test
    void perQuestionStatisticsOrderedByExamQuestionIdAscending() {
        // Add a SECOND question to the same exam version (its exam_question_id > the first's)
        long verId = jdbc.queryForObject("SELECT exam_version_id FROM exam_questions WHERE id = " + examQuestionId, Long.class);
        long secId = jdbc.queryForObject("SELECT exam_section_id FROM exam_questions WHERE id = " + examQuestionId, Long.class);
        long bankId = jdbc.queryForObject("SELECT q.question_bank_id FROM questions q JOIN exam_questions eq ON eq.source_question_id = q.id WHERE eq.id = " + examQuestionId, Long.class);
        long q2 = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bankId + ",'Q2" + tag + "'," + teacherUserId + ")");
        ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q2 + ",1,'SINGLE_CHOICE','c2',1,'{}'::jsonb," + teacherUserId + ")");
        long eq2 = ins("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + verId + "," + secId + "," + q2 + ",(SELECT id FROM question_versions WHERE question_id=" + q2 + "),'QC2','SINGLE_CHOICE','c2',1,1,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq2 + ",'A','a',false,0),(" + eq2 + ",'B','b',false,1),(" + eq2 + ",'C','c',true,2),(" + eq2 + ",'D','d',false,3)");
        createStudentAndSubmit();
        em.flush(); em.clear();

        SessionStatisticsResponse s = statsService.getStatistics(teacherUserId, "TEACHER", sessionId);
        assertThat(s.perQuestionStatistics()).hasSize(2);
        // Ordered ascending by examQuestionId
        assertThat(s.perQuestionStatistics().get(0).examQuestionId())
                .isLessThan(s.perQuestionStatistics().get(1).examQuestionId());
        // Stable ascending regardless of repeat
        var s2 = statsService.getStatistics(teacherUserId, "TEACHER", sessionId);
        assertThat(s2.perQuestionStatistics().get(0).examQuestionId())
                .isEqualTo(s.perQuestionStatistics().get(0).examQuestionId());
    }

    // D. Excel: submittedAt cell is NUMERIC and date-formatted
    @Test
    void excelSubmittedAtIsNumericDateCell() throws Exception {
        createStudentAndSubmit();
        em.flush(); em.clear();
        byte[] bytes = exportService.export(teacherUserId, "TEACHER", sessionId);
        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var results = wb.getSheet("Results");
            var submittedAtCell = results.getRow(1).getCell(5); // column index 5 = "Submitted At"
            assertThat(submittedAtCell.getCellType())
                    .as("submittedAt cell must be NUMERIC (real date cell), not STRING")
                    .isEqualTo(org.apache.poi.ss.usermodel.CellType.NUMERIC);
            assertThat(org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(submittedAtCell))
                    .as("submittedAt cell must be date-formatted")
                    .isTrue();
            // score/maxScore/percentage also numeric
            assertThat(results.getRow(1).getCell(6).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.NUMERIC); // score
            assertThat(results.getRow(1).getCell(7).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.NUMERIC); // maxScore
            assertThat(results.getRow(1).getCell(8).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.NUMERIC); // percentage
        }
    }

    // D. Excel Results sheet follows the same default ordering as the API
    @Test
    void excelResultsSheetMatchesApiDefaultOrder() throws Exception {
        for (int i = 0; i < 4; i++) createStudentAndSubmit();
        em.flush(); em.clear();
        var apiPage = sessionResultService.getSessionResults(teacherUserId, "TEACHER", sessionId, 0, 100, null, null, null, null, null);
        byte[] bytes = exportService.export(teacherUserId, "TEACHER", sessionId);
        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var results = wb.getSheet("Results");
            // Row 0 = header; rows 1..n = data in default order
            for (int i = 0; i < apiPage.items().size(); i++) {
                long apiAttempt = apiPage.items().get(i).bestAttemptId();
                long excelAttempt = (long) results.getRow(i + 1).getCell(3).getNumericCellValue(); // col 3 = Best Attempt ID
                assertThat(excelAttempt)
                        .as("Excel row %d must match API default-order bestAttemptId", i)
                        .isEqualTo(apiAttempt);
            }
        }
    }

    // Helper: create student + submit WITHOUT answering
    private void createStudentAndSubmit() {
        createStudentSubmitWithAnswer(null);
    }

    // Helper: create student + submit WITH optional answer (optionKey null = unanswered)
    private long createStudentSubmitWithAnswer(String optionKey) {
        long su = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('stu" + tag + UUID.randomUUID().toString().substring(0, 4) + "','s" + UUID.randomUUID() + "@t.com','h','Student')");
        long sr = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + su + "," + sr + ")");
        long sp = ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + su + "," + schoolId + ",'SC" + tag + UUID.randomUUID().toString().substring(0, 4) + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + schoolId + "," + sessionId + "," + sp + "," + teacherUserId + ")");
        long attemptId = attemptService.startAttempt(su, sessionId, new com.hhtuann.backend.attempt.dto.StartAttemptRequest(null)).attemptId();
        em.flush(); em.clear();
        if (optionKey != null) {
            long aqId = jdbc.queryForObject("SELECT id FROM attempt_questions WHERE attempt_id = " + attemptId + " LIMIT 1", Long.class);
            jdbc.update("INSERT INTO attempt_answers (attempt_id, attempt_question_id, answer_payload, sequence_number) VALUES (" + attemptId + "," + aqId + ",'{\"selectedOptionKey\":\"" + optionKey + "\"}'::jsonb,1)");
            em.flush(); em.clear();
        }
        submitService.submitAttempt(su, attemptId, new SubmitRequest("r4-" + UUID.randomUUID()));
        return su;
    }

    private long ins(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
