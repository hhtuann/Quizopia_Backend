package com.quizopia.backend.exam;

import com.quizopia.backend.exam.domain.model.*;
import com.quizopia.backend.exam.repository.*;
import com.quizopia.backend.question.domain.model.QuestionType;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
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
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests proving the redesigned exam JPA entities (Task B, issue
 * #2 — one Exam = one content set) map correctly to the user-centric schema
 * via Hibernate {@code ddl-auto=validate}, and that JSONB, defaults, lifecycle
 * transitions, composite provenance FKs, creator-scoped code uniqueness,
 * the session state machine, optimistic versioning and toString safety all
 * work on real PostgreSQL 17 via Testcontainers.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ExamEntityMappingIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private ExamRepository examRepo;
    @Autowired
    private ExamSectionRepository sectionRepo;
    @Autowired
    private ExamQuestionRepository questionRepo;
    @Autowired
    private ExamSessionRepository sessionRepo;
    @Autowired
    private ExamSessionParticipantRepository participantRepo;

    private long userId;
    private long subjectId;
    private long questionIdA;
    private long questionVersionIdA;
    private long questionIdB;
    private long questionVersionIdB;

    @BeforeEach
    void setUp() {
        userId = insert(
                "INSERT INTO users (username, email, password_hash, display_name) VALUES ('eu','eu@t','h','EU')");
        subjectId = insert("INSERT INTO subjects (grade_level_id, code, name) VALUES ("
                + "(SELECT id FROM grade_levels WHERE code='G10'), 'SUB','Sub')");
        long bankId = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES ("
                + userId + "," + subjectId + ",'QB','Bank')");
        questionIdA = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bankId + ",'qA',"
                + userId + ")");
        questionVersionIdA = insert(
                "INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES ("
                        + questionIdA + ",1,'SINGLE_CHOICE','ca','MEDIUM',1,'{}'::jsonb," + userId + ")");
        questionIdB = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bankId + ",'qB',"
                + userId + ")");
        questionVersionIdB = insert(
                "INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES ("
                        + questionIdB + ",1,'SINGLE_CHOICE','cb','MEDIUM',1,'{}'::jsonb," + userId + ")");
    }

    // -- Schema validation (context load = ddl-auto=validate passes) --

    @Test
    void contextLoadsAndAllEntitiesValidate() {
        // If the Spring context loads, Hibernate ddl-auto=validate passed for
        // all entities (Exam/ExamSection/ExamQuestion/ExamQuestionOption/
        // ExamSession/... — exam version entities are gone).
        assertThat(entityManager).isNotNull();
    }

    // -- Exam field mapping round-trip (content columns moved from exam_versions) --

    @Test
    void examFieldMappingRoundTrip() {
        Exam source = examRepo.saveAndFlush(newExam("RT1"));
        Exam exam = newExam("RT2");
        exam.setDescription("desc");
        exam.updateDraftSettings(45, "Read carefully");
        exam.setPurposeId(null);
        exam.markAsDuplicateOf(source.getId());
        // Exam carries a non-null @Version default, so save() merges and the
        // id lands on the RETURNED managed instance — capture it.
        exam = examRepo.saveAndFlush(exam);
        entityManager.clear();
        Exam reloaded = examRepo.findById(exam.getId()).orElseThrow();
        assertThat(reloaded.getCreatedByUserUserId()).isEqualTo(userId);
        assertThat(reloaded.getSubjectId()).isEqualTo(subjectId);
        assertThat(reloaded.getCode()).isEqualTo("RT2");
        assertThat(reloaded.getTitle()).isEqualTo("T");
        assertThat(reloaded.getDescription()).isEqualTo("desc");
        assertThat(reloaded.getInstructions()).isEqualTo("Read carefully");
        assertThat(reloaded.getDurationMinutes()).isEqualTo(45);
        assertThat(reloaded.getStatus()).isEqualTo(ExamStatus.DRAFT);
        assertThat(reloaded.getPublishedAt()).isNull();
        assertThat(reloaded.getSourceExamId()).isEqualTo(source.getId());
        assertThat(reloaded.isDraft()).isTrue();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
        // Source exam carries no duplicate provenance.
        assertThat(examRepo.findById(source.getId()).orElseThrow().getSourceExamId()).isNull();
    }

    @Test
    void examDefaults() {
        Exam exam = examRepo.saveAndFlush(newExam("E1"));
        assertThat(exam.getStatus()).isEqualTo(ExamStatus.DRAFT);
        assertThat(exam.getTotalPoints()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(exam.getVersion()).isZero();
        assertThat(exam.getPublishedAt()).isNull();
        assertThat(exam.getSourceExamId()).isNull();
        entityManager.clear();
        Exam reloaded = examRepo.findById(exam.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ExamStatus.DRAFT);
        assertThat(reloaded.getTotalPoints()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // -- tfMatrixScoring JSONB --

    @Test
    void examTfMatrixScoringIsPerInstance() {
        Exam e1 = newExam("TF1");
        Exam e2 = newExam("TF2");
        // Two instances must NOT share the same mutable JsonNode
        assertThat(e1.getTfMatrixScoring()).isNotSameAs(e2.getTfMatrixScoring());
        // Exact TF ladder on both
        for (Exam e : new Exam[] { e1, e2 }) {
            assertThat(e.getTfMatrixScoring().path("0").asInt()).isZero();
            assertThat(e.getTfMatrixScoring().path("1").asInt()).isEqualTo(10);
            assertThat(e.getTfMatrixScoring().path("2").asInt()).isEqualTo(25);
            assertThat(e.getTfMatrixScoring().path("3").asInt()).isEqualTo(50);
            assertThat(e.getTfMatrixScoring().path("4").asInt()).isEqualTo(100);
        }
    }

    @Test
    void tfMatrixScoringRoundTrip() {
        Exam exam = examRepo.saveAndFlush(newExam("J1"));
        entityManager.clear();
        Exam reloaded = examRepo.findById(exam.getId()).orElseThrow();
        assertThat(reloaded.getTfMatrixScoring().path("0").isNumber()).isTrue();
        assertThat(reloaded.getTfMatrixScoring().path("0").asInt()).isZero();
        assertThat(reloaded.getTfMatrixScoring().path("4").asInt()).isEqualTo(100);
        // Verify DB-level: ladder values are numbers (not strings)
        String typeCheck = jdbc.queryForObject(
                "SELECT jsonb_typeof(tf_matrix_scoring->'1') FROM exams WHERE id=?",
                String.class, exam.getId());
        assertThat(typeCheck).isEqualTo("number");
    }

    // -- Lifecycle transitions --

    @Test
    void markPublishedPersistsPublishedState() {
        Exam exam = examRepo.saveAndFlush(newExam("P1"));
        Instant publishedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        exam.markPublished(publishedAt, new BigDecimal("10.00"));
        examRepo.saveAndFlush(exam);
        entityManager.clear();
        Exam reloaded = examRepo.findById(exam.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ExamStatus.PUBLISHED);
        assertThat(reloaded.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(reloaded.getTotalPoints()).isEqualByComparingTo("10.00");
        assertThat(reloaded.isDraft()).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM exams WHERE id=?", String.class, exam.getId()))
                .isEqualTo("PUBLISHED");
    }

    @Test
    void markPublishedInvalidTransitionsRejected() {
        Exam exam = examRepo.saveAndFlush(newExam("P2"));
        exam.markPublished(Instant.now(), BigDecimal.TEN);
        // Second publish from PUBLISHED -> invalid
        assertThatThrownBy(() -> exam.markPublished(Instant.now(), BigDecimal.TEN))
                .isInstanceOf(IllegalStateException.class);
        // Non-positive total points -> invalid
        Exam fresh = newExam("P3");
        assertThatThrownBy(() -> fresh.markPublished(Instant.now(), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fresh.markPublished(Instant.now(), new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markArchivedFromPublishedPersists() {
        Exam exam = examRepo.saveAndFlush(newExam("A1"));
        exam.markPublished(Instant.now(), BigDecimal.TEN);
        examRepo.saveAndFlush(exam);
        exam.markArchived();
        examRepo.saveAndFlush(exam);
        entityManager.clear();
        assertThat(examRepo.findById(exam.getId()).orElseThrow().getStatus()).isEqualTo(ExamStatus.ARCHIVED);
        // Archiving an already ARCHIVED exam -> invalid transition
        Exam reloaded = examRepo.findById(exam.getId()).orElseThrow();
        assertThatThrownBy(reloaded::markArchived).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markArchivedFromDraftRejected() {
        Exam exam = newExam("A2");
        assertThatThrownBy(exam::markArchived).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updateDraftSettingsBehavior() {
        Exam exam = examRepo.saveAndFlush(newExam("U1"));
        exam.updateDraftSettings(60, "Instr");
        assertThat(exam.getDurationMinutes()).isEqualTo(60);
        assertThat(exam.getInstructions()).isEqualTo("Instr");
        // Null duration keeps the existing value; instructions still replaceable
        exam.updateDraftSettings(null, "Instr2");
        assertThat(exam.getDurationMinutes()).isEqualTo(60);
        assertThat(exam.getInstructions()).isEqualTo("Instr2");
        // 0 = unlimited convention is accepted
        exam.updateDraftSettings(0, null);
        assertThat(exam.getDurationMinutes()).isZero();
        assertThat(exam.getInstructions()).isNull();
        // Negative duration rejected
        assertThatThrownBy(() -> exam.updateDraftSettings(-1, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markAsDuplicateOfRejectsNull() {
        Exam exam = newExam("D1");
        assertThatThrownBy(() -> exam.markAsDuplicateOf(null))
                .isInstanceOf(NullPointerException.class);
    }

    // -- Optimistic @Version increment --

    @Test
    void optimisticVersionIncrementsOnExamUpdate() {
        Exam exam = examRepo.saveAndFlush(newExam("V1"));
        assertThat(exam.getVersion()).isZero();
        exam.updateDraftSettings(30, "first");
        examRepo.saveAndFlush(exam);
        assertThat(exam.getVersion()).isEqualTo(1);
        entityManager.clear();
        assertThat(examRepo.findById(exam.getId()).orElseThrow().getVersion()).isEqualTo(1);
        Exam managed = examRepo.findById(exam.getId()).orElseThrow();
        managed.updateDraftSettings(31, "second");
        examRepo.saveAndFlush(managed);
        assertThat(managed.getVersion()).isEqualTo(2);
    }

    // -- User-ownership columns (user-centric model) --

    @Test
    void userOwnershipColumnsRoundTrip() {
        // exams.created_by_user_id <-> Exam.getCreatedByUserUserId()
        Exam exam = examRepo.saveAndFlush(newExam("OWN1"));
        assertThat(jdbc.queryForObject("SELECT created_by_user_id FROM exams WHERE id=?",
                Long.class, exam.getId())).isEqualTo(userId);
        // question_banks.created_by_user_id -> the bank owning the fixture questions
        assertThat(jdbc.queryForObject(
                "SELECT qb.created_by_user_id FROM question_banks qb "
                        + "JOIN questions q ON q.question_bank_id = qb.id WHERE q.id = ?",
                Long.class, questionIdA)).isEqualTo(userId);
        // exam_sessions.created_by <-> ExamSession.getCreatedBy()
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        var session = sessionRepo.saveAndFlush(new ExamSession(
                exam.getId(), "OWN", "T", starts, ends, 1, 30, userId));
        assertThat(jdbc.queryForObject("SELECT created_by FROM exam_sessions WHERE id=?",
                Long.class, session.getId())).isEqualTo(session.getCreatedBy()).isEqualTo(userId);
        // exam_session_participants.user_id <-> ExamSessionParticipant.getUserId()
        ExamSessionParticipant p = participantRepo.saveAndFlush(
                new ExamSessionParticipant(userId, session.getId(), userId));
        entityManager.clear();
        ExamSessionParticipant reloadedP = participantRepo.findById(p.getId()).orElseThrow();
        assertThat(jdbc.queryForObject("SELECT user_id FROM exam_session_participants WHERE id=?",
                Long.class, p.getId())).isEqualTo(reloadedP.getUserId()).isEqualTo(userId);
    }

    // -- ExamSection round-trip + position uniqueness --

    @Test
    void examSectionRoundTrip() {
        Exam exam = examRepo.saveAndFlush(newExam("S1"));
        ExamSection s = new ExamSection(exam.getId(), "Section A", 0);
        s.setCode("SA");
        s.setInstructions("sec instructions");
        sectionRepo.saveAndFlush(s);
        entityManager.clear();
        ExamSection reloaded = sectionRepo.findById(s.getId()).orElseThrow();
        assertThat(reloaded.getExamId()).isEqualTo(exam.getId());
        assertThat(reloaded.getCode()).isEqualTo("SA");
        assertThat(reloaded.getTitle()).isEqualTo("Section A");
        assertThat(reloaded.getInstructions()).isEqualTo("sec instructions");
        assertThat(reloaded.getPosition()).isZero();
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void sectionPositionUniquePerExam() {
        Exam exam = examRepo.saveAndFlush(newExam("S2"));
        sectionRepo.saveAndFlush(new ExamSection(exam.getId(), "A", 0));
        assertThatThrownBy(() -> sectionRepo.saveAndFlush(new ExamSection(exam.getId(), "B", 0)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_exam_sections_exam_position");
    }

    // -- ExamQuestion round-trip (JSONB answerKey/metadata, provenance FKs) --

    @Test
    void examQuestionAnswerKeyAndMetadataRoundTrip() {
        Exam exam = examRepo.saveAndFlush(newExam("Q1"));
        ExamSection s = sectionRepo.saveAndFlush(new ExamSection(exam.getId(), "S", 0));
        // Choice question: metadata only, answerKey null
        ExamQuestion choice = new ExamQuestion(exam.getId(), s.getId(), questionIdA, questionVersionIdA,
                "QC", QuestionType.SINGLE_CHOICE, "c", BigDecimal.ONE, 0);
        ObjectNode meta = JsonNodeFactory.instance.objectNode().put("tag", "x");
        choice.setMetadata(meta);
        questionRepo.saveAndFlush(choice);
        // NUMERIC source question+version
        long nq = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES ("
                + "(SELECT id FROM question_banks WHERE code='QB'), 'qN'," + userId + ")");
        long nv = insert(
                "INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, answer_key, metadata, created_by) VALUES ("
                        + nq
                        + ",1,'NUMERIC_FILL','n',1,'{\"expectedAnswer\":\"2.50\",\"requiredInputLength\":4,\"roundingInstruction\":\"r2\"}'::jsonb,'{}'::jsonb,"
                        + userId + ")");
        ObjectNode ak = JsonNodeFactory.instance.objectNode();
        ak.put("expectedAnswer", "2.50");
        ak.put("requiredInputLength", 4);
        ak.put("roundingInstruction", "r2");
        ExamQuestion numeric = new ExamQuestion(exam.getId(), s.getId(), nq, nv,
                "QN", QuestionType.NUMERIC_FILL, "n", BigDecimal.ONE, 1);
        numeric.setAnswerKey(ak);
        questionRepo.saveAndFlush(numeric);
        entityManager.clear();
        ExamQuestion reloadedChoice = questionRepo.findById(choice.getId()).orElseThrow();
        assertThat(reloadedChoice.getAnswerKey()).isNull();
        assertThat(reloadedChoice.getMetadata().path("tag").asString()).isEqualTo("x");
        assertThat(reloadedChoice.getSourceQuestionId()).isEqualTo(questionIdA);
        assertThat(reloadedChoice.getSourceQuestionVersionId()).isEqualTo(questionVersionIdA);
        assertThat(reloadedChoice.getExamId()).isEqualTo(exam.getId());
        assertThat(reloadedChoice.getExamSectionId()).isEqualTo(s.getId());
        assertThat(reloadedChoice.getDefaultPoints()).isEqualByComparingTo(BigDecimal.ONE);
        ExamQuestion reloadedNumeric = questionRepo.findById(numeric.getId()).orElseThrow();
        assertThat(reloadedNumeric.getAnswerKey().path("expectedAnswer").asString()).isEqualTo("2.50");
        assertThat(reloadedNumeric.getAnswerKey().path("requiredInputLength").isNumber()).isTrue();
        assertThat(reloadedNumeric.getAnswerKey().path("requiredInputLength").asInt()).isEqualTo(4);
        assertThat(reloadedNumeric.getAnswerKey().path("roundingInstruction").asString()).isEqualTo("r2");
    }

    @Test
    void examQuestionMetadataIsPerInstanceEmptyObject() {
        ExamQuestion q1 = new ExamQuestion(1L, 1L, questionIdA, questionVersionIdA,
                "QC1", QuestionType.SINGLE_CHOICE, "c", BigDecimal.ONE, 0);
        ExamQuestion q2 = new ExamQuestion(1L, 1L, questionIdB, questionVersionIdB,
                "QC2", QuestionType.SINGLE_CHOICE, "c2", BigDecimal.ONE, 1);
        assertThat(q1.getMetadata()).isNotSameAs(q2.getMetadata());
        assertThat(q1.getMetadata().isObject()).isTrue();
    }

    @Test
    void provenanceMismatchRejectedByCompositeFk() {
        Exam exam = examRepo.saveAndFlush(newExam("Q2"));
        ExamSection s = sectionRepo.saveAndFlush(new ExamSection(exam.getId(), "S", 0));
        ExamQuestion mismatch = new ExamQuestion(exam.getId(), s.getId(), questionIdA, questionVersionIdB,
                "QC", QuestionType.SINGLE_CHOICE, "c", BigDecimal.ONE, 0);
        assertThatThrownBy(() -> questionRepo.saveAndFlush(mismatch))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_exam_questions_source_pair");
    }

    @Test
    void crossExamSectionRejectedByCompositeFk() {
        Exam exam1 = examRepo.saveAndFlush(newExam("Q3"));
        Exam exam2 = examRepo.saveAndFlush(newExam("Q4"));
        ExamSection sOfExam1 = sectionRepo.saveAndFlush(new ExamSection(exam1.getId(), "S", 0));
        // Assign exam2's question to exam1's section -> composite FK violation
        ExamQuestion bad = new ExamQuestion(exam2.getId(), sOfExam1.getId(), questionIdA, questionVersionIdA,
                "QC", QuestionType.SINGLE_CHOICE, "c", BigDecimal.ONE, 0);
        assertThatThrownBy(() -> questionRepo.saveAndFlush(bad))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_exam_questions_section_exam");
    }

    @Test
    void sameSourceQuestionTwiceInOneExamRejected() {
        Exam exam = examRepo.saveAndFlush(newExam("Q5"));
        ExamSection s = sectionRepo.saveAndFlush(new ExamSection(exam.getId(), "S", 0));
        questionRepo.saveAndFlush(new ExamQuestion(exam.getId(), s.getId(), questionIdA, questionVersionIdA,
                "QC1", QuestionType.SINGLE_CHOICE, "c", BigDecimal.ONE, 0));
        assertThatThrownBy(() -> questionRepo.saveAndFlush(
                new ExamQuestion(exam.getId(), s.getId(), questionIdA, questionVersionIdA,
                        "QC2", QuestionType.SINGLE_CHOICE, "c", BigDecimal.ONE, 1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_exam_questions_exam_source");
    }

    @Test
    void examQuestionOptionIsCorrectDefaultsFalse() {
        assertThat(new ExamQuestionOption(1L, "A", "x", null, 0).getIsCorrect()).isFalse();
    }

    // -- ExamSession round-trip + NOT NULL durationMinutes --

    @Test
    void examSessionRoundTrip() {
        Exam exam = examRepo.saveAndFlush(newExam("SS1"));
        Instant starts = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant ends = starts.plusSeconds(3600);
        var session = new ExamSession(exam.getId(),
                "S1", "T", starts, ends, 2, 60, userId);
        // @Version default makes save() merge — capture the managed instance.
        session = sessionRepo.saveAndFlush(session);
        entityManager.clear();
        ExamSession reloaded = sessionRepo.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getExamId()).isEqualTo(exam.getId());
        assertThat(reloaded.getCode()).isEqualTo("S1");
        assertThat(reloaded.getTitle()).isEqualTo("T");
        assertThat(reloaded.getStatus()).isEqualTo(ExamSessionStatus.DRAFT);
        assertThat(reloaded.getVisibility()).isEqualTo(SessionVisibility.CLASS_RESTRICTED);
        assertThat(reloaded.getStartsAt()).isEqualTo(starts);
        assertThat(reloaded.getEndsAt()).isEqualTo(ends);
        assertThat(reloaded.getMaxAttempts()).isEqualTo(2);
        assertThat(reloaded.getDurationMinutes()).isEqualTo(60);
        assertThat(reloaded.getCreatedBy()).isEqualTo(userId);
        assertThat(reloaded.getOpenedAt()).isNull();
        assertThat(reloaded.getClosedAt()).isNull();
        assertThat(reloaded.getVersion()).isZero();
    }

    @Test
    void examSessionDurationMinutesNotNullAndNonNegative() {
        Exam exam = examRepo.saveAndFlush(newExam("SS2"));
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        // null duration rejected (session-owned, no exam fallback)
        assertThatThrownBy(() -> new ExamSession(exam.getId(),
                "X1", "T", starts, ends, 1, null, userId))
                .isInstanceOf(NullPointerException.class);
        // negative duration rejected
        assertThatThrownBy(() -> new ExamSession(exam.getId(),
                "X2", "T", starts, ends, 1, -1, userId))
                .isInstanceOf(IllegalArgumentException.class);
        // 0 = unlimited convention is accepted and persists
        var unlimited = sessionRepo.saveAndFlush(new ExamSession(exam.getId(),
                "X3", "T", starts, ends, 1, 0, userId));
        assertThat(unlimited.getDurationMinutes()).isZero();
    }

    // -- ExamSession state machine (schedule/open/close/expire/cancel) --

    @Test
    void sessionStateMachineTransitionsPersist() {
        Instant starts = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(60);
        Instant ends = starts.plusSeconds(7200);
        Instant t1 = starts.plusSeconds(60);
        Instant t2 = starts.plusSeconds(120);

        // DRAFT -> SCHEDULED -> OPEN -> CLOSED
        Exam exam1 = examRepo.saveAndFlush(newExam("SM1"));
        var s1 = sessionRepo.saveAndFlush(new ExamSession(exam1.getId(),
                "SM1", "T", starts, ends, 1, 45, userId));
        s1.schedule();
        sessionRepo.saveAndFlush(s1);
        assertThat(s1.getStatus()).isEqualTo(ExamSessionStatus.SCHEDULED);
        s1.open(t1);
        sessionRepo.saveAndFlush(s1);
        assertThat(s1.getStatus()).isEqualTo(ExamSessionStatus.OPEN);
        assertThat(s1.getOpenedAt()).isEqualTo(t1);
        s1.close(t2);
        sessionRepo.saveAndFlush(s1);
        entityManager.clear();
        ExamSession closed = sessionRepo.findById(s1.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(ExamSessionStatus.CLOSED);
        assertThat(closed.getOpenedAt()).isEqualTo(t1);
        assertThat(closed.getClosedAt()).isEqualTo(t2);

        // SCHEDULED -> CLOSED via expire (never opened: both timestamps = now)
        Exam exam2 = examRepo.saveAndFlush(newExam("SM2"));
        var s2 = sessionRepo.saveAndFlush(new ExamSession(exam2.getId(),
                "SM2", "T", starts, ends, 1, 45, userId));
        s2.schedule();
        s2.expire(t1);
        sessionRepo.saveAndFlush(s2);
        entityManager.clear();
        ExamSession expired = sessionRepo.findById(s2.getId()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(ExamSessionStatus.CLOSED);
        assertThat(expired.getOpenedAt()).isEqualTo(t1);
        assertThat(expired.getClosedAt()).isEqualTo(t1);

        // SCHEDULED -> CANCELLED (no timestamps)
        Exam exam3 = examRepo.saveAndFlush(newExam("SM3"));
        var s3 = sessionRepo.saveAndFlush(new ExamSession(exam3.getId(),
                "SM3", "T", starts, ends, 1, 45, userId));
        s3.schedule();
        s3.cancel();
        sessionRepo.saveAndFlush(s3);
        entityManager.clear();
        ExamSession cancelled = sessionRepo.findById(s3.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(ExamSessionStatus.CANCELLED);
        assertThat(cancelled.getOpenedAt()).isNull();
        assertThat(cancelled.getClosedAt()).isNull();
    }

    @Test
    void sessionUpdateConfigChangesWindowAndRejectsInvalidWindow() {
        Exam exam = examRepo.saveAndFlush(newExam("UC1"));
        Instant starts = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant ends = starts.plusSeconds(3600);
        var session = sessionRepo.saveAndFlush(new ExamSession(exam.getId(),
                "UC1", "T", starts, ends, 1, 30, userId));
        Instant starts2 = starts.plusSeconds(600);
        Instant ends2 = starts2.plusSeconds(1800);
        session.updateConfig("New Title", starts2, ends2, 3);
        sessionRepo.saveAndFlush(session);
        entityManager.clear();
        ExamSession reloaded = sessionRepo.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("New Title");
        assertThat(reloaded.getStartsAt()).isEqualTo(starts2);
        assertThat(reloaded.getEndsAt()).isEqualTo(ends2);
        assertThat(reloaded.getMaxAttempts()).isEqualTo(3);
        assertThat(reloaded.getDurationMinutes()).isEqualTo(30);
        // endsAt must remain after startsAt
        assertThatThrownBy(() -> reloaded.updateConfig(null, ends2, ends2, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -- Code uniqueness --

    @Test
    void examCodeSameCreatorDifferentCaseRejected() {
        // Creator-scoped unique index (created_by_user_id, LOWER(code)).
        examRepo.saveAndFlush(new Exam(userId, null, "CODE-1", "T"));
        assertThatThrownBy(() -> examRepo.saveAndFlush(new Exam(userId, null, "code-1", "T")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_exams_creator_code_ci");
    }

    @Test
    void examCodeDifferentCreatorsAccepted() {
        long u2 = insert(
                "INSERT INTO users (username, email, password_hash, display_name) VALUES ('u2a','u2a@t','h','U2A')");
        examRepo.saveAndFlush(new Exam(userId, null, "SHARED", "T"));
        examRepo.saveAndFlush(new Exam(u2, null, "SHARED", "T"));
    }

    @Test
    void sessionCodeSameCreatorDifferentCaseRejected() {
        Exam exam = examRepo.saveAndFlush(newExam("SC1"));
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        sessionRepo.saveAndFlush(new ExamSession(exam.getId(),
                "SESS-1", "T", starts, ends, 1, 60, userId));
        assertThatThrownBy(() -> sessionRepo.saveAndFlush(new ExamSession(exam.getId(),
                "sess-1", "T", starts, ends, 1, 60, userId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sessionCodeDifferentCreatorsSameCodeAccepted() {
        Exam exam = examRepo.saveAndFlush(newExam("SC2"));
        long u2 = insert(
                "INSERT INTO users (username, email, password_hash, display_name) VALUES ('u2b','u2b@t','h','U2B')");
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        sessionRepo.saveAndFlush(new ExamSession(exam.getId(),
                "SHARED", "T", starts, ends, 1, 60, userId));
        sessionRepo.saveAndFlush(new ExamSession(exam.getId(),
                "SHARED", "T", starts, ends, 1, 60, u2));
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

    /** Creator-owned Exam in the user-centric model (all explicit). */
    private Exam newExam(String code) {
        return new Exam(userId, subjectId, code, "T");
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
