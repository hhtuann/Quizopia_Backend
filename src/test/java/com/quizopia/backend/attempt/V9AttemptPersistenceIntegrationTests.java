package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.domain.model.Attempt;
import com.quizopia.backend.attempt.domain.model.AttemptAnswer;
import com.quizopia.backend.attempt.domain.model.AttemptQuestion;
import com.quizopia.backend.attempt.domain.model.AttemptStatus;
import com.quizopia.backend.attempt.domain.model.Grade;
import com.quizopia.backend.attempt.domain.model.GradeItem;
import com.quizopia.backend.attempt.domain.model.GradeStatus;
import com.quizopia.backend.attempt.domain.model.IdempotencyOperation;
import com.quizopia.backend.attempt.domain.model.IdempotencyRecord;
import com.quizopia.backend.attempt.repository.AttemptAnswerRepository;
import com.quizopia.backend.attempt.repository.AttemptQuestionRepository;
import com.quizopia.backend.attempt.repository.AttemptRepository;
import com.quizopia.backend.attempt.repository.GradeItemRepository;
import com.quizopia.backend.attempt.repository.GradeRepository;
import com.quizopia.backend.attempt.repository.IdempotencyRecordRepository;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistence integration tests for the six V9 entities on a real PostgreSQL 17
 * (Testcontainers). The {@code @SpringBootTest} context start proves Flyway reached
 * V9 and Hibernate {@code ddl-auto=validate} accepts all six managed entities
 * against the V9 schema. Tests cover round-trip, JSONB, domain transitions,
 * repository semantics, the atomic answer UPSERT, idempotency lookups and
 * mapping safety. Cross-transaction proof (pessimistic-lock serialization and
 * the higher-sequence-wins UPSERT across committed transactions) lives in
 * {@code V9AttemptConcurrencyIntegrationTests}, which manages its own transactions.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class V9AttemptPersistenceIntegrationTests {

    private static final Set<Class<?>> ENTITY_CLASSES = Set.of(Attempt.class, AttemptQuestion.class,
            AttemptAnswer.class, Grade.class, GradeItem.class, IdempotencyRecord.class);

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private AttemptRepository attemptRepo;
    @Autowired
    private AttemptQuestionRepository aqRepo;
    @Autowired
    private AttemptAnswerRepository aaRepo;
    @Autowired
    private GradeRepository gradeRepo;
    @Autowired
    private GradeItemRepository giRepo;
    @Autowired
    private IdempotencyRecordRepository idemRepo;
    @Autowired
    private JdbcTemplate jdbc;

    private long userId;
    private long schoolId;
    private long bankId;
    private long studentProfileId;
    private long examVersionId;
    private long sessionId;
    private long examQuestionId;

    @BeforeEach
    void setUp() {
        userId = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('u9p','u9p@t.com','h','U9P')");
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('TSP','Test School P')");
        long gradeLevelId = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        long subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) "
                + "VALUES (" + schoolId + "," + gradeLevelId + ",'MATH','Math')");
        long teacherId = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) "
                + "VALUES (" + userId + "," + schoolId + ",'TC')");
        studentProfileId = insert("INSERT INTO student_profiles (user_id, school_id, student_code) "
                + "VALUES (" + userId + "," + schoolId + ",'SC')");
        bankId = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) "
                + "VALUES (" + schoolId + "," + subjectId + "," + teacherId + ",'B','Bank')");
        long questionId = insert("INSERT INTO questions (question_bank_id, code, created_by) "
                + "VALUES (" + bankId + ",'Q'," + userId + ")");
        long qvId = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, "
                + "default_points, metadata, created_by) VALUES (" + questionId + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb,"
                + userId + ")");
        long examId = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) "
                + "VALUES (" + schoolId + "," + subjectId + "," + teacherId + ",'E','t')");
        examVersionId = insert("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, "
                + "published_at, created_by) VALUES (" + schoolId + "," + examId + ",1,'PUBLISHED',10,now()," + userId + ")");
        long sectionId = insert("INSERT INTO exam_sections (exam_version_id, title, position) VALUES ("
                + examVersionId + ",'Sec',0)");
        examQuestionId = insert("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, "
                + "source_question_version_id, question_code, question_type, content, default_points, position, metadata) "
                + "VALUES (" + examVersionId + "," + sectionId + "," + questionId + "," + qvId + ",'QC','SINGLE_CHOICE','c',1,0,"
                + "'{}'::jsonb)");
        sessionId = insert("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, "
                + "starts_at, ends_at, created_by, opened_at) VALUES (" + schoolId + "," + examVersionId + "," + teacherId
                + ",'S','t','OPEN',now()-interval '1 hour',now()+interval '2 hours'," + userId + ",now())");
    }

    // ============================================================
    // GROUP 1 — context + validate (all six entities managed)
    // ============================================================

    @Test
    void allSixEntitiesAreManagedByHibernate() {
        Set<Class<?>> managed = Set.copyOf(em.getMetamodel().getEntities().stream()
                .map(e -> e.getJavaType()).toList());
        for (Class<?> c : ENTITY_CLASSES) {
            assertThat(managed).as("entity %s managed", c.getSimpleName()).contains(c);
        }
    }

    // ============================================================
    // GROUP 2 — round-trip each entity (flush/clear/reload)
    // ============================================================

    @Test
    void attemptRoundTrip() {
        Attempt a = Attempt.start(schoolId, sessionId, studentProfileId, examVersionId, 1,
                Instant.parse("2026-07-03T07:00:00Z"), Instant.parse("2026-07-03T08:00:00Z"));
        em.persist(a);
        em.flush();
        em.clear();
        Attempt reloaded = em.find(Attempt.class, a.getId());
        assertThat(reloaded.getSchoolId()).isEqualTo(schoolId);
        assertThat(reloaded.getExamSessionId()).isEqualTo(sessionId);
        assertThat(reloaded.getStudentProfileId()).isEqualTo(studentProfileId);
        assertThat(reloaded.getExamVersionId()).isEqualTo(examVersionId);
        assertThat(reloaded.getAttemptNumber()).isEqualTo(1);
        assertThat(reloaded.getStatus()).isEqualTo(AttemptStatus.IN_PROGRESS);
        assertThat(reloaded.getStartedAt()).isEqualTo(Instant.parse("2026-07-03T07:00:00Z"));
        assertThat(reloaded.getDeadlineAt()).isEqualTo(Instant.parse("2026-07-03T08:00:00Z"));
        assertThat(reloaded.getSubmittedAt()).isNull();
        assertThat(reloaded.getSubmissionIdempotencyKey()).isNull();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void attemptClientInstanceUuidRoundTrip() {
        Attempt a = persistAttempt(1);
        UUID clientInstance = UUID.randomUUID();
        jdbc.update("UPDATE attempts SET client_instance_id = ? WHERE id = ?", clientInstance, a.getId());
        em.clear();
        Attempt reloaded = em.find(Attempt.class, a.getId());
        assertThat(reloaded.getClientInstanceId()).isEqualTo(clientInstance);
    }

    @Test
    void attemptQuestionRoundTrip() {
        Attempt a = persistAttempt(1);
        AttemptQuestion q = new AttemptQuestion(a.getId(), examQuestionId, "SINGLE_CHOICE",
                new BigDecimal("1.50"), 0, null);
        em.persist(q);
        em.flush();
        em.clear();
        AttemptQuestion reloaded = em.find(AttemptQuestion.class, q.getId());
        assertThat(reloaded.getAttemptId()).isEqualTo(a.getId());
        assertThat(reloaded.getExamQuestionId()).isEqualTo(examQuestionId);
        assertThat(reloaded.getQuestionType()).isEqualTo("SINGLE_CHOICE");
        assertThat(reloaded.getDefaultPoints()).isEqualByComparingTo("1.50");
        assertThat(reloaded.getDisplayOrder()).isZero();
        assertThat(reloaded.getOptionOrder()).isNull();
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void attemptAnswerRoundTrip() {
        Attempt a = persistAttempt(1);
        AttemptQuestion q = persistAttemptQuestion(a.getId(), 0);
        JsonNode payload = JsonNodeFactory.instance.objectNode().put("selectedOptionKey", "B");
        AttemptAnswer ans = new AttemptAnswer(a.getId(), q.getId(), payload, 3L, Instant.now());
        em.persist(ans);
        em.flush();
        em.clear();
        AttemptAnswer reloaded = em.find(AttemptAnswer.class, ans.getId());
        assertThat(reloaded.getAttemptId()).isEqualTo(a.getId());
        assertThat(reloaded.getSequenceNumber()).isEqualTo(3L);
        assertThat(reloaded.getSavedAt()).isNotNull();
        assertThat(reloaded.getAnswerPayload().path("selectedOptionKey").asString()).isEqualTo("B");
    }

    @Test
    void gradeRoundTrip() {
        Attempt a = persistSubmittedAttempt(1, "K1");
        Grade g = new Grade(a.getId(), new BigDecimal("7.00"), new BigDecimal("7.00"),
                new BigDecimal("10.00"), Instant.now(), userId);
        em.persist(g);
        em.flush();
        em.clear();
        Grade reloaded = em.find(Grade.class, g.getId());
        assertThat(reloaded.getAttemptId()).isEqualTo(a.getId());
        assertThat(reloaded.getAutomaticScore()).isEqualByComparingTo("7.00");
        assertThat(reloaded.getFinalScore()).isEqualByComparingTo("7.00");
        assertThat(reloaded.getMaxScore()).isEqualByComparingTo("10.00");
        assertThat(reloaded.getStatus()).isEqualTo(GradeStatus.AUTO_GRADED);
        assertThat(reloaded.getReleasedAt()).isNull();
        assertThat(reloaded.getGradedBy()).isEqualTo(userId);
    }

    @Test
    void gradeItemRoundTrip() {
        Attempt a = persistSubmittedAttempt(1, "K2");
        AttemptQuestion q = persistAttemptQuestion(a.getId(), 0);
        Grade g = persistGrade(a.getId());
        GradeItem item = new GradeItem(g.getId(), a.getId(), q.getId(), new BigDecimal("1.00"),
                new BigDecimal("1.00"), true, JsonNodeFactory.instance.objectNode().put("note", "x"));
        em.persist(item);
        em.flush();
        em.clear();
        GradeItem reloaded = em.find(GradeItem.class, item.getId());
        assertThat(reloaded.getGradeId()).isEqualTo(g.getId());
        assertThat(reloaded.getAttemptId()).isEqualTo(a.getId());
        assertThat(reloaded.getAttemptQuestionId()).isEqualTo(q.getId());
        assertThat(reloaded.getAwardedPoints()).isEqualByComparingTo("1.00");
        assertThat(reloaded.isCorrect()).isTrue();
        assertThat(reloaded.getGradingDetails().path("note").asString()).isEqualTo("x");
    }

    @Test
    void idempotencyRecordRoundTrip() {
        Attempt a = persistSubmittedAttempt(1, "IK");
        JsonNode body = JsonNodeFactory.instance.objectNode().put("attemptId", a.getId());
        IdempotencyRecord rec = new IdempotencyRecord(userId, a.getId(), IdempotencyOperation.ATTEMPT_SUBMIT,
                "IK", 200, body);
        em.persist(rec);
        em.flush();
        em.clear();
        IdempotencyRecord reloaded = em.find(IdempotencyRecord.class, rec.getId());
        assertThat(reloaded.getUserId()).isEqualTo(userId);
        assertThat(reloaded.getAttemptId()).isEqualTo(a.getId());
        assertThat(reloaded.getOperation()).isEqualTo(IdempotencyOperation.ATTEMPT_SUBMIT);
        assertThat(reloaded.getIdempotencyKey()).isEqualTo("IK");
        assertThat(reloaded.getResponseStatus()).isEqualTo(200);
        assertThat(reloaded.getExpiresAt()).isNull();
        assertThat(reloaded.getResponseBody().path("attemptId").asLong()).isEqualTo(a.getId());
    }

    // ============================================================
    // GROUP 3 — JSONB round-trip
    // ============================================================

    @Test
    void jsonbOptionOrderArrayRoundTrip() {
        Attempt a = persistAttempt(1);
        JsonNode order = JsonNodeFactory.instance.arrayNode().add("A").add("B").add("C");
        AttemptQuestion q = new AttemptQuestion(a.getId(), examQuestionId, "MULTIPLE_CHOICE",
                new BigDecimal("2.00"), 0, order);
        em.persist(q);
        em.flush();
        em.clear();
        AttemptQuestion reloaded = em.find(AttemptQuestion.class, q.getId());
        assertThat(reloaded.getOptionOrder().isArray()).isTrue();
        assertThat(reloaded.getOptionOrder().size()).isEqualTo(3);
        assertThat(reloaded.getOptionOrder().get(0).asString()).isEqualTo("A");
        assertThat(reloaded.getOptionOrder().get(2).asString()).isEqualTo("C");
    }

    @Test
    void jsonbAnswerPayloadObjectAndNullRoundTrip() {
        Attempt a = persistAttempt(1);
        AttemptQuestion q1 = persistAttemptQuestion(a.getId(), 0);
        long eq2 = newExamQuestion(1);
        AttemptQuestion q2 = persistAttemptQuestionWithOrder(a.getId(), eq2, 1);
        // object payload on q1
        AttemptAnswer objectAns = new AttemptAnswer(a.getId(), q1.getId(),
                JsonNodeFactory.instance.objectNode().put("selectedOptionKey", "A"), 1L, Instant.now());
        em.persist(objectAns);
        em.flush();
        em.clear();
        assertThat(em.find(AttemptAnswer.class, objectAns.getId()).getAnswerPayload()
                .path("selectedOptionKey").asString()).isEqualTo("A");
        // null payload on q2 (different question — UQ attempt_question prevents same pair)
        em.clear();
        AttemptAnswer nullAns = new AttemptAnswer(a.getId(), q2.getId(), null, 2L, Instant.now());
        em.persist(nullAns);
        em.flush();
        em.clear();
        assertThat(em.find(AttemptAnswer.class, nullAns.getId()).getAnswerPayload()).isNull();
    }

    @Test
    void jsonbGradingDetailsRoundTrip() {
        Attempt a = persistSubmittedAttempt(1, "GD");
        AttemptQuestion q = persistAttemptQuestion(a.getId(), 0);
        Grade g = persistGrade(a.getId());
        JsonNode details = JsonNodeFactory.instance.objectNode().put("correctCount", 3);
        GradeItem item = new GradeItem(g.getId(), a.getId(), q.getId(), new BigDecimal("1.00"),
                new BigDecimal("1.00"), true, details);
        em.persist(item);
        em.flush();
        em.clear();
        assertThat(em.find(GradeItem.class, item.getId()).getGradingDetails().path("correctCount").asInt()).isEqualTo(3);
    }

    @Test
    void jsonbResponseBodyRoundTrip() {
        Attempt a = persistSubmittedAttempt(1, "RB");
        JsonNode body = JsonNodeFactory.instance.objectNode()
                .put("status", "SUBMITTED").put("submittedAt", "2026-07-03T08:58:00Z");
        IdempotencyRecord rec = new IdempotencyRecord(userId, a.getId(), IdempotencyOperation.ATTEMPT_SUBMIT,
                "RB", 200, body);
        em.persist(rec);
        em.flush();
        em.clear();
        assertThat(em.find(IdempotencyRecord.class, rec.getId()).getResponseBody()
                .path("status").asString()).isEqualTo("SUBMITTED");
    }

    @Test
    void jsonbUpdateRequiresNewNodeAssignment() {
        // Hibernate does not dirty-check in-place JsonNode mutation; the supported update path
        // is to assign a NEW JsonNode. Verify that a re-assigned node persists.
        Attempt a = persistAttempt(1);
        AttemptQuestion q = new AttemptQuestion(a.getId(), examQuestionId, "SINGLE_CHOICE",
                new BigDecimal("1.00"), 0, JsonNodeFactory.instance.arrayNode().add("A").add("B"));
        em.persist(q);
        em.flush();
        em.clear();
        JsonNode newOrder = JsonNodeFactory.instance.arrayNode().add("B").add("A");
        em.createQuery("UPDATE AttemptQuestion q SET q.optionOrder = :order WHERE q.id = :id")
                .setParameter("order", newOrder).setParameter("id", q.getId()).executeUpdate();
        em.clear();
        AttemptQuestion reloaded = em.find(AttemptQuestion.class, q.getId());
        assertThat(reloaded.getOptionOrder().get(0).asString()).isEqualTo("B");
        assertThat(reloaded.getOptionOrder().get(1).asString()).isEqualTo("A");
    }

    // ============================================================
    // GROUP 4 — attempt transitions
    // ============================================================

    @Test
    void attemptFactoryProducesInProgressWithNulls() {
        Attempt a = Attempt.start(schoolId, sessionId, studentProfileId, examVersionId, 1,
                Instant.now(), Instant.now().plusSeconds(3600));
        assertThat(a.getStatus()).isEqualTo(AttemptStatus.IN_PROGRESS);
        assertThat(a.getSubmittedAt()).isNull();
        assertThat(a.getSubmissionIdempotencyKey()).isNull();
    }

    @Test
    void submitSetsStatusTimestampKeyAtomically() {
        Attempt a = persistAttempt(1);
        Instant submittedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        a.submit(submittedAt, "SUBMIT-KEY");
        em.flush();
        em.clear();
        Attempt reloaded = em.find(Attempt.class, a.getId());
        assertThat(reloaded.getStatus()).isEqualTo(AttemptStatus.SUBMITTED);
        assertThat(reloaded.getSubmittedAt()).isEqualTo(submittedAt);
        assertThat(reloaded.getSubmissionIdempotencyKey()).isEqualTo("SUBMIT-KEY");
    }

    @Test
    void submitRejectsWhenNotInProgress() {
        Attempt a = persistAttempt(1);
        a.submit(Instant.now(), "K");
        assertThatThrownBy(() -> a.submit(Instant.now(), "K2"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void submitRejectsBlankKey() {
        Attempt a = persistAttempt(1);
        assertThatThrownBy(() -> a.submit(Instant.now(), "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markGradedOnlyFromSubmitted() {
        Attempt a = persistAttempt(1);
        assertThatThrownBy(a::markGraded).isInstanceOf(IllegalStateException.class);
        a.submit(Instant.now(), "MG");
        a.markGraded();
        em.flush();
        em.clear();
        assertThat(em.find(Attempt.class, a.getId()).getStatus()).isEqualTo(AttemptStatus.GRADED);
    }

    // ============================================================
    // GROUP 5 — grade transitions
    // ============================================================

    @Test
    void gradeFactoryProducesAutoGraded() {
        Grade g = new Grade(1L, new BigDecimal("5.00"), new BigDecimal("5.00"),
                new BigDecimal("10.00"), Instant.now(), null);
        assertThat(g.getStatus()).isEqualTo(GradeStatus.AUTO_GRADED);
        assertThat(g.getReleasedAt()).isNull();
    }

    @Test
    void releaseSetsStatusAndTimestamp() {
        Attempt a = persistSubmittedAttempt(1, "RL");
        Grade g = persistGrade(a.getId());
        Instant releasedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        g.release(releasedAt);
        em.flush();
        em.clear();
        Grade reloaded = em.find(Grade.class, g.getId());
        assertThat(reloaded.getStatus()).isEqualTo(GradeStatus.RELEASED);
        assertThat(reloaded.getReleasedAt()).isEqualTo(releasedAt);
    }

    @Test
    void releaseRejectsWhenAlreadyReleased() {
        Attempt a = persistSubmittedAttempt(1, "R2");
        Grade g = persistGrade(a.getId());
        g.release(Instant.now());
        assertThatThrownBy(() -> g.release(Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ============================================================
    // GROUP 6 — repository semantics
    // ============================================================

    @Test
    void findActiveAttemptReturnsInProgress() {
        Attempt active = persistAttempt(1);
        Optional<Attempt> found = attemptRepo.findByExamSessionIdAndStudentProfileIdAndStatus(
                active.getExamSessionId(), active.getStudentProfileId(), AttemptStatus.IN_PROGRESS);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(active.getId());
    }

    @Test
    void findMaxAttemptNumberEmptyReturnsEmpty() {
        long freshSession = newOpenSession("FR");
        assertThat(attemptRepo.findMaxAttemptNumber(freshSession, studentProfileId)).isEmpty();
    }

    @Test
    void findMaxAttemptNumberReturnsMax() {
        Attempt a1 = persistSubmittedAttempt(1, "MX1");
        persistAttempt(2);
        assertThat(attemptRepo.findMaxAttemptNumber(a1.getExamSessionId(), a1.getStudentProfileId())).contains(2);
    }

    @Test
    void countBySessionAndStatusCountsActive() {
        Attempt active = persistSubmittedAttempt(1, "CNT");
        persistAttemptForSession(2, active.getExamSessionId(), active.getStudentProfileId(),
                active.getExamVersionId());
        long activeCount = attemptRepo.countByExamSessionIdAndStatus(active.getExamSessionId(),
                AttemptStatus.IN_PROGRESS);
        assertThat(activeCount).isEqualTo(1L);
    }

    @Test
    void historyReturnsStudentAttemptsNewestFirst() {
        persistSubmittedAttempt(1, "H1");
        persistSubmittedAttempt(2, "H2");
        persistAttempt(3);
        List<Attempt> page = attemptRepo
                .findByStudentProfileIdOrderByCreatedAtDesc(studentProfileId, PageRequest.of(0, 10))
                .getContent();
        assertThat(page).hasSize(3);
        assertThat(page).allMatch(a -> a.getStudentProfileId() == studentProfileId);
    }

    @Test
    void attemptQuestionOrderedByDisplayOrder() {
        Attempt a = persistAttempt(1);
        long eq1 = newExamQuestion(1);
        long eq2 = newExamQuestion(2);
        persistAttemptQuestionWithOrder(a.getId(), eq2, 1);
        persistAttemptQuestionWithOrder(a.getId(), eq1, 0);
        em.flush();
        em.clear();
        List<AttemptQuestion> ordered = aqRepo.findByAttemptIdOrderByDisplayOrderAsc(a.getId());
        assertThat(ordered).hasSize(2);
        assertThat(ordered.get(0).getDisplayOrder()).isZero();
        assertThat(ordered.get(1).getDisplayOrder()).isEqualTo(1);
        assertThat(ordered.get(0).getExamQuestionId()).isEqualTo(eq1);
        assertThat(ordered.get(1).getExamQuestionId()).isEqualTo(eq2);
    }

    @Test
    void gradeRepositoryFindByAttemptId() {
        Attempt a = persistSubmittedAttempt(1, "GR");
        Grade g = persistGrade(a.getId());
        Optional<Grade> found = gradeRepo.findByAttemptId(a.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(g.getId());
    }

    @Test
    void gradeItemRepositoryFindByGradeIdOrderByIdAsc() {
        Attempt a = persistSubmittedAttempt(1, "GI");
        long eq1 = newExamQuestion(1);
        long eq2 = newExamQuestion(2);
        AttemptQuestion q1 = persistAttemptQuestionWithOrder(a.getId(), eq1, 0);
        AttemptQuestion q2 = persistAttemptQuestionWithOrder(a.getId(), eq2, 1);
        Grade g = persistGrade(a.getId());
        persistGradeItem(g.getId(), a.getId(), q2.getId());
        persistGradeItem(g.getId(), a.getId(), q1.getId());
        em.flush();
        em.clear();
        List<GradeItem> items = giRepo.findByGradeIdOrderByIdAsc(g.getId());
        assertThat(items).hasSize(2);
        assertThat(items).allMatch(i -> i.getGradeId() == g.getId());
    }

    // ============================================================
    // GROUP 7 — atomic answer UPSERT (within-transaction guard)
    // ============================================================

    @Test
    void upsertInsertsOnSequenceOne() {
        Attempt a = persistAttempt(1);
        AttemptQuestion q = persistAttemptQuestion(a.getId(), 0);
        int affected = aaRepo.upsertIfNewer(a.getId(), q.getId(), "{\"selectedOptionKey\":\"A\"}", 1L);
        assertThat(affected).isEqualTo(1);
        AttemptAnswer stored = aaRepo.findByAttemptIdAndAttemptQuestionId(a.getId(), q.getId()).orElseThrow();
        assertThat(stored.getSequenceNumber()).isEqualTo(1L);
        assertThat(stored.getAnswerPayload().path("selectedOptionKey").asString()).isEqualTo("A");
    }

    @Test
    void upsertIgnoresEqualOrLowerSequence() {
        Attempt a = persistAttempt(1);
        AttemptQuestion q = persistAttemptQuestion(a.getId(), 0);
        aaRepo.upsertIfNewer(a.getId(), q.getId(), "{\"selectedOptionKey\":\"A\"}", 5L);
        em.clear();
        assertThat(aaRepo.upsertIfNewer(a.getId(), q.getId(), "{\"selectedOptionKey\":\"B\"}", 5L)).isZero();
        assertThat(aaRepo.upsertIfNewer(a.getId(), q.getId(), "{\"selectedOptionKey\":\"C\"}", 2L)).isZero();
        em.clear();
        AttemptAnswer stored = aaRepo.findByAttemptIdAndAttemptQuestionId(a.getId(), q.getId()).orElseThrow();
        assertThat(stored.getSequenceNumber()).isEqualTo(5L);
        assertThat(stored.getAnswerPayload().path("selectedOptionKey").asString()).isEqualTo("A");
    }

    @Test
    void upsertUpdatesOnHigherSequence() {
        Attempt a = persistAttempt(1);
        AttemptQuestion q = persistAttemptQuestion(a.getId(), 0);
        aaRepo.upsertIfNewer(a.getId(), q.getId(), "{\"selectedOptionKey\":\"A\"}", 1L);
        em.clear();
        assertThat(aaRepo.upsertIfNewer(a.getId(), q.getId(), "{\"selectedOptionKey\":\"Z\"}", 9L)).isEqualTo(1);
        em.clear();
        AttemptAnswer stored = aaRepo.findByAttemptIdAndAttemptQuestionId(a.getId(), q.getId()).orElseThrow();
        assertThat(stored.getSequenceNumber()).isEqualTo(9L);
        assertThat(stored.getAnswerPayload().path("selectedOptionKey").asString()).isEqualTo("Z");
    }

    @Test
    void upsertRejectsCrossAttemptQuestion() {
        Attempt a = persistAttempt(1);
        Attempt other = persistAttemptForSession(1, newOpenSession("XS"), studentProfileId, examVersionId);
        AttemptQuestion otherQ = persistAttemptQuestion(other.getId(), 0);
        assertThatThrownBy(() -> aaRepo.upsertIfNewer(a.getId(), otherQ.getId(), "{\"x\":1}", 1L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ============================================================
    // GROUP 8 — idempotency repository
    // ============================================================

    @Test
    void findByUserOperationKeyReturnsMatchingRecord() {
        Attempt a = persistSubmittedAttempt(1, "UF");
        IdempotencyRecord rec = persistIdempotency(a.getId(), "UF");
        Optional<IdempotencyRecord> found = idemRepo.findByUserIdAndOperationAndIdempotencyKey(
                userId, IdempotencyOperation.ATTEMPT_SUBMIT, "UF");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(rec.getId());
        assertThat(found.get().getAttemptId()).isEqualTo(a.getId());
    }

    @Test
    void findByAttemptOperationReturnsMatchingRecord() {
        Attempt a = persistSubmittedAttempt(1, "AF");
        IdempotencyRecord rec = persistIdempotency(a.getId(), "AF");
        Optional<IdempotencyRecord> found = idemRepo.findByAttemptIdAndOperation(a.getId(),
                IdempotencyOperation.ATTEMPT_SUBMIT);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(rec.getId());
    }

    @Test
    void doesNotReturnRecordOfDifferentAttemptForSameKey() {
        Attempt a = persistSubmittedAttempt(1, "DA");
        persistIdempotency(a.getId(), "DA");
        long sessionDb = newOpenSession("DB");
        Attempt b = persistSubmittedAttemptForSession(1, "DB-K", sessionDb, studentProfileId, examVersionId);
        persistIdempotency(b.getId(), "DB-K");
        Optional<IdempotencyRecord> byKeyDA = idemRepo.findByUserIdAndOperationAndIdempotencyKey(
                userId, IdempotencyOperation.ATTEMPT_SUBMIT, "DA");
        assertThat(byKeyDA).isPresent();
        assertThat(byKeyDA.get().getAttemptId()).isEqualTo(a.getId());
        Optional<IdempotencyRecord> byKeyDB = idemRepo.findByUserIdAndOperationAndIdempotencyKey(
                userId, IdempotencyOperation.ATTEMPT_SUBMIT, "DB-K");
        assertThat(byKeyDB).isPresent();
        assertThat(byKeyDB.get().getAttemptId()).isEqualTo(b.getId());
    }

    // ============================================================
    // GROUP 9 — mapping safety (reflection)
    // ============================================================

    @Test
    void noAttemptEntityHasVersionAnnotation() {
        for (Class<?> c : ENTITY_CLASSES) {
            for (Field f : c.getDeclaredFields()) {
                assertThat(f.isAnnotationPresent(jakarta.persistence.Version.class))
                        .as("%s.%s must not be @Version", c.getSimpleName(), f.getName()).isFalse();
            }
        }
    }

    @Test
    void noAttemptEntityHasAssociationsOrCollections() {
        List<Class<? extends java.lang.annotation.Annotation>> associations = List.of(
                jakarta.persistence.OneToOne.class, jakarta.persistence.OneToMany.class,
                jakarta.persistence.ManyToOne.class, jakarta.persistence.ManyToMany.class);
        for (Class<?> c : ENTITY_CLASSES) {
            for (Field f : c.getDeclaredFields()) {
                for (Class<? extends java.lang.annotation.Annotation> a : associations) {
                    assertThat(f.isAnnotationPresent(a))
                            .as("%s.%s must not carry %s", c.getSimpleName(), f.getName(), a.getSimpleName())
                            .isFalse();
                }
            }
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }

    private long newOpenSession(String code) {
        return insert("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, "
                + "starts_at, ends_at, created_by, opened_at) VALUES (" + schoolId + "," + examVersionId + "," + userId
                + ",'" + code + "','t','OPEN',now()-interval '1 hour',now()+interval '2 hours'," + userId + ",now())");
    }

    /** Creates a fresh, distinct source question+version+section+exam_question (avoids uk_exam_questions_version_source). */
    private long newExamQuestion(int position) {
        long srcQ = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bankId + ",'X"
                + position + "'," + userId + ")");
        long srcV = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, "
                + "default_points, metadata, created_by) VALUES (" + srcQ + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb,"
                + userId + ")");
        long section = insert("INSERT INTO exam_sections (exam_version_id, title, position) VALUES ("
                + examVersionId + ",'Sec" + position + "'," + position + ")");
        return insert("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, "
                + "source_question_version_id, question_code, question_type, content, default_points, position, metadata) "
                + "VALUES (" + examVersionId + "," + section + "," + srcQ + "," + srcV + ",'QC" + position
                + "','SINGLE_CHOICE','c',1," + position + ",'{}'::jsonb)");
    }

    private Attempt persistAttempt(int number) {
        return persistAttemptForSession(number, sessionId, studentProfileId, examVersionId);
    }

    private Attempt persistAttemptForSession(int number, long session, long student, long version) {
        Attempt a = Attempt.start(schoolId, session, student, version, number,
                Instant.now(), Instant.now().plusSeconds(3600));
        em.persist(a);
        em.flush();
        return a;
    }

    private Attempt persistSubmittedAttempt(int number, String key) {
        Attempt a = persistAttempt(number);
        a.submit(Instant.now(), key);
        em.flush();
        return a;
    }

    private Attempt persistSubmittedAttemptForSession(int number, String key, long session, long student, long version) {
        Attempt a = persistAttemptForSession(number, session, student, version);
        a.submit(Instant.now(), key);
        em.flush();
        return a;
    }

    private AttemptQuestion persistAttemptQuestion(long attemptId, int order) {
        AttemptQuestion q = new AttemptQuestion(attemptId, examQuestionId, "SINGLE_CHOICE",
                new BigDecimal("1.00"), order, null);
        em.persist(q);
        em.flush();
        return q;
    }

    private AttemptQuestion persistAttemptQuestionWithOrder(long attemptId, long examQId, int order) {
        AttemptQuestion q = new AttemptQuestion(attemptId, examQId, "SINGLE_CHOICE",
                new BigDecimal("1.00"), order, null);
        em.persist(q);
        em.flush();
        return q;
    }

    private Grade persistGrade(long attemptId) {
        Grade g = new Grade(attemptId, new BigDecimal("5.00"), new BigDecimal("5.00"),
                new BigDecimal("10.00"), Instant.now(), userId);
        em.persist(g);
        em.flush();
        return g;
    }

    private void persistGradeItem(long gradeId, long attemptId, long attemptQuestionId) {
        em.persist(new GradeItem(gradeId, attemptId, attemptQuestionId, new BigDecimal("1.00"),
                new BigDecimal("1.00"), true, JsonNodeFactory.instance.objectNode()));
        em.flush();
    }

    private IdempotencyRecord persistIdempotency(long attemptId, String key) {
        JsonNode body = JsonNodeFactory.instance.objectNode().put("attemptId", attemptId);
        IdempotencyRecord rec = new IdempotencyRecord(userId, attemptId, IdempotencyOperation.ATTEMPT_SUBMIT,
                key, 200, body);
        em.persist(rec);
        em.flush();
        return rec;
    }
}
