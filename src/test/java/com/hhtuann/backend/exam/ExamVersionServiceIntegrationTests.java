package com.hhtuann.backend.exam;

import com.hhtuann.backend.exam.application.ExamService;
import com.hhtuann.backend.exam.domain.model.ExamQuestion;
import com.hhtuann.backend.exam.domain.model.ExamQuestionOption;
import com.hhtuann.backend.exam.domain.model.ExamSection;
import com.hhtuann.backend.exam.domain.model.ExamVersionStatus;
import com.hhtuann.backend.exam.dto.CreateExamVersionRequest;
import com.hhtuann.backend.exam.dto.CreateExamVersionResponse;
import com.hhtuann.backend.exam.dto.TeacherExamEditorResponse;
import com.hhtuann.backend.exam.dto.UpdateDraftCompositionRequest;
import com.hhtuann.backend.exam.dto.UpdateDraftCompositionRequest.CompositionQuestionRequest;
import com.hhtuann.backend.exam.dto.UpdateDraftCompositionRequest.CompositionSectionRequest;
import com.hhtuann.backend.exam.exception.ExamErrorCode;
import com.hhtuann.backend.exam.exception.ExamException;
import com.hhtuann.backend.exam.repository.ExamQuestionOptionRepository;
import com.hhtuann.backend.exam.repository.ExamQuestionRepository;
import com.hhtuann.backend.exam.repository.ExamSectionRepository;
import com.hhtuann.backend.exam.repository.ExamVersionRepository;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-level integration tests for POST /api/exams/{examId}/versions (A3.2-2B —
 * create next DRAFT by cloning a PUBLISHED version). Real PostgreSQL 17 Testcontainers.
 * PUBLISHED source versions are staged via jdbc (the publish endpoint lands in a later
 * checkpoint). Covers clone correctness, pinned-source preservation, deep-copy, exam-state
 * immutability, ordering, optimistic/version errors, no per-source SELECT growth, rollback.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ExamVersionServiceIntegrationTests {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExamService examService;
    @Autowired private ExamVersionRepository versionRepo;
    @Autowired private ExamSectionRepository sectionRepo;
    @Autowired private ExamQuestionRepository questionRepo;
    @Autowired private ExamQuestionOptionRepository optionRepo;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private long teacherUserId;
    private long schoolId;
    private long subjectId;
    private long teacherProfileId;

    @BeforeEach
    void setUp() {
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('vt','vt@t','h','VT')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", teacherUserId);
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('VS','Version School')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + gl + ",'SUB','Sub')");
        teacherProfileId = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacherUserId + "," + schoolId + ",'VTC')");
    }

    // -- Test group: explicit + null clone success --

    @Test
    void explicitClonePublishedVersionSuccess() {
        long bank = newBank();
        long q1 = activeSingleChoice(bank, "q1");
        long q2 = activeSingleChoice(bank, "q2");
        Long examId = composeAndPublish("E1", List.of(q1, q2));
        long v1Id = versionRepo.findAllByExamIdOrderByVersionNumberDesc(examId).get(0).getId();

        CreateExamVersionResponse r = examService.createNextVersion(teacherUserId, examId, new CreateExamVersionRequest(1));

        assertThat(r.versionNumber()).isEqualTo(2);
        assertThat(r.status()).isEqualTo("DRAFT");
        assertThat(r.clonedFrom()).isEqualTo(1);
        // New DRAFT cloned with 2 questions, pinned source IDs preserved, new child IDs.
        long v2Id = draftVersionId(examId);
        assertThat(v2Id).isNotEqualTo(v1Id);
        var clonedQuestions = questionRepo.findAllByExamVersionId(v2Id);
        assertThat(clonedQuestions).hasSize(2);
        assertThat(clonedQuestions).extracting(ExamQuestion::getSourceQuestionId).containsExactlyInAnyOrder(q1, q2);
        assertThat(clonedQuestions).allSatisfy(q -> assertThat(q.getId()).isNotIn(
                questionRepo.findAllByExamVersionId(v1Id).stream().map(ExamQuestion::getId).toList()));
    }

    @Test
    void nullCloneLatestPublishedSuccess() {
        long bank = newBank();
        long q = activeSingleChoice(bank, "q");
        Long examId = composeAndPublish("E2", List.of(q));

        CreateExamVersionResponse r = examService.createNextVersion(teacherUserId, examId, new CreateExamVersionRequest(null));

        assertThat(r.versionNumber()).isEqualTo(2);
        assertThat(r.clonedFrom()).isEqualTo(1); // latest (only) PUBLISHED
        assertThat(r.status()).isEqualTo("DRAFT");
    }

    @Test
    void nullClonePicksLatestByVersionNumberDesc() {
        long bank = newBank();
        long q = activeSingleChoice(bank, "q");
        Long examId = composeAndPublish("E3", List.of(q)); // v1 PUBLISHED, cvn=1
        // Stage a 2nd PUBLISHED version (minimal, no composition) and bump cvn so newVersionNumber=3.
        insert("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) "
                + "VALUES (" + schoolId + "," + examId + ",2,'PUBLISHED',5.00,now()," + teacherUserId + ")");
        jdbc.update("UPDATE exams SET current_version_number = 2 WHERE id = ?", examId);

        CreateExamVersionResponse r = examService.createNextVersion(teacherUserId, examId, new CreateExamVersionRequest(null));

        assertThat(r.clonedFrom()).isEqualTo(2); // latest PUBLISHED by versionNumber DESC
        assertThat(r.versionNumber()).isEqualTo(3);
    }

    // -- Test group: conflict / not-found --

    @Test
    void existingDraftConflict() {
        // Fresh exam still has its v1 DRAFT (never published) -> step-4 guard fires.
        Long examId = createExam("E4");
        assertThatThrownBy(() -> examService.createNextVersion(teacherUserId, examId, new CreateExamVersionRequest(null)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VERSION_NOT_DRAFT));
    }

    @Test
    void noPublishedConflict() {
        Long examId = createExam("E5");
        long v1 = draftVersionId(examId);
        jdbc.update("DELETE FROM exam_versions WHERE id = ?", v1); // no versions at all
        assertThatThrownBy(() -> examService.createNextVersion(teacherUserId, examId, new CreateExamVersionRequest(null)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VERSION_NOT_DRAFT));
    }

    @Test
    void explicitVersionNotFound404() {
        long bank = newBank();
        Long examId = composeAndPublish("E6", List.of(activeSingleChoice(bank, "q")));
        assertThatThrownBy(() -> examService.createNextVersion(teacherUserId, examId, new CreateExamVersionRequest(999)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VERSION_NOT_FOUND));
    }

    @Test
    void explicitSourceIsDraftConflict409() {
        // Fresh exam: v1 DRAFT exists. Cloning v1 (a DRAFT) is rejected (step-4 guard; same
        // EXAM_VERSION_NOT_DRAFT code as the "source not PUBLISHED" branch).
        Long examId = createExam("E7");
        assertThatThrownBy(() -> examService.createNextVersion(teacherUserId, examId, new CreateExamVersionRequest(1)))
                .isInstanceOfSatisfying(ExamException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExamErrorCode.EXAM_VERSION_NOT_DRAFT));
    }

    // -- Test group: pinned source IDs preserved (no Question Bank current resolution) --

    @Test
    void cloneKeepsPinnedSourceIdsAfterBankBump() {
        long bank = newBank();
        long q = activeSingleChoice(bank, "q");
        long pinnedV = jdbc.queryForObject("SELECT id FROM question_versions WHERE question_id=? ORDER BY version_number DESC LIMIT 1", Long.class, q);
        Long examId = composeAndPublish("E8", List.of(q));
        // Bank bumps the question to version 2 (new current).
        insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) "
                + "VALUES (" + q + ",2,'SINGLE_CHOICE','c2','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        jdbc.update("UPDATE questions SET current_version_number = 2 WHERE id = ?", q);

        examService.createNextVersion(teacherUserId, examId, new CreateExamVersionRequest(1));

        ExamQuestion cloned = questionRepo.findAllByExamVersionId(draftVersionId(examId)).get(0);
        assertThat(cloned.getSourceQuestionVersionId()).isEqualTo(pinnedV); // still pinned at v1, NOT resolved to v2
    }

    // -- Test group: child IDs differ; JsonNode not aliased --

    @Test
    void childIdsDifferAndDeepCopyNoAlias() {
        long bank = newBank();
        long q = activeSingleChoice(bank, "q");
        Long examId = composeAndPublish("E9", List.of(q));
        long v1Id = versionRepo.findAllByExamIdOrderByVersionNumberDesc(examId).get(0).getId();
        ExamQuestion srcQ = questionRepo.findAllByExamVersionId(v1Id).get(0);
        ExamSection srcS = sectionRepo.findAllByExamVersionIdOrderByPositionAsc(v1Id).get(0);
        ExamQuestionOption srcO = optionRepo.findAllByExamQuestionIdInOrderByExamQuestionIdAscPositionAsc(List.of(srcQ.getId())).get(0);

        examService.createNextVersion(teacherUserId, examId, new CreateExamVersionRequest(1));

        long v2Id = draftVersionId(examId);
        ExamSection newS = sectionRepo.findAllByExamVersionIdOrderByPositionAsc(v2Id).get(0);
        ExamQuestion newQ = questionRepo.findAllByExamVersionId(v2Id).get(0);
        ExamQuestionOption newO = optionRepo.findAllByExamQuestionIdInOrderByExamQuestionIdAscPositionAsc(List.of(newQ.getId())).get(0);
        assertThat(newS.getId()).isNotEqualTo(srcS.getId());
        assertThat(newQ.getId()).isNotEqualTo(srcQ.getId());
        assertThat(newO.getId()).isNotEqualTo(srcO.getId());
        // JsonNode (metadata) deep-copied: mutating the clone must NOT affect the source.
        ObjectNode srcMeta = (ObjectNode) srcQ.getMetadata();
        int srcBefore = srcMeta.size();
        ((ObjectNode) newQ.getMetadata()).put("mutated", true);
        assertThat(srcQ.getMetadata().size()).isEqualTo(srcBefore); // source unchanged
    }

    // -- Test group: exam state unchanged --

    @Test
    void examReadyAndCurrentVersionNumberUnchanged() {
        long bank = newBank();
        Long examId = composeAndPublish("E10", List.of(activeSingleChoice(bank, "q1"), activeSingleChoice(bank, "q2")));
        // After composeAndPublish: exam.status=READY, currentVersionNumber=1.
        assertThat(jdbc.queryForObject("SELECT status FROM exams WHERE id=?", String.class, examId)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT current_version_number FROM exams WHERE id=?", Integer.class, examId)).isEqualTo(1);

        examService.createNextVersion(teacherUserId, examId, new CreateExamVersionRequest(null));

        // Exam stays READY; currentVersionNumber NOT bumped before publish.
        assertThat(jdbc.queryForObject("SELECT status FROM exams WHERE id=?", String.class, examId)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT current_version_number FROM exams WHERE id=?", Integer.class, examId)).isEqualTo(1);
        // New version is a DRAFT with DRAFT invariant (published_at null, total_points 0).
        var newDraft = versionRepo.findFirstByExamIdAndStatus(examId, ExamVersionStatus.DRAFT).orElseThrow();
        assertThat(newDraft.getStatus()).isEqualTo(ExamVersionStatus.DRAFT);
        assertThat(newDraft.getPublishedAt()).isNull();
        assertThat(newDraft.getTotalPoints()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // -- Test group: order preserved --

    @Test
    void orderPreservedAcrossClone() {
        long bank = newBank();
        long qa = activeSingleChoice(bank, "qa");
        long qb = activeSingleChoice(bank, "qb");
        long qc = activeSingleChoice(bank, "qc");
        Long examId = createExam("E11");
        // Two sections; S0 has two questions inserted in REVERSE position order (qb@0, qa@1)
        // to verify the clone preserves position ordering rather than insertion order.
        examService.updateDraftComposition(teacherUserId, examId, new UpdateDraftCompositionRequest(1, null, null, List.of(
                new CompositionSectionRequest(0, "S0", null, List.of(
                        new CompositionQuestionRequest(qa, 1, null), new CompositionQuestionRequest(qb, 0, null))),
                new CompositionSectionRequest(1, "S1", null, List.of(
                        new CompositionQuestionRequest(qc, 0, null))))));
        publishV1(examId, new BigDecimal("3.00"));

        examService.createNextVersion(teacherUserId, examId, new CreateExamVersionRequest(1));

        long v2Id = draftVersionId(examId);
        var sections = sectionRepo.findAllByExamVersionIdOrderByPositionAsc(v2Id);
        assertThat(sections).extracting(ExamSection::getPosition).containsExactly(0, 1);
        // Section 0 questions ordered by position: qb(0), qa(1).
        var s0Questions = questionRepo.findAllByExamSectionIdInOrderByExamSectionIdAscPositionAsc(
                sections.stream().map(ExamSection::getId).toList());
        var s0 = s0Questions.stream().filter(q -> q.getExamSectionId().equals(sections.get(0).getId())).toList();
        assertThat(s0).extracting(ExamQuestion::getSourceQuestionId).containsExactly(qb, qa);
    }

    // -- Test group: rollback on clone failure --

    @Test
    void rollbackOnVersionNumberCollision() {
        Long examId = composeAndPublish("E12", List.of(activeSingleChoice(newBank(), "q"))); // v1 PUBLISHED, cvn=1
        // Stage a v2 PUBLISHED WITHOUT bumping cvn -> newVersionNumber=1+1=2 collides on insert.
        insert("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) "
                + "VALUES (" + schoolId + "," + examId + ",2,'PUBLISHED',5.00,now()," + teacherUserId + ")");
        // The collision aborts the tx at the version-row insert (BEFORE any children are written);
        // @Transactional rolls back the failed insert. PostgreSQL aborts the whole tx on the unique
        // violation (so post-failure count queries can't run in the same tx — the abort IS the
        // rollback). Children are inserted only after the version row succeeds, so no partial graph.
        assertThatThrownBy(() -> examService.createNextVersion(teacherUserId, examId, new CreateExamVersionRequest(null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -- Test group: query count (no per-source SELECT growth) --

    @Test
    void createNextVersionSelectCountDoesNotGrowWithSourceCount() {
        Statistics stats = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        // N=1: source PUBLISHED version with 1 question.
        long bank1 = newBank();
        Long examN1 = composeAndPublish("Q1", List.of(activeSingleChoice(bank1, "a")));
        stats.clear();
        examService.createNextVersion(teacherUserId, examN1, new CreateExamVersionRequest(1));
        long n1 = selectExecutionCount(stats);

        // N=20: a DIFFERENT exam whose source PUBLISHED version has 20 questions.
        long bank20 = newBank();
        List<Long> twenty = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            twenty.add(activeSingleChoice(bank20, "q" + i));
        }
        Long examN20 = composeAndPublish("Q20", twenty);
        stats.clear();
        examService.createNextVersion(teacherUserId, examN20, new CreateExamVersionRequest(1));
        long n20 = selectExecutionCount(stats);

        System.out.println("VERSION_QUERY_COUNT SELECT executions N=1=" + n1 + " N=20=" + n20);
        // Reads are batched (sections/questions/options each 1 query) => constant w.r.t. N.
        // A per-source SELECT regression would add ~19 executions for N=20 -> fail.
        assertThat(Math.abs(n20 - n1))
                .as("SELECT execution delta N=1=%d N=20=%d must be constant (no per-source read)", n1, n20)
                .isLessThanOrEqualTo(4L);
        assertThat(n20).as("SELECT executions N=20").isLessThan(35L);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private Long composeAndPublish(String code, List<Long> questionIds) {
        Long examId = createExam(code);
        List<CompositionQuestionRequest> qs = new ArrayList<>();
        for (int i = 0; i < questionIds.size(); i++) {
            qs.add(new CompositionQuestionRequest(questionIds.get(i), i, null));
        }
        examService.updateDraftComposition(teacherUserId, examId,
                new UpdateDraftCompositionRequest(1, null, null, List.of(
                        new CompositionSectionRequest(0, "S", null, qs.isEmpty() ? List.of() : qs))));
        publishV1(examId, BigDecimal.valueOf(questionIds.size()));
        return examId;
    }

    private void publishV1(Long examId, BigDecimal totalPoints) {
        long v1 = draftVersionId(examId);
        jdbc.update("UPDATE exam_versions SET status='PUBLISHED', published_at=now(), total_points=? WHERE id=?", totalPoints, v1);
        jdbc.update("UPDATE exams SET status='READY' WHERE id=?", examId);
        // The jdbc status/READY flip bypasses Hibernate; clear the persistence context so the
        // service (next call) re-reads v1 as PUBLISHED and exam as READY from the DB instead of
        // serving the stale managed entities left by createExam/updateDraftComposition.
        entityManager.clear();
    }

    private long newBank() {
        return insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES ("
                + schoolId + "," + subjectId + "," + teacherProfileId + ",'VB" + Math.abs(System.nanoTime() % 1_000_000_000L) + "','Bank')");
    }

    private long activeSingleChoice(long bank, String code) {
        long q = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'" + code + "','ACTIVE',1," + teacherUserId + ")");
        long ver = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        // 4 options A-D (A correct) so PUT composition snapshots them and the clone carries options.
        jdbc.update("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + ver + ",'A','opt',true,0)");
        jdbc.update("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + ver + ",'B','opt',false,1)");
        jdbc.update("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + ver + ",'C','opt',false,2)");
        jdbc.update("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + ver + ",'D','opt',false,3)");
        return q;
    }

    private Long createExam(String code) {
        return examService.createExam(teacherUserId,
                new com.hhtuann.backend.exam.dto.CreateExamRequest(subjectId, null, code, "T", null)).id();
    }

    private long draftVersionId(Long examId) {
        return versionRepo.findFirstByExamIdAndStatus(examId, ExamVersionStatus.DRAFT).orElseThrow().getId();
    }

    private long selectExecutionCount(Statistics statistics) {
        return Arrays.stream(statistics.getQueries())
                .filter(Objects::nonNull)
                .filter(q -> q.trim().toLowerCase().startsWith("select"))
                .mapToLong(q -> statistics.getQueryStatistics(q).getExecutionCount())
                .sum();
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
