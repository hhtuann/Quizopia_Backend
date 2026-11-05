package com.quizopia.backend.question;

import com.quizopia.backend.academic.domain.model.GradeLevel;
import com.quizopia.backend.academic.domain.model.School;
import com.quizopia.backend.academic.domain.model.Subject;
import com.quizopia.backend.academic.repository.GradeLevelRepository;
import com.quizopia.backend.academic.repository.SchoolRepository;
import com.quizopia.backend.academic.repository.SubjectRepository;
import com.quizopia.backend.exam.application.ExamService;
import com.quizopia.backend.exam.dto.PublishedExamSummary;
import com.quizopia.backend.exam.dto.PublishExamRequest;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest.CompositionQuestionRequest;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest.CompositionSectionRequest;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.question.application.MarkdownQuestionImportService;
import com.quizopia.backend.question.application.QuestionBankService;
import com.quizopia.backend.question.application.QuestionService;
import com.quizopia.backend.question.domain.markdown.QuizMarkdownParseResult;
import com.quizopia.backend.question.domain.markdown.QuizMarkdownParser;
import com.quizopia.backend.question.domain.model.QuestionDifficulty;
import com.quizopia.backend.question.dto.CreateQuestionBankRequest;
import com.quizopia.backend.question.dto.ImportResponse;
import com.quizopia.backend.question.dto.RowError;
import com.quizopia.backend.question.dto.UpdateQuestionRequest;
import com.quizopia.backend.question.exception.QuestionErrorCode;
import com.quizopia.backend.question.exception.QuestionException;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.workspace.application.WorkspaceProvisioningService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-level integration tests for the Teacher-only Quiz Markdown
 * Question Bank import (P2-C, issues #18 + #10): persistence shape, the
 * bank-contract preflight (#10: MULTIPLE_CHOICE {@code >=2}, canonical
 * numeric token), atomic all-or-nothing semantics, limits, workspace
 * authorization (personal bank without TeacherProfile, revoked membership)
 * and the Markdown → import → edit → publish round trip.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class MarkdownQuestionImportServiceIntegrationTests {

    private static final Pattern QU_CODE = Pattern.compile("^QU[0-9]{8}$");

    /** One valid document covering all four grammar types. */
    private static final String FOUR_TYPES_DOC = """
            Câu 1 [SINGLE_CHOICE]: 2 + 2 bằng bao nhiêu?
            *A. 4
            B. 3
            C. 5
            D. 6
            Lời giải: 2 + 2 = 4

            Câu 2 [MULTIPLE_CHOICE]: Chọn các số nguyên tố.
            *A. 2
            B. 4
            *C. 5
            D. 9

            Câu 3 [TRUE_FALSE_MATRIX]: Đánh dấu đúng/sai.
            *A. HTTP thuộc tầng Application.
            B. TCP thuộc tầng Application.
            *C. HTTPS dùng được TLS.
            D. UDP đảm bảo delivery.

            Câu 4 [NUMERIC_FILL]: 10 / 4 (viết hai chữ số thập phân)?
            Đáp án: 2.50
            Lời giải: `10 / 4 = 2.5`.
            """;

    @Autowired
    private MarkdownQuestionImportService importService;
    @Autowired
    private QuestionBankService bankService;
    @Autowired
    private QuestionService questionService;
    @Autowired
    private ExamService examService;
    @Autowired
    private UserRepository userRepo;
    @Autowired
    private SchoolRepository schoolRepo;
    @Autowired
    private GradeLevelRepository glRepo;
    @Autowired
    private SubjectRepository subjectRepo;
    @Autowired
    private WorkspaceProvisioningService workspaceProvisioningService;
    @Autowired
    private JdbcTemplate jdbc;
    @jakarta.persistence.PersistenceContext
    private EntityManager em;

    private final QuizMarkdownParser parser = new QuizMarkdownParser();

    private Long teacherUserId;
    private Long schoolId;
    private Long subjectId;

    @BeforeEach
    void setUp() {
        User teacher = userRepo.saveAndFlush(
                new User("md-import-teacher", "mdit@test.com", "hash", "MD Import Teacher"));
        teacherUserId = teacher.getId();
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacherUserId);

        School school = schoolRepo.saveAndFlush(new School("MD-IMP-SCH", "MD Import School"));
        schoolId = school.getId();
        GradeLevel gl = glRepo.saveAndFlush(new GradeLevel(schoolId, "GL-MDI", "Grade"));
        Subject subject = subjectRepo.saveAndFlush(new Subject(schoolId, gl.getId(), "SUB-MDI", "Subject"));
        subjectId = subject.getId();

        // TeacherProfile keeps this teacher on the legacy academic path (org
        // workspace resolved + EDITOR membership backfilled on first use);
        // the no-profile self-upgrade path gets its own test user below.
        jdbc.update("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) "
                + "VALUES (" + teacherUserId + "," + schoolId + ",'TC-MDI')");
        em.clear();
    }

    @AfterEach
    void cleanupCommittedTestData() {
        jdbc.update("DELETE FROM exam_question_options");
        jdbc.update("DELETE FROM exam_questions");
        jdbc.update("DELETE FROM exam_sections");
        jdbc.update("DELETE FROM exams WHERE code LIKE 'MD-EX-%'");
        jdbc.update("DELETE FROM question_options");
        jdbc.update("DELETE FROM question_versions");
        jdbc.update("DELETE FROM questions");
        jdbc.update("DELETE FROM question_banks WHERE code LIKE 'MD-%'");
        jdbc.update("DELETE FROM teacher_profiles WHERE teacher_code = 'TC-MDI'");
        jdbc.update("DELETE FROM subjects WHERE code = 'SUB-MDI'");
        jdbc.update("DELETE FROM grade_levels WHERE code = 'GL-MDI'");
        jdbc.update("DELETE FROM schools WHERE code = 'MD-IMP-SCH'");
        jdbc.update("DELETE FROM notifications WHERE user_id IN (SELECT id FROM users WHERE username IN ('md-import-teacher','md-no-profile','md-other'))");
        jdbc.update("DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE username IN ('md-import-teacher','md-no-profile','md-other'))");
        // Personal workspaces provisioned by the no-profile test own FK rows
        // referencing the test user — remove them first.
        jdbc.update("DELETE FROM question_banks WHERE workspace_id IN (SELECT id FROM workspaces WHERE owner_user_id IN (SELECT id FROM users WHERE username IN ('md-import-teacher','md-no-profile','md-other')))");
        jdbc.update("DELETE FROM workspace_memberships WHERE workspace_id IN (SELECT id FROM workspaces WHERE owner_user_id IN (SELECT id FROM users WHERE username IN ('md-import-teacher','md-no-profile','md-other')))");
        jdbc.update("DELETE FROM workspaces WHERE owner_user_id IN (SELECT id FROM users WHERE username IN ('md-import-teacher','md-no-profile','md-other'))");
        jdbc.update("DELETE FROM users WHERE username IN ('md-import-teacher','md-no-profile','md-other')");
    }

    private Long createOrgBank() {
        // Org-workspace bank via the real creation service (subject required,
        // membership provisioned by the workspace resolver).
        return bankService.createBank(teacherUserId,
                        new CreateQuestionBankRequest(null, "MD-ORG-BANK", "MD Org Bank", null, subjectId))
                .id();
    }

    private Long createPersonalBank(Long userId) {
        return bankService.createBank(userId,
                        new CreateQuestionBankRequest(null, "MD-PERSONAL-BANK", "MD Personal Bank", null, null))
                .id();
    }

    private ImportResponse importDoc(Long userId, Long bankId, String doc) {
        QuizMarkdownParseResult parsed = parser.parse(doc);
        return importService.importParsedMarkdown(userId, bankId, parsed);
    }

    private int questionCount(Long bankId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM questions WHERE question_bank_id = ?", Integer.class, bankId);
        return count == null ? 0 : count;
    }

    // ============================================================
    // Happy path: all four types persist with Excel-equivalent conventions
    // ============================================================

    @Test
    void importFourTypes_persistsWithBankConventions() {
        Long bankId = createOrgBank();

        ImportResponse response = importDoc(teacherUserId, bankId, FOUR_TYPES_DOC);

        assertThat(response.totalRows()).isEqualTo(4);
        assertThat(response.importedRows()).isEqualTo(4);
        assertThat(response.invalidRows()).isEqualTo(0);
        assertThat(response.errors()).isEmpty();
        assertThat(questionCount(bankId)).isEqualTo(4);

        List<String> codes = jdbc.queryForList(
                "SELECT code FROM questions WHERE question_bank_id = ? ORDER BY id", String.class, bankId);
        assertThat(codes).allMatch(c -> QU_CODE.matcher(c).matches());

        // Per-type option/answer shape (questions ordered by insertion = 1..4).
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM questions WHERE question_bank_id = ? ORDER BY id", Long.class, bankId);
        assertThat(optionFlags(ids.get(0))).containsExactly(true, false, false, false); // SINGLE *A
        assertThat(optionFlags(ids.get(1))).containsExactly(true, false, true, false); // MULTIPLE *A *C
        assertThat(optionFlags(ids.get(2))).containsExactly(true, false, true, false); // TF truth flags
        assertThat(optionCount(ids.get(3))).isZero(); // NUMERIC: no options

        // Version conventions: v1, MEDIUM, 1.00, ACTIVE question, verbatim answer key.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM question_versions WHERE question_id = ? AND version_number = 1 "
                        + "AND difficulty = 'MEDIUM' AND default_points = 1.00",
                Integer.class, ids.get(0))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM questions WHERE id = ?", String.class, ids.get(0))).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT answer_key->>'expectedAnswer' FROM question_versions WHERE question_id = ?",
                String.class, ids.get(3))).isEqualTo("2.50");
        assertThat(jdbc.queryForObject(
                "SELECT explanation FROM question_versions WHERE question_id = ?",
                String.class, ids.get(3))).isEqualTo("`10 / 4 = 2.5`.\n");

        // Notification + import access re-authorization leave no side effects beyond the bank.
        Integer notifications = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'IMPORT_COMPLETED'",
                Integer.class, teacherUserId);
        assertThat(notifications).isGreaterThanOrEqualTo(1);
    }

    // ============================================================
    // #10: bank-contract preflight — precise, atomic, never rewritten
    // ============================================================

    @Test
    void oneCorrectMultiple_rejectedAtomicallyWithActionableDiagnostic() {
        Long bankId = createOrgBank();
        String doc = """
                Câu 1 [SINGLE_CHOICE]: valid?
                *A. yes
                B. no
                C. maybe
                D. never

                Câu 2 [MULTIPLE_CHOICE]: only one star — bank contract wants two.
                *A. one
                B. two
                C. three
                D. four
                """;

        ImportResponse response = importDoc(teacherUserId, bankId, doc);

        assertThat(response.importedRows()).isZero();
        assertThat(response.invalidRows()).isEqualTo(1);
        assertThat(response.errors()).hasSize(1);
        RowError error = response.errors().get(0);
        assertThat(error.rowNumber()).isEqualTo(2);
        assertThat(error.code()).isEqualTo("MULTIPLE_CHOICE_MIN_TWO_CORRECT");
        assertThat(error.message()).contains("at least 2 correct");
        // Atomic: the valid question 1 is NOT persisted either.
        assertThat(questionCount(bankId)).isZero();
    }

    @Test
    void leadingDotNumeric_rejectedWithCanonicalSuggestion_neverRewritten() {
        Long bankId = createOrgBank();
        String doc = """
                Câu 1 [NUMERIC_FILL]: Giá trị khác?
                Đáp án: -.50
                """;

        ImportResponse response = importDoc(teacherUserId, bankId, doc);

        assertThat(response.importedRows()).isZero();
        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().get(0).code()).isEqualTo("NUMERIC_ANSWER_BANK_CONTRACT");
        assertThat(response.errors().get(0).message()).contains("-0.5");
        assertThat(questionCount(bankId)).isZero();
    }

    @Test
    void canonicalNumerics_persistVerbatim() {
        Long bankId = createOrgBank();
        String doc = """
                Câu 1 [NUMERIC_FILL]: a?
                Đáp án: -3.5

                Câu 2 [NUMERIC_FILL]: b?
                Đáp án: 02.5
                """;

        ImportResponse response = importDoc(teacherUserId, bankId, doc);

        assertThat(response.importedRows()).isEqualTo(2);
        List<String> answers = jdbc.queryForList(
                "SELECT answer_key->>'expectedAnswer' FROM question_versions qv "
                        + "JOIN questions q ON qv.question_id = q.id "
                        + "WHERE q.question_bank_id = ? ORDER BY q.id",
                String.class, bankId);
        assertThat(answers).containsExactly("-3.5", "02.5");
    }

    @Test
    void commaDecimal_rejectedByGrammar_notImported() {
        Long bankId = createOrgBank();
        String doc = """
                Câu 1 [NUMERIC_FILL]: comma decimal?
                Đáp án: 2,50
                """;

        ImportResponse response = importDoc(teacherUserId, bankId, doc);

        assertThat(response.importedRows()).isZero();
        assertThat(response.errors()).isNotEmpty();
        assertThat(response.errors().stream().map(RowError::code))
                .contains("NUMERIC_ANSWER_INVALID");
        assertThat(questionCount(bankId)).isZero();
    }

    @Test
    void parseError_returnsParserDiagnostics_nothingPersisted() {
        Long bankId = createOrgBank();
        String doc = """
                Câu 2 [SINGLE_CHOICE]: numbering must start at one.
                *A. a
                B. b
                C. c
                D. d
                """;

        ImportResponse response = importDoc(teacherUserId, bankId, doc);

        assertThat(response.importedRows()).isZero();
        assertThat(response.errors()).isNotEmpty();
        assertThat(response.errors().stream().map(RowError::code))
                .contains("QUESTION_NUMBER_MUST_START_AT_ONE");
        assertThat(questionCount(bankId)).isZero();
    }

    // ============================================================
    // Limits
    // ============================================================

    @Test
    void moreThanMaxQuestions_rejected() {
        Long bankId = createOrgBank();
        StringBuilder doc = new StringBuilder();
        for (int i = 1; i <= MarkdownQuestionImportService.MAX_MARKDOWN_QUESTIONS + 1; i++) {
            doc.append("Câu ").append(i).append(" [NUMERIC_FILL]: q").append(i).append("?\n");
            doc.append("Đáp án: 1.25\n\n");
        }

        QuizMarkdownParseResult parsed = parser.parse(doc.toString());
        assertThat(parsed.isValid()).isTrue();

        assertThatThrownBy(() -> importService.importParsedMarkdown(teacherUserId, bankId, parsed))
                .isInstanceOfSatisfying(QuestionException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .isEqualTo(QuestionErrorCode.QUESTION_IMPORT_MARKDOWN_TOO_MANY_QUESTIONS));
        assertThat(questionCount(bankId)).isZero();
    }

    // ============================================================
    // Authorization (workspace boundary, issue #18 audit)
    // ============================================================

    @Test
    void selfUpgradedTeacherWithoutProfile_importsIntoPersonalBank() {
        User noProfile = userRepo.saveAndFlush(
                new User("md-no-profile", "mdnp@test.com", "hash", "No Profile Teacher"));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", noProfile.getId());
        em.clear();

        Long personalBankId = createPersonalBank(noProfile.getId());

        ImportResponse response = importDoc(noProfile.getId(), personalBankId, FOUR_TYPES_DOC);

        assertThat(response.importedRows()).isEqualTo(4);
        assertThat(questionCount(personalBankId)).isEqualTo(4);
    }

    @Test
    void revokedOrgMembership_deniedEvenForBankCreator() {
        Long bankId = createOrgBank();
        jdbc.update("""
                UPDATE workspace_memberships SET status = 'REMOVED'
                WHERE user_id = ? AND workspace_id = (
                    SELECT workspace_id FROM question_banks WHERE id = ?
                )
                """, teacherUserId, bankId);
        em.clear();

        assertThatThrownBy(() -> importDoc(teacherUserId, bankId, FOUR_TYPES_DOC))
                .isInstanceOfSatisfying(QuestionException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .isEqualTo(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED));
        assertThat(questionCount(bankId)).isZero();
    }

    @Test
    void unrelatedTeacher_denied_crossBankIsolation() {
        Long bankId = createOrgBank();
        User other = userRepo.saveAndFlush(
                new User("md-other", "mdother@test.com", "hash", "Other Teacher"));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", other.getId());
        em.clear();

        assertThatThrownBy(() -> importDoc(other.getId(), bankId, FOUR_TYPES_DOC))
                .isInstanceOfSatisfying(QuestionException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .isEqualTo(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED));
        assertThat(questionCount(bankId)).isZero();
    }

    @Test
    void archivedBank_denied() {
        Long bankId = createOrgBank();
        jdbc.update("UPDATE question_banks SET status = 'ARCHIVED' WHERE id = ?", bankId);
        em.clear();

        assertThatThrownBy(() -> importDoc(teacherUserId, bankId, FOUR_TYPES_DOC))
                .isInstanceOfSatisfying(QuestionException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .isEqualTo(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED));
    }

    // ============================================================
    // #10 round trip: Markdown → import → edit → publish
    // ============================================================

    @Test
    void roundTrip_importEditPublish() {
        Long bankId = createOrgBank();
        ImportResponse imported = importDoc(teacherUserId, bankId, FOUR_TYPES_DOC);
        assertThat(imported.importedRows()).isEqualTo(4);

        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM questions WHERE question_bank_id = ? ORDER BY id", Long.class, bankId);

        // Edit the imported SINGLE_CHOICE question through the bank edit contract.
        questionService.editQuestion(teacherUserId, ids.get(0), new UpdateQuestionRequest(
                "Edited stem (2+2)?",
                QuestionDifficulty.HARD,
                "edited explanation",
                List.of(
                        new UpdateQuestionRequest.OptionPart("A", "4", true),
                        new UpdateQuestionRequest.OptionPart("B", "3", false),
                        new UpdateQuestionRequest.OptionPart("C", "5", false),
                        new UpdateQuestionRequest.OptionPart("D", "6", false)),
                null));
        assertThat(jdbc.queryForObject(
                "SELECT content FROM question_versions WHERE question_id = ?", String.class, ids.get(0)))
                .isEqualTo("Edited stem (2+2)?");

        // Publish an exam pinning the imported versions (publication contract).
        Long examId = examService.createExam(teacherUserId,
                        new com.quizopia.backend.exam.dto.CreateExamRequest(null, subjectId, null,
                                "MD-EX-1", "MD Round Trip", null))
                .id();
        List<CompositionQuestionRequest> questions = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            questions.add(new CompositionQuestionRequest(ids.get(i), i, null));
        }
        examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(0, 45, null,
                        List.of(new CompositionSectionRequest(0, "S", null, questions))));

        PublishedExamSummary published = examService.publishExam(
                teacherUserId, examId, new PublishExamRequest(null));

        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.questionCount()).isEqualTo(4);
        assertThat(published.totalPoints()).isEqualByComparingTo("4.00");
        // Numeric answer key survives the whole pipeline verbatim.
        assertThat(jdbc.queryForObject(
                "SELECT answer_key->>'expectedAnswer' FROM exam_questions WHERE source_question_id = ?",
                String.class, ids.get(3))).isEqualTo("2.50");
        // The edited content is what the exam pinned.
        assertThat(jdbc.queryForObject(
                "SELECT content FROM exam_questions WHERE source_question_id = ?",
                String.class, ids.get(0))).isEqualTo("Edited stem (2+2)?");
    }

    // ============================================================
    // Helpers
    // ============================================================

    private List<Boolean> optionFlags(Long questionId) {
        return jdbc.queryForList(
                "SELECT o.is_correct FROM question_options o "
                        + "JOIN question_versions v ON o.question_version_id = v.id "
                        + "WHERE v.question_id = ? AND v.version_number = 1 "
                        + "ORDER BY o.position",
                Boolean.class, questionId);
    }

    private int optionCount(Long questionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM question_options o "
                        + "JOIN question_versions v ON o.question_version_id = v.id "
                        + "WHERE v.question_id = ? AND v.version_number = 1",
                Integer.class, questionId);
        return count == null ? 0 : count;
    }
}
