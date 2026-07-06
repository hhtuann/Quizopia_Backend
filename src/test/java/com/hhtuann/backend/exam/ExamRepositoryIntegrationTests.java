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
 * Integration tests for the 8 exam repository interfaces: derived queries,
 * pessimistic lock, and optimistic lock (@Version) on real PostgreSQL 17.
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
    @Autowired private ExamVersionRepository versionRepo;
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
    void examFindAllByOwner() {
        examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "EX2", "T"));
        examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "EX3", "T"));
        assertThat(examRepo.findAllByOwnerTeacherId(teacherProfileId)).hasSize(2);
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

    // -- Version queries --

    @Test
    void versionFindByExamAndNumber() {
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "V1", "T"));
        versionRepo.saveAndFlush(new ExamVersion(schoolId, e.getId(), 1, userId));
        assertThat(versionRepo.findByExamIdAndVersionNumber(e.getId(), 1)).isPresent();
        assertThat(versionRepo.findByExamIdAndVersionNumber(e.getId(), 2)).isEmpty();
    }

    @Test
    void versionLatestPublished() {
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "V2", "T"));
        ExamVersion v1 = versionRepo.saveAndFlush(new ExamVersion(schoolId, e.getId(), 1, userId));
        v1.markPublished(Instant.now(), BigDecimal.TEN);
        versionRepo.saveAndFlush(v1);
        ExamVersion v2 = versionRepo.saveAndFlush(new ExamVersion(schoolId, e.getId(), 2, userId));
        v2.markPublished(Instant.now(), BigDecimal.valueOf(20));
        versionRepo.saveAndFlush(v2);
        // Latest PUBLISHED by version_number DESC = v2
        ExamVersion latest = versionRepo.findFirstByExamIdAndStatusOrderByVersionNumberDesc(
                e.getId(), ExamVersionStatus.PUBLISHED).orElseThrow();
        assertThat(latest.getVersionNumber()).isEqualTo(2);
    }

    @Test
    void versionExistsByExamAndStatus() {
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "V3", "T"));
        versionRepo.saveAndFlush(new ExamVersion(schoolId, e.getId(), 1, userId));
        assertThat(versionRepo.existsByExamIdAndStatus(e.getId(), ExamVersionStatus.DRAFT)).isTrue();
        assertThat(versionRepo.existsByExamIdAndStatus(e.getId(), ExamVersionStatus.PUBLISHED)).isFalse();
    }

    // -- Section ordering --

    @Test
    void sectionOrderingByPosition() {
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "S1", "T"));
        ExamVersion v = versionRepo.saveAndFlush(new ExamVersion(schoolId, e.getId(), 1, userId));
        sectionRepo.saveAndFlush(new ExamSection(v.getId(), "C", 2));
        sectionRepo.saveAndFlush(new ExamSection(v.getId(), "A", 0));
        sectionRepo.saveAndFlush(new ExamSection(v.getId(), "B", 1));
        List<ExamSection> ordered = sectionRepo.findAllByExamVersionIdOrderByPositionAsc(v.getId());
        assertThat(ordered).extracting(ExamSection::getTitle).containsExactly("A", "B", "C");
    }

    @Test
    void sectionDeleteAllByVersion() {
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "S2", "T"));
        ExamVersion v = versionRepo.saveAndFlush(new ExamVersion(schoolId, e.getId(), 1, userId));
        sectionRepo.saveAndFlush(new ExamSection(v.getId(), "A", 0));
        sectionRepo.saveAndFlush(new ExamSection(v.getId(), "B", 1));
        sectionRepo.deleteAllByExamVersionId(v.getId());
        sectionRepo.flush();
        assertThat(sectionRepo.findAllByExamVersionIdOrderByPositionAsc(v.getId())).isEmpty();
    }

    // -- Question/Option ordering --

    @Test
    void optionOrderingByPosition() {
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "QO1", "T"));
        ExamVersion v = versionRepo.saveAndFlush(new ExamVersion(schoolId, e.getId(), 1, userId));
        ExamSection s = sectionRepo.saveAndFlush(new ExamSection(v.getId(), "Sec", 0));
        ExamQuestion q = questionRepo.saveAndFlush(new ExamQuestion(v.getId(), s.getId(), questionIdA, questionVersionIdA,
                "QC", QuestionType.SINGLE_CHOICE, "c", BigDecimal.ONE, 0));
        optionRepo.saveAndFlush(new ExamQuestionOption(q.getId(), "C", "third", false, 2));
        optionRepo.saveAndFlush(new ExamQuestionOption(q.getId(), "A", "first", true, 0));
        optionRepo.saveAndFlush(new ExamQuestionOption(q.getId(), "B", "second", false, 1));
        List<ExamQuestionOption> ordered = optionRepo.findAllByExamQuestionIdOrderByPositionAsc(q.getId());
        assertThat(ordered).extracting(ExamQuestionOption::getOptionKey).containsExactly("A", "B", "C");
    }

    // -- Session owner lookup --

    @Test
    void sessionOwnerLookup() {
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "SS1", "T"));
        ExamVersion v = versionRepo.saveAndFlush(new ExamVersion(schoolId, e.getId(), 1, userId));
        v.markPublished(Instant.now(), BigDecimal.TEN);
        versionRepo.saveAndFlush(v);
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        ExamSession session = sessionRepo.saveAndFlush(new ExamSession(
                schoolId, v.getId(), teacherProfileId, "CODE", "T", starts, ends, 1, userId));
        assertThat(sessionRepo.findByIdAndOwnerTeacherId(session.getId(), teacherProfileId)).isPresent();
        assertThat(sessionRepo.findByIdAndOwnerTeacherId(session.getId(), 99999L)).isEmpty();
    }

    // -- Participant existence + batch --

    @Test
    void participantExistenceAndBatchLookup() {
        Exam e = examRepo.saveAndFlush(new Exam(schoolId, subjectId, teacherProfileId, "P1", "T"));
        ExamVersion v = versionRepo.saveAndFlush(new ExamVersion(schoolId, e.getId(), 1, userId));
        v.markPublished(Instant.now(), BigDecimal.TEN);
        versionRepo.saveAndFlush(v);
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        ExamSession session = sessionRepo.saveAndFlush(new ExamSession(
                schoolId, v.getId(), teacherProfileId, "PC", "T", starts, ends, 1, userId));
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
        ExamVersion v = versionRepo.saveAndFlush(new ExamVersion(schoolId, e.getId(), 1, userId));
        v.markPublished(Instant.now(), BigDecimal.TEN);
        versionRepo.saveAndFlush(v);
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        ExamSession saved = sessionRepo.saveAndFlush(new ExamSession(
                schoolId, v.getId(), teacherProfileId, "OL2C", "T", starts, ends, 1, userId));
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
        ExamVersion v = versionRepo.saveAndFlush(new ExamVersion(schoolId, e.getId(), 1, userId));
        v.markPublished(Instant.now(), BigDecimal.TEN);
        versionRepo.saveAndFlush(v);
        Instant starts = Instant.now();
        Instant ends = starts.plusSeconds(3600);
        ExamSession session = sessionRepo.saveAndFlush(new ExamSession(
                schoolId, v.getId(), teacherProfileId, "PVC", "T", starts, ends, 1, userId));
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
