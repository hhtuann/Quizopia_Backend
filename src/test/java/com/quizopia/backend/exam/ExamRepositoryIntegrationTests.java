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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the exam repository interfaces: derived queries,
 * pessimistic lock, and optimistic lock (@Version) on real PostgreSQL 17.
 * One-exam-one-content model (issue #2): no version repositories.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
@SuppressWarnings({"null"})
class ExamRepositoryIntegrationTests {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private ExamPurposeRepository purposeRepo;
    @Autowired private ExamRepository examRepo;
    @Autowired private ExamSectionRepository sectionRepo;
    @Autowired private ExamQuestionRepository questionRepo;
    @Autowired private ExamQuestionOptionRepository optionRepo;
    @Autowired private ExamSessionRepository sessionRepo;
    @Autowired private ExamSessionParticipantRepository participantRepo;

    private long userId;
    private long subjectId;
    private long questionIdA;
    private long questionVersionIdA;

    @BeforeEach
    void setUp() {
        userId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('ru','ru@t','h','RU')");
        subjectId = insert("INSERT INTO subjects (grade_level_id, code, name) VALUES ((SELECT id FROM grade_levels WHERE code='G10'), 'SUB','Sub')");
        long bankId = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES (" + userId + "," + subjectId + ",'RB','Bank')");
        questionIdA = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bankId + ",'rqA'," + userId + ")");
        questionVersionIdA = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + questionIdA + ",1,'SINGLE_CHOICE','ca','MEDIUM',1,'{}'::jsonb," + userId + ")");
    }

    // -- Purpose ordering (global catalog, V24) --

    @Test
    void purposeOrderingByPosition() {
        purposeRepo.saveAndFlush(makePurpose("ZPURP", "Z Title", 2));
        purposeRepo.saveAndFlush(makePurpose("APURP", "A Title", 0));
        purposeRepo.saveAndFlush(makePurpose("MPURP", "M Title", 1));
        List<String> ownCodes = List.of("APURP", "MPURP", "ZPURP");
        // Global catalog: filter to rows created by this test before asserting position order.
        List<ExamPurpose> ordered = purposeRepo.findAllByOrderByPositionAsc().stream()
                .filter(p -> ownCodes.contains(p.getCode()))
                .toList();
        assertThat(ordered).extracting(ExamPurpose::getCode).containsExactly("APURP", "MPURP", "ZPURP");
    }

    @Test
    void purposeCatalogIsGlobalAndPreSeeded() {
        // V24 pre-seeds the global purpose catalog; codes are unique per database.
        assertThat(purposeRepo.findAllByOrderByPositionAsc())
                .extracting(ExamPurpose::getCode)
                .contains("MIDTERM", "FINAL", "QUIZ", "PRACTICE");
    }

    // -- Exam creator lookup --

    @Test
    void examCreatorLookup() {
        Exam e = examRepo.saveAndFlush(new Exam(userId, subjectId, "EX1", "T"));
        assertThat(examRepo.findAllByCreatedByUserUserId(e.getCreatedByUserUserId()))
                .extracting(Exam::getId)
                .contains(e.getId());
        assertThat(examRepo.findAllByCreatedByUserUserId(99999L)).isEmpty();
    }

    @Test
    void examExistsByCreatorAndCodeIgnoreCase() {
        examRepo.saveAndFlush(new Exam(userId, subjectId, "UNIQUE", "T"));
        assertThat(examRepo.existsByCreatedByUserUserIdAndCodeIgnoreCase(userId, "unique")).isTrue();
        assertThat(examRepo.existsByCreatedByUserUserIdAndCodeIgnoreCase(userId, "OTHER")).isFalse();
        assertThat(examRepo.existsByCreatedByUserUserIdAndCodeIgnoreCase(99999L, "UNIQUE")).isFalse();
    }

    @Test
    void examFindAllByCreator() {
        examRepo.saveAndFlush(new Exam(userId, subjectId, "EX2", "T"));
        examRepo.saveAndFlush(new Exam(userId, subjectId, "EX3", "T"));
        assertThat(examRepo.findAllByCreatedByUserUserId(userId)).hasSize(2);
    }

    @Test
    void examCreatedByUserPagingAndFilter() {
        examRepo.saveAndFlush(new Exam(userId, subjectId, "CR1", "Alpha"));
        examRepo.saveAndFlush(new Exam(userId, subjectId, "CR2", "Beta"));
        var page = examRepo.findCreatedByUser(userId, "alpha", null, null,
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getCode()).isEqualTo("CR1");
    }

    // -- Exam pessimistic lock --

    @Test
    void examPessimisticLockRunsInTransaction() {
        Exam e = examRepo.saveAndFlush(new Exam(userId, subjectId, "PL", "T"));
        entityManager.flush();
        entityManager.clear();
        // Should not throw; executes SELECT ... FOR UPDATE
        Exam locked = examRepo.findByIdForUpdate(e.getId()).orElseThrow();
        assertThat(locked.getCode()).isEqualTo("PL");
    }

    // -- Exam lifecycle transitions --

    @Test
    void examPublishAndArchiveLifecycle() {
        Exam e = examRepo.saveAndFlush(new Exam(userId, subjectId, "LC", "T"));
        assertThat(e.getStatus()).isEqualTo(ExamStatus.DRAFT);
        e.markPublished(Instant.now(), BigDecimal.TEN);
        examRepo.saveAndFlush(e);
        assertThat(examRepo.findById(e.getId()).orElseThrow().getStatus()).isEqualTo(ExamStatus.PUBLISHED);
        e.markArchived();
        examRepo.saveAndFlush(e);
        assertThat(examRepo.findById(e.getId()).orElseThrow().getStatus()).isEqualTo(ExamStatus.ARCHIVED);
    }

    // -- Section ordering --

    @Test
    void sectionOrderingByPosition() {
        Exam e = examRepo.saveAndFlush(new Exam(userId, subjectId, "S1", "T"));
        sectionRepo.saveAndFlush(new ExamSection(e.getId(), "C", 2));
        sectionRepo.saveAndFlush(new ExamSection(e.getId(), "A", 0));
        sectionRepo.saveAndFlush(new ExamSection(e.getId(), "B", 1));
        List<ExamSection> ordered = sectionRepo.findAllByExamIdOrderByPositionAsc(e.getId());
        assertThat(ordered).extracting(ExamSection::getTitle).containsExactly("A", "B", "C");
    }

    @Test
    void sectionDeleteAllByExam() {
        Exam e = examRepo.saveAndFlush(new Exam(userId, subjectId, "S2", "T"));
        sectionRepo.saveAndFlush(new ExamSection(e.getId(), "A", 0));
        sectionRepo.saveAndFlush(new ExamSection(e.getId(), "B", 1));
        sectionRepo.deleteAllByExamId(e.getId());
        sectionRepo.flush();
        assertThat(sectionRepo.findAllByExamIdOrderByPositionAsc(e.getId())).isEmpty();
    }

    // -- Question/Option ordering --

    @Test
    void optionOrderingByPosition() {
        Exam e = examRepo.saveAndFlush(new Exam(userId, subjectId, "QO1", "T"));
        ExamSection s = sectionRepo.saveAndFlush(new ExamSection(e.getId(), "Sec", 0));
        ExamQuestion q = questionRepo.saveAndFlush(new ExamQuestion(e.getId(), s.getId(), questionIdA, questionVersionIdA,
                "QC", QuestionType.SINGLE_CHOICE, "c", BigDecimal.ONE, 0));
        optionRepo.saveAndFlush(new ExamQuestionOption(q.getId(), "C", "third", false, 2));
        optionRepo.saveAndFlush(new ExamQuestionOption(q.getId(), "A", "first", true, 0));
        optionRepo.saveAndFlush(new ExamQuestionOption(q.getId(), "B", "second", false, 1));
        List<ExamQuestionOption> ordered = optionRepo.findAllByExamQuestionIdOrderByPositionAsc(q.getId());
        assertThat(ordered).extracting(ExamQuestionOption::getOptionKey).containsExactly("A", "B", "C");
    }

    @Test
    void questionCountByExamIds() {
        Exam e = examRepo.saveAndFlush(new Exam(userId, subjectId, "QCNT", "T"));
        ExamSection s = sectionRepo.saveAndFlush(new ExamSection(e.getId(), "Sec", 0));
        long q2 = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES ("
                + "(SELECT question_bank_id FROM questions WHERE id = " + questionIdA + "),'rqB'," + userId + ")");
        long v2 = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + q2 + ",1,'SINGLE_CHOICE','c2','MEDIUM',1,'{}'::jsonb," + userId + ")");
        questionRepo.saveAndFlush(new ExamQuestion(e.getId(), s.getId(), questionIdA, questionVersionIdA,
                "QC", QuestionType.SINGLE_CHOICE, "c", BigDecimal.ONE, 0));
        questionRepo.saveAndFlush(new ExamQuestion(e.getId(), s.getId(), q2, v2,
                "QC2", QuestionType.SINGLE_CHOICE, "c2", BigDecimal.ONE, 1));
        List<Object[]> counts = questionRepo.countByExamIdsIn(List.of(e.getId()));
        assertThat(counts).hasSize(1);
        assertThat((Long) counts.get(0)[1]).isEqualTo(2L);
    }

    // -- Session creator lookup --

    @Test
    void sessionCreatorLookup() {
        Exam e = examRepo.saveAndFlush(new Exam(userId, subjectId, "SS1", "T"));
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        ExamSession session = sessionRepo.saveAndFlush(new ExamSession(
                e.getId(), "CODE", "T", starts, ends, 1, 60, userId));
        assertThat(session.getCreatedBy()).isEqualTo(userId);
        assertThat(sessionRepo.existsByCreatedByAndCodeIgnoreCase(session.getCreatedBy(), "code")).isTrue();
        assertThat(sessionRepo.existsByCreatedByAndCodeIgnoreCase(99999L, "code")).isFalse();
    }

    @Test
    void sessionsOfExamPaging() {
        Exam e = examRepo.saveAndFlush(new Exam(userId, subjectId, "SSP", "T"));
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        sessionRepo.saveAndFlush(new ExamSession(e.getId(), "P1", "T1", starts, ends, 1, 60, userId));
        sessionRepo.saveAndFlush(new ExamSession(e.getId(), "P2", "T2", starts, ends, 1, 60, userId));
        var page = sessionRepo.findAllByExamIdOrderByIdDesc(e.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    // -- Participant existence + batch --

    @Test
    void participantExistenceAndBatchLookup() {
        Exam e = examRepo.saveAndFlush(new Exam(userId, subjectId, "P1", "T"));
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        ExamSession session = sessionRepo.saveAndFlush(new ExamSession(
                e.getId(), "PC", "T", starts, ends, 1, 60, userId));
        participantRepo.saveAndFlush(new ExamSessionParticipant(userId, session.getId(), userId));
        assertThat(participantRepo.findByExamSessionIdAndUserId(session.getId(), userId)).isPresent();
        assertThat(participantRepo.findAllByExamSessionIdAndUserIdIn(
                session.getId(), List.of(userId, 99999L))).hasSize(1);
    }

    // -- Optimistic lock: Exam @Version --

    @Test
    void examOptimisticLockRejectsStaleUpdate() {
        Exam saved = examRepo.saveAndFlush(new Exam(userId, subjectId, "OL1", "T"));
        entityManager.clear();
        Exam managed = entityManager.find(Exam.class, saved.getId());
        assertThat(managed.getVersion()).isZero();
        // Simulate concurrent modification
        jdbc.update("UPDATE exams SET version = version + 1 WHERE id = ?", saved.getId());
        managed.setDescription("stale");
        assertThatThrownBy(() -> entityManager.flush())
                .isInstanceOfAny(OptimisticLockingFailureException.class,
                        jakarta.persistence.OptimisticLockException.class);
    }

    // -- Optimistic lock: ExamSession @Version --

    @Test
    void sessionOptimisticLockRejectsStaleUpdate() {
        Exam e = examRepo.saveAndFlush(new Exam(userId, subjectId, "OL2", "T"));
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        ExamSession saved = sessionRepo.saveAndFlush(new ExamSession(
                e.getId(), "OL2C", "T", starts, ends, 1, 60, userId));
        entityManager.clear();
        ExamSession managed = entityManager.find(ExamSession.class, saved.getId());
        assertThat(managed.getVersion()).isZero();
        jdbc.update("UPDATE exam_sessions SET version = version + 1 WHERE id = ?", saved.getId());
        managed.updateConfig("stale", null, null, null);
        assertThatThrownBy(() -> entityManager.flush())
                .isInstanceOfAny(OptimisticLockingFailureException.class,
                        jakarta.persistence.OptimisticLockException.class);
    }

    // -- Participant version increment after block --

    @Test
    void participantVersionIncrementsAfterBlock() {
        Exam e = examRepo.saveAndFlush(new Exam(userId, subjectId, "PV1", "T"));
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        ExamSession session = sessionRepo.saveAndFlush(new ExamSession(
                e.getId(), "PVC", "T", starts, ends, 1, 60, userId));
        ExamSessionParticipant p = participantRepo.saveAndFlush(
                new ExamSessionParticipant(userId, session.getId(), userId));
        assertThat(p.getVersion()).isZero();
        p.block(Instant.now());
        participantRepo.saveAndFlush(p);
        entityManager.clear();
        ExamSessionParticipant reloaded = participantRepo.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getVersion()).isGreaterThan(0);
        assertThat(reloaded.getStatus()).isEqualTo(ExamSessionParticipantStatus.BLOCKED);
    }

    // -- Helpers --

    private ExamPurpose makePurpose(String code, String title, int position) {
        ExamPurpose p = new ExamPurpose(code, title);
        p.setPosition(position);
        return p;
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
