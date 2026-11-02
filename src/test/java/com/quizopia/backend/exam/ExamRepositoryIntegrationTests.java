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
    private long schoolId;
    private long subjectId;
    private long teacherProfileId;
    private long studentProfileId;
    private long questionIdA;
    private long questionVersionIdA;

    @BeforeEach
    void setUp() {
        userId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('ru','ru@t','h','RU')");
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('RS','Repo School')");
        long glId = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + glId + ",'SUB','S')");
        teacherProfileId = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + userId + "," + schoolId + ",'RTC')");
        studentProfileId = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + userId + "," + schoolId + ",'RSC')");
        long bankId = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + schoolId + "," + subjectId + "," + teacherProfileId + ",'RB','Bank')");
        questionIdA = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bankId + ",'rqA'," + userId + ")");
        questionVersionIdA = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + questionIdA + ",1,'SINGLE_CHOICE','ca','MEDIUM',1,'{}'::jsonb," + userId + ")");
    }

    // -- Purpose ordering --

    @Test
    void purposeOrderingByPosition() {
        purposeRepo.saveAndFlush(makePurpose("Z", "Z Title", 2));
        purposeRepo.saveAndFlush(makePurpose("A", "A Title", 0));
        purposeRepo.saveAndFlush(makePurpose("M", "M Title", 1));
        List<ExamPurpose> ordered = purposeRepo.findAllBySchoolIdOrderByPositionAsc(schoolId);
        assertThat(ordered).extracting(ExamPurpose::getCode).containsExactly("A", "M", "Z");
    }

    @Test
    void purposeExistsByCodeIgnoreCase() {
        purposeRepo.saveAndFlush(makePurpose("MIDTERM", "Mid", 0));
        assertThat(purposeRepo.existsBySchoolIdAndCodeIgnoreCase(schoolId, "midterm")).isTrue();
        assertThat(purposeRepo.existsBySchoolIdAndCodeIgnoreCase(schoolId, "MIDTERM")).isTrue();
        assertThat(purposeRepo.existsBySchoolIdAndCodeIgnoreCase(schoolId, "FINAL")).isFalse();
    }

    // -- Exam owner lookup --

    @Test
    void examOwnerLookup() {
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "EX1", "T"));
        assertThat(examRepo.findByIdAndOwnerTeacherId(e.getId(), teacherProfileId)).isPresent();
        assertThat(examRepo.findByIdAndOwnerTeacherId(e.getId(), 99999L)).isEmpty();
    }

    @Test
    void examExistsByOwnerAndCodeIgnoreCase() {
        examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "UNIQUE", "T"));
        assertThat(examRepo.existsByOwnerTeacherIdAndCodeIgnoreCase(teacherProfileId, "unique")).isTrue();
        assertThat(examRepo.existsByOwnerTeacherIdAndCodeIgnoreCase(teacherProfileId, "OTHER")).isFalse();
    }

    @Test
    void examExistsByWorkspaceCreatorAndCodeIgnoreCase() {
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "WSUNIQ", "T"));
        Long workspaceId = jdbc.queryForObject(
                "SELECT workspace_id FROM exams WHERE id = " + e.getId(), Long.class);
        assertThat(examRepo.existsByWorkspaceIdAndCreatedByUserUserIdAndCodeIgnoreCase(
                workspaceId, userId, "wsuniq")).isTrue();
        assertThat(examRepo.existsByWorkspaceIdAndCreatedByUserUserIdAndCodeIgnoreCase(
                workspaceId, userId, "OTHER")).isFalse();
    }

    @Test
    void examFindAllByOwner() {
        examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "EX2", "T"));
        examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "EX3", "T"));
        assertThat(examRepo.findAllByOwnerTeacherId(teacherProfileId)).hasSize(2);
    }

    @Test
    void examCreatedByUserPagingAndFilter() {
        examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "CR1", "Alpha"));
        examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "CR2", "Beta"));
        var page = examRepo.findCreatedByUser(userId, "alpha", null, null,
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getCode()).isEqualTo("CR1");
    }

    // -- Exam pessimistic lock --

    @Test
    void examPessimisticLockRunsInTransaction() {
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "PL", "T"));
        entityManager.flush();
        entityManager.clear();
        // Should not throw; executes SELECT ... FOR UPDATE
        Exam locked = examRepo.findByIdForUpdate(e.getId()).orElseThrow();
        assertThat(locked.getCode()).isEqualTo("PL");
    }

    // -- Exam lifecycle transitions --

    @Test
    void examPublishAndArchiveLifecycle() {
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "LC", "T"));
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
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "S1", "T"));
        sectionRepo.saveAndFlush(new ExamSection(e.getId(), "C", 2));
        sectionRepo.saveAndFlush(new ExamSection(e.getId(), "A", 0));
        sectionRepo.saveAndFlush(new ExamSection(e.getId(), "B", 1));
        List<ExamSection> ordered = sectionRepo.findAllByExamIdOrderByPositionAsc(e.getId());
        assertThat(ordered).extracting(ExamSection::getTitle).containsExactly("A", "B", "C");
    }

    @Test
    void sectionDeleteAllByExam() {
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "S2", "T"));
        sectionRepo.saveAndFlush(new ExamSection(e.getId(), "A", 0));
        sectionRepo.saveAndFlush(new ExamSection(e.getId(), "B", 1));
        sectionRepo.deleteAllByExamId(e.getId());
        sectionRepo.flush();
        assertThat(sectionRepo.findAllByExamIdOrderByPositionAsc(e.getId())).isEmpty();
    }

    // -- Question/Option ordering --

    @Test
    void optionOrderingByPosition() {
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "QO1", "T"));
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
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "QCNT", "T"));
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

    // -- Session owner lookup --

    @Test
    void sessionOwnerLookup() {
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "SS1", "T"));
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        ExamSession session = sessionRepo.saveAndFlush(new ExamSession(
                e.getId(), schoolId, teacherProfileId, "CODE", "T", starts, ends, 1, 60, userId));
        assertThat(sessionRepo.findByIdAndOwnerTeacherId(session.getId(), teacherProfileId)).isPresent();
        assertThat(sessionRepo.findByIdAndOwnerTeacherId(session.getId(), 99999L)).isEmpty();
    }

    @Test
    void sessionsOfExamPaging() {
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "SSP", "T"));
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        sessionRepo.saveAndFlush(new ExamSession(e.getId(), schoolId, teacherProfileId, "P1", "T1", starts, ends, 1, 60, userId));
        sessionRepo.saveAndFlush(new ExamSession(e.getId(), schoolId, teacherProfileId, "P2", "T2", starts, ends, 1, 60, userId));
        var page = sessionRepo.findAllByExamIdOrderByIdDesc(e.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    // -- Participant existence + batch --

    @Test
    void participantExistenceAndBatchLookup() {
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "P1", "T"));
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        ExamSession session = sessionRepo.saveAndFlush(new ExamSession(
                e.getId(), schoolId, teacherProfileId, "PC", "T", starts, ends, 1, 60, userId));
        participantRepo.saveAndFlush(new ExamSessionParticipant(schoolId, session.getId(), studentProfileId, userId));
        assertThat(participantRepo.existsByExamSessionIdAndStudentProfileId(session.getId(), studentProfileId)).isTrue();
        assertThat(participantRepo.findAllByExamSessionIdAndStudentProfileIdIn(
                session.getId(), List.of(studentProfileId, 99999L))).hasSize(1);
    }

    // -- Optimistic lock: Exam @Version --

    @Test
    void examOptimisticLockRejectsStaleUpdate() {
        Exam saved = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "OL1", "T"));
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
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "OL2", "T"));
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        ExamSession saved = sessionRepo.saveAndFlush(new ExamSession(
                e.getId(), schoolId, teacherProfileId, "OL2C", "T", starts, ends, 1, 60, userId));
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
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "PV1", "T"));
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        ExamSession session = sessionRepo.saveAndFlush(new ExamSession(
                e.getId(), schoolId, teacherProfileId, "PVC", "T", starts, ends, 1, 60, userId));
        ExamSessionParticipant p = participantRepo.saveAndFlush(
                new ExamSessionParticipant(schoolId, session.getId(), studentProfileId, userId));
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
        ExamPurpose p = new ExamPurpose(schoolId, code, title);
        p.setPosition(position);
        return p;
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
