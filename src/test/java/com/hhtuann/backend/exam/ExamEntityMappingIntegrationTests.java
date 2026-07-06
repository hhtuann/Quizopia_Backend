package com.hhtuann.backend.exam;

import com.hhtuann.backend.exam.domain.model.*;
import com.hhtuann.backend.exam.repository.*;
import com.hhtuann.backend.question.domain.model.QuestionType;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests proving the 8 exam JPA entities map correctly to the V8
 * schema via Hibernate {@code ddl-auto=validate}, and that JSONB, defaults,
 * composite provenance (H1), composite section FK, owner-scoped code, and
 * toString safety all work on real PostgreSQL 17 via Testcontainers.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ExamEntityMappingIntegrationTests {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private ExamPurposeRepository purposeRepo;
    @Autowired private ExamRepository examRepo;
    @Autowired private ExamVersionRepository versionRepo;
    @Autowired private ExamSectionRepository sectionRepo;
    @Autowired private ExamQuestionRepository questionRepo;
    @Autowired private ExamSessionRepository sessionRepo;

    private long userId;
    private long schoolId;
    private long subjectId;
    private long teacherProfileId;
    private long studentProfileId;
    private long questionIdA;
    private long questionVersionIdA;
    private long questionIdB;
    private long questionVersionIdB;

    @BeforeEach
    void setUp() {
        userId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('eu','eu@t','h','EU')");
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('ES','Exam School')");
        long glId = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + glId + ",'SUB','S')");
        teacherProfileId = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + userId + "," + schoolId + ",'ETC')");
        studentProfileId = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + userId + "," + schoolId + ",'ESC')");
        long bankId = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + schoolId + "," + subjectId + "," + teacherProfileId + ",'QB','Bank')");
        questionIdA = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bankId + ",'qA'," + userId + ")");
        questionVersionIdA = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + questionIdA + ",1,'SINGLE_CHOICE','ca','MEDIUM',1,'{}'::jsonb," + userId + ")");
        questionIdB = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bankId + ",'qB'," + userId + ")");
        questionVersionIdB = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + questionIdB + ",1,'SINGLE_CHOICE','cb','MEDIUM',1,'{}'::jsonb," + userId + ")");
    }

    // -- Schema validation (context load = ddl-auto=validate passes) --

    @Test
    void contextLoadsAndAllEntitiesValidate() {
        // If the Spring context loads, Hibernate ddl-auto=validate passed for all 8 entities.
        assertThat(entityManager).isNotNull();
    }

    // -- Defaults --

    @Test
    void examDefaults() {
        Exam exam = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "E1", "T"));
        assertThat(exam.getCurrentVersionNumber()).isEqualTo(1);
        assertThat(exam.getStatus()).isEqualTo(ExamStatus.DRAFT);
        assertThat(exam.getVersion()).isZero();
    }

    @Test
    void examVersionDefaults() {
        Exam exam = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "E2", "T"));
        ExamVersion v = versionRepo.saveAndFlush(new ExamVersion(schoolId, exam.getId(), 1, userId));
        assertThat(v.getDurationMinutes()).isEqualTo(60);
        assertThat(v.getTotalPoints()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(v.getStatus()).isEqualTo(ExamVersionStatus.DRAFT);
        assertThat(v.getPublishedAt()).isNull();
        // Exact TF ladder
        assertThat(v.getTfMatrixScoring().path("0").asInt()).isZero();
        assertThat(v.getTfMatrixScoring().path("1").asInt()).isEqualTo(10);
        assertThat(v.getTfMatrixScoring().path("2").asInt()).isEqualTo(25);
        assertThat(v.getTfMatrixScoring().path("3").asInt()).isEqualTo(50);
        assertThat(v.getTfMatrixScoring().path("4").asInt()).isEqualTo(100);
    }

    @Test
    void examVersionTfMatrixScoringIsPerInstance() {
        ExamVersion v1 = new ExamVersion(schoolId, 999L, 1, userId);
        ExamVersion v2 = new ExamVersion(schoolId, 999L, 1, userId);
        // Two instances must NOT share the same mutable JsonNode
        assertThat(v1.getTfMatrixScoring()).isNotSameAs(v2.getTfMatrixScoring());
    }

    @Test
    void examQuestionMetadataIsPerInstanceEmptyObject() {
        Exam exam = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "E3", "T"));
        ExamVersion v = versionRepo.saveAndFlush(new ExamVersion(schoolId, exam.getId(), 1, userId));
        ExamSection s = sectionRepo.saveAndFlush(new ExamSection(v.getId(), "S", 0));
        ExamQuestion q1 = new ExamQuestion(v.getId(), s.getId(), questionIdA, questionVersionIdA,
                "QC1", QuestionType.SINGLE_CHOICE, "c", BigDecimal.ONE, 0);
        ExamQuestion q2 = new ExamQuestion(v.getId(), s.getId(), questionIdB, questionVersionIdB,
                "QC2", QuestionType.SINGLE_CHOICE, "c2", BigDecimal.ONE, 1);
        assertThat(q1.getMetadata()).isNotSameAs(q2.getMetadata());
        assertThat(q1.getMetadata().isObject()).isTrue();
    }

    @Test
    void examQuestionOptionIsCorrectDefaultsFalse() {
        assertThat(new ExamQuestionOption(1L, "A", "x", null, 0).getIsCorrect()).isFalse();
    }

    @Test
    void examSessionDefaults() {
        Exam exam = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "E4", "T"));
        ExamVersion v = versionRepo.saveAndFlush(new ExamVersion(schoolId, exam.getId(), 1, userId));
        v.markPublished(Instant.now(), BigDecimal.TEN);
        versionRepo.saveAndFlush(v);
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        var session = new com.hhtuann.backend.exam.domain.model.ExamSession(
                schoolId, v.getId(), teacherProfileId, "S1", "T", starts, ends, 1, userId);
        assertThat(session.getStatus()).isEqualTo(ExamSessionStatus.DRAFT);
        assertThat(session.getMaxAttempts()).isEqualTo(1);
    }

    @Test
    void examSessionParticipantDefaults() {
        var p = new ExamSessionParticipant(schoolId, 1L, studentProfileId, userId);
        assertThat(p.getStatus()).isEqualTo(ExamSessionParticipantStatus.ELIGIBLE);
        assertThat(p.getBlockedAt()).isNull();
    }

    // -- JSONB round-trip --

    @Test
    void tfMatrixScoringRoundTrip() {
        Exam exam = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "J1", "T"));
        ExamVersion v = versionRepo.saveAndFlush(new ExamVersion(schoolId, exam.getId(), 1, userId));
        entityManager.clear();
        ExamVersion reloaded = versionRepo.findById(v.getId()).orElseThrow();
        assertThat(reloaded.getTfMatrixScoring().path("0").isNumber()).isTrue();
        assertThat(reloaded.getTfMatrixScoring().path("0").asInt()).isZero();
        assertThat(reloaded.getTfMatrixScoring().path("4").asInt()).isEqualTo(100);
        // Verify DB-level: requiredInputLength type is number (not string)
        String typeCheck = jdbc.queryForObject(
                "SELECT jsonb_typeof(tf_matrix_scoring->'1') FROM exam_versions WHERE id=?",
                String.class, v.getId());
        assertThat(typeCheck).isEqualTo("number");
    }

    @Test
    void metadataRoundTrip() {
        Exam exam = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "J2", "T"));
        ExamVersion v = versionRepo.saveAndFlush(new ExamVersion(schoolId, exam.getId(), 1, userId));
        ExamSection s = sectionRepo.saveAndFlush(new ExamSection(v.getId(), "S", 0));
        ObjectNode meta = JsonNodeFactory.instance.objectNode().put("tag", "x");
        ExamQuestion q = new ExamQuestion(v.getId(), s.getId(), questionIdA, questionVersionIdA,
                "QC", QuestionType.SINGLE_CHOICE, "c", BigDecimal.ONE, 0);
        q.setMetadata(meta);
        questionRepo.saveAndFlush(q);
        entityManager.clear();
        ExamQuestion reloaded = questionRepo.findById(q.getId()).orElseThrow();
        assertThat(reloaded.getMetadata().path("tag").asString()).isEqualTo("x");
    }

    @Test
    void numericAnswerKeyRoundTrip() {
        Exam exam = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "J3", "T"));
        ExamVersion v = versionRepo.saveAndFlush(new ExamVersion(schoolId, exam.getId(), 1, userId));
        ExamSection s = sectionRepo.saveAndFlush(new ExamSection(v.getId(), "S", 0));
        // NUMERIC source question+version
        long nq = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES ("
                + "(SELECT id FROM question_banks WHERE code='QB'), 'qN'," + userId + ")");
        long nv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, answer_key, metadata, created_by) VALUES ("
                + nq + ",1,'NUMERIC_FILL','n',1,'{\"expectedAnswer\":\"2.50\",\"requiredInputLength\":4,\"roundingInstruction\":\"r2\"}'::jsonb,'{}'::jsonb," + userId + ")");
        ObjectNode ak = JsonNodeFactory.instance.objectNode();
        ak.put("expectedAnswer", "2.50");
        ak.put("requiredInputLength", 4);
        ak.put("roundingInstruction", "r2");
        ExamQuestion q = new ExamQuestion(v.getId(), s.getId(), nq, nv,
                "QN", QuestionType.NUMERIC_FILL, "n", BigDecimal.ONE, 0);
        q.setAnswerKey(ak);
        questionRepo.saveAndFlush(q);
        entityManager.clear();
        ExamQuestion reloaded = questionRepo.findById(q.getId()).orElseThrow();
        assertThat(reloaded.getAnswerKey().path("expectedAnswer").asString()).isEqualTo("2.50");
        assertThat(reloaded.getAnswerKey().path("requiredInputLength").isNumber()).isTrue();
        assertThat(reloaded.getAnswerKey().path("requiredInputLength").asInt()).isEqualTo(4);
        assertThat(reloaded.getAnswerKey().path("roundingInstruction").asString()).isEqualTo("r2");
    }

    @Test
    void choiceAnswerKeyNullRoundTrip() {
        Exam exam = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "J4", "T"));
        ExamVersion v = versionRepo.saveAndFlush(new ExamVersion(schoolId, exam.getId(), 1, userId));
        ExamSection s = sectionRepo.saveAndFlush(new ExamSection(v.getId(), "S", 0));
        ExamQuestion q = new ExamQuestion(v.getId(), s.getId(), questionIdA, questionVersionIdA,
                "QC", QuestionType.SINGLE_CHOICE, "c", BigDecimal.ONE, 0);
        questionRepo.saveAndFlush(q);
        entityManager.clear();
        ExamQuestion reloaded = questionRepo.findById(q.getId()).orElseThrow();
        assertThat(reloaded.getAnswerKey()).isNull();
    }

    // -- Provenance mapping (H1) --

    @Test
    void provenanceValidPairAcceptedAndAssociationResolves() {
        Exam exam = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "P1", "T"));
        ExamVersion v = versionRepo.saveAndFlush(new ExamVersion(schoolId, exam.getId(), 1, userId));
        ExamSection s = sectionRepo.saveAndFlush(new ExamSection(v.getId(), "S", 0));
        ExamQuestion q = new ExamQuestion(v.getId(), s.getId(), questionIdA, questionVersionIdA,
                "QC", QuestionType.SINGLE_CHOICE, "c", BigDecimal.ONE, 0);
        questionRepo.saveAndFlush(q);
        entityManager.clear();
        ExamQuestion reloaded = questionRepo.findById(q.getId()).orElseThrow();
        // Composite read-only association should resolve to vA which belongs to qA
        assertThat(reloaded.getSourceQuestionVersion()).isNotNull();
        assertThat(reloaded.getSourceQuestionVersion().getQuestionId()).isEqualTo(questionIdA);
    }

    @Test
    void provenanceMismatchRejectedByCompositeFk() {
        Exam exam = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "P2", "T"));
        ExamVersion v = versionRepo.saveAndFlush(new ExamVersion(schoolId, exam.getId(), 1, userId));
        ExamSection s = sectionRepo.saveAndFlush(new ExamSection(v.getId(), "S", 0));
        ExamQuestion mismatch = new ExamQuestion(v.getId(), s.getId(), questionIdA, questionVersionIdB,
                "QC", QuestionType.SINGLE_CHOICE, "c", BigDecimal.ONE, 0);
        assertThatThrownBy(() -> questionRepo.saveAndFlush(mismatch))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_exam_questions_source_pair");
    }

    // -- Composite section FK --

    @Test
    void compositeSectionRejectsCrossVersionSection() {
        Exam exam = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "CS1", "T"));
        ExamVersion v1 = versionRepo.saveAndFlush(new ExamVersion(schoolId, exam.getId(), 1, userId));
        v1.markPublished(Instant.now(), BigDecimal.TEN);
        versionRepo.saveAndFlush(v1);
        ExamVersion v2 = versionRepo.saveAndFlush(new ExamVersion(schoolId, exam.getId(), 2, userId));
        ExamSection sOfV1 = sectionRepo.saveAndFlush(new ExamSection(v1.getId(), "S", 0));
        // Assign v2 question to v1's section → cross-version composite FK violation
        ExamQuestion bad = new ExamQuestion(v2.getId(), sOfV1.getId(), questionIdA, questionVersionIdA,
                "QC", QuestionType.SINGLE_CHOICE, "c", BigDecimal.ONE, 0);
        assertThatThrownBy(() -> questionRepo.saveAndFlush(bad))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_exam_questions_section_version");
    }

    // -- Owner-scoped code --

    @Test
    void examCodeSameOwnerDifferentCaseRejected() {
        examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "CODE-1", "T"));
        assertThatThrownBy(() -> examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "code-1", "T")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void examCodeTwoOwnersSameSchoolSameCodeAccepted() {
        long u2 = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('u2a','u2a@t','h','U2A')");
        long tp2 = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + u2 + "," + schoolId + ",'ETC2')");
        examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "SHARED", "T"));
        examRepo.saveAndFlush(new Exam(schoolId, subjectId, tp2, "SHARED", "T"));
    }

    @Test
    void sessionCodeSameOwnerDifferentCaseRejected() {
        Exam exam = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "SC1", "T"));
        ExamVersion v = versionRepo.saveAndFlush(new ExamVersion(schoolId, exam.getId(), 1, userId));
        v.markPublished(Instant.now(), BigDecimal.TEN);
        versionRepo.saveAndFlush(v);
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        sessionRepo.saveAndFlush(new com.hhtuann.backend.exam.domain.model.ExamSession(
                schoolId, v.getId(), teacherProfileId, "SESS-1", "T", starts, ends, 1, userId));
        assertThatThrownBy(() -> sessionRepo.saveAndFlush(new com.hhtuann.backend.exam.domain.model.ExamSession(
                schoolId, v.getId(), teacherProfileId, "sess-1", "T", starts, ends, 1, userId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sessionCodeTwoOwnersSameSchoolSameCodeAccepted() {
        Exam exam = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "SC2", "T"));
        ExamVersion v = versionRepo.saveAndFlush(new ExamVersion(schoolId, exam.getId(), 1, userId));
        v.markPublished(Instant.now(), BigDecimal.TEN);
        versionRepo.saveAndFlush(v);
        long u2 = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('u2b','u2b@t','h','U2B')");
        long tp2 = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + u2 + "," + schoolId + ",'ETC3')");
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        sessionRepo.saveAndFlush(new com.hhtuann.backend.exam.domain.model.ExamSession(
                schoolId, v.getId(), teacherProfileId, "SHARED", "T", starts, ends, 1, userId));
        sessionRepo.saveAndFlush(new com.hhtuann.backend.exam.domain.model.ExamSession(
                schoolId, v.getId(), tp2, "SHARED", "T", starts, ends, 1, userId));
    }

    // -- toString safety --

    @Test
    void examQuestionToStringExcludesAnswerKey() {
        ExamQuestion q = new ExamQuestion(1L, 1L, 1L, 1L, "QC", QuestionType.NUMERIC_FILL, "c", BigDecimal.ONE, 0);
        ObjectNode ak = JsonNodeFactory.instance.objectNode();
        ak.put("expectedAnswer", "2.50");
        q.setAnswerKey(ak);
        String str = q.toString();
        assertThat(str).doesNotContain("answerKey");
        assertThat(str).doesNotContain("expectedAnswer");
        assertThat(str).doesNotContain("2.50");
    }

    @Test
    void examQuestionOptionToStringExcludesIsCorrect() {
        ExamQuestionOption opt = new ExamQuestionOption(1L, "A", "content", true, 0);
        String str = opt.toString();
        assertThat(str).doesNotContain("isCorrect");
        assertThat(str).doesNotContain("correct");
    }

    // -- Helper --

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
