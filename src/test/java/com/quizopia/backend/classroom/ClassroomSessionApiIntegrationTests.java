package com.quizopia.backend.classroom;

import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CLASS-M2 (issue #52): HTTP integration tests for classroom-scoped session
 * management — teacher {@code /api/classrooms/{id}/sessions[...]} (list /
 * assignable / assign / unassign) and learner
 * {@code /api/classrooms/joined/{id}[...]} (detail / assigned sessions).
 *
 * <p>Covers: owner-only scoping (no cross-teacher enumeration), additive
 * assign/unassign that never clobbers other classrooms' associations, the
 * replace-set endpoint still working, lifecycle/visibility gates, learner
 * membership gating with no DRAFT leakage, archived read-only behavior,
 * status/search/paging, and route resolution of {@code /joined/{id}} vs
 * {@code /{id}}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ PostgresTestContainerConfiguration.class, TestClockConfig.class })
@Transactional
class ClassroomSessionApiIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private jakarta.persistence.EntityManager em;

    private String tag;
    private long teacherA;
    private long teacherB;
    private long learner;
    private long outsider;
    private long examA;
    private long examB;

    @BeforeEach
    void setUp() {
        tag = java.util.UUID.randomUUID().toString().substring(0, 8);
        teacherA = teacher("tcha" + tag, "Teacher A " + tag);
        teacherB = teacher("tchb" + tag, "Teacher B " + tag);
        learner = verifiedStudent("lrnr" + tag, "Learner " + tag);
        outsider = verifiedStudent("outs" + tag, "Outsider " + tag);
        examA = publishedExam(teacherA, "EA" + tag, "Algebra " + tag);
        examB = publishedExam(teacherB, "EB" + tag, "Biology " + tag);
    }

    // ============================================================
    // Teacher: listing is owner-scoped
    // ============================================================

    @Test
    void teacherListsOnlyOwnClassroomSessions() throws Exception {
        long classA1 = createClassroom(teacherA, "A1" + tag);
        long classA2 = createClassroom(teacherA, "A2" + tag);
        long classB1 = createClassroom(teacherB, "B1" + tag);
        long s1 = session(teacherA, examA, "S1" + tag, "SCHEDULED");
        long s2 = session(teacherB, examB, "S2" + tag, "SCHEDULED");
        assign(teacherA, classA1, s1);
        assign(teacherB, classB1, s2);

        mockMvc.perform(get("/api/classrooms/" + classA1 + "/sessions").with(jwtFor(teacherA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].sessionId").value((int) s1))
                .andExpect(jsonPath("$.items[0].examTitle").value("Algebra " + tag))
                .andExpect(jsonPath("$.items[0].visibility").value("CLASS_RESTRICTED"))
                .andExpect(jsonPath("$.items[0].status").value("SCHEDULED"));
        mockMvc.perform(get("/api/classrooms/" + classA2 + "/sessions").with(jwtFor(teacherA)))
                .andExpect(jsonPath("$.totalElements").value(0));

        // Cross-teacher: B cannot list A's classroom sessions.
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/sessions").with(jwtFor(teacherB)))
                .andExpect(status().isForbidden());
        // Learner cannot use the teacher endpoint at all.
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/sessions").with(jwtFor(learner)))
                .andExpect(status().isForbidden());
    }

    @Test
    void teacherListFiltersStatusSearchAndPages() throws Exception {
        long classA1 = createClassroom(teacherA, "FL" + tag);
        // CLOSED/CANCELLED/OPEN sessions reach those states AFTER being
        // assigned (assignment is DRAFT/SCHEDULED-only) — fixture mirrors
        // the real lifecycle: create SCHEDULED → assign → SQL-transition.
        long s1 = session(teacherA, examA, "F1" + tag, "SCHEDULED", "Midterm Algebra One");
        long s2 = session(teacherA, examA, "F2" + tag, "SCHEDULED", "Midterm Algebra Two");
        long s3 = session(teacherA, examA, "F3" + tag, "SCHEDULED", "Quiz Physics");
        assign(teacherA, classA1, s1);
        assign(teacherA, classA1, s2);
        assign(teacherA, classA1, s3);
        transition(s1, "CLOSED");
        transition(s3, "OPEN");

        mockMvc.perform(get("/api/classrooms/" + classA1 + "/sessions").with(jwtFor(teacherA))
                        .param("status", "CLOSED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].sessionId").value((int) s1));
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/sessions").with(jwtFor(teacherA))
                        .param("search", "midterm algebra"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/sessions").with(jwtFor(teacherA))
                        .param("search", "%" + tag))
                .andExpect(jsonPath("$.totalElements").value(0)); // wildcards escaped
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/sessions").with(jwtFor(teacherA))
                        .param("size", "2").param("page", "0"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(2));
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/sessions").with(jwtFor(teacherA))
                        .param("status", "BOGUS"))
                .andExpect(status().isBadRequest());
        // Over-paged request clamps to the last (only) page — not an error.
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/sessions").with(jwtFor(teacherA))
                        .param("page", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    // ============================================================
    // Assignable picker
    // ============================================================

    @Test
    void assignablePickerExcludesDuplicatesPublicClosedAndForeignSessions() throws Exception {
        long classA1 = createClassroom(teacherA, "PK" + tag);
        long assigned = session(teacherA, examA, "PA" + tag, "SCHEDULED");
        long draftOk = session(teacherA, examA, "PD" + tag, "DRAFT");
        long publicS = session(teacherA, examA, "PP" + tag, "SCHEDULED", null, "PUBLIC");
        long closedS = session(teacherA, examA, "PC" + tag, "CLOSED");
        long foreign = session(teacherB, examB, "PF" + tag, "SCHEDULED");
        assign(teacherA, classA1, assigned);

        MvcResult result = mockMvc.perform(get("/api/classrooms/" + classA1 + "/sessions/assignable")
                        .with(jwtFor(teacherA)).param("size", "50"))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
        java.util.List<Long> ids = new java.util.ArrayList<>();
        items.forEach(i -> ids.add(i.get("sessionId").asLong()));
        assertThat(ids).containsExactly(draftOk);
        assertThat(ids).doesNotContain(assigned, publicS, closedS, foreign);

        // Non-owner classroom: picker denied.
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/sessions/assignable")
                        .with(jwtFor(teacherB)))
                .andExpect(status().isForbidden());
    }

    // ============================================================
    // Assign / unassign: additive, idempotent, gated
    // ============================================================

    @Test
    void assignIsAdditiveIdempotentAndNeverClobbersOtherClasses() throws Exception {
        long classA1 = createClassroom(teacherA, "AD1" + tag);
        long classA2 = createClassroom(teacherA, "AD2" + tag);
        long s1 = session(teacherA, examA, "AS" + tag, "SCHEDULED");

        // Idempotent double assign of the same pair.
        assign(teacherA, classA1, s1);
        mockMvc.perform(post("/api/classrooms/" + classA1 + "/sessions/" + s1).with(jwtFor(teacherA)))
                .andExpect(status().isNoContent());
        assertThat(associationCount(s1)).isEqualTo(1);

        // Additive assign to a SECOND classroom keeps both.
        assign(teacherA, classA2, s1);
        assertThat(associationCount(s1)).isEqualTo(2);

        // Unassigning from ONE classroom preserves the other.
        mockMvc.perform(delete("/api/classrooms/" + classA1 + "/sessions/" + s1).with(jwtFor(teacherA)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/sessions").with(jwtFor(teacherA)))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/classrooms/" + classA2 + "/sessions").with(jwtFor(teacherA)))
                .andExpect(jsonPath("$.totalElements").value(1));

        // Repeat unassign of an absent association: still 204.
        mockMvc.perform(delete("/api/classrooms/" + classA1 + "/sessions/" + s1).with(jwtFor(teacherA)))
                .andExpect(status().isNoContent());
    }

    @Test
    void assignUnassignValidationMatrix() throws Exception {
        long classA1 = createClassroom(teacherA, "VM" + tag);
        long classB1 = createClassroom(teacherB, "VMB" + tag);
        long openS = session(teacherA, examA, "VO" + tag, "OPEN");
        long closedS = session(teacherA, examA, "VC" + tag, "CLOSED");
        long publicS = session(teacherA, examA, "VP" + tag, "SCHEDULED", null, "PUBLIC");
        long foreignS = session(teacherB, examB, "VF" + tag, "SCHEDULED");

        // Lifecycle gate: OPEN/CLOSED sessions cannot be (un)assigned.
        mockMvc.perform(post("/api/classrooms/" + classA1 + "/sessions/" + openS).with(jwtFor(teacherA)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/classrooms/" + classA1 + "/sessions/" + closedS).with(jwtFor(teacherA)))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/classrooms/" + classA1 + "/sessions/" + openS).with(jwtFor(teacherA)))
                .andExpect(status().isConflict());
        // Visibility gate: PUBLIC sessions are not class-assignable here.
        mockMvc.perform(post("/api/classrooms/" + classA1 + "/sessions/" + publicS).with(jwtFor(teacherA)))
                .andExpect(status().isForbidden());
        // Ownership gates: foreign session on own class; own session on foreign class.
        mockMvc.perform(post("/api/classrooms/" + classA1 + "/sessions/" + foreignS).with(jwtFor(teacherA)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/classrooms/" + classB1 + "/sessions/" + openS).with(jwtFor(teacherA)))
                .andExpect(status().isForbidden());
        // Unknown session: 404.
        mockMvc.perform(post("/api/classrooms/" + classA1 + "/sessions/999999999").with(jwtFor(teacherA)))
                .andExpect(status().isNotFound());
        // Archived classroom: assignment AND unassignment denied (read-only
        // for members; matches the assign/replace-set policy — review M2-F1).
        // (em.clear: production requests load fresh state — the shared test
        // persistence context must not keep the pre-archive classroom.)
        long preArchived = session(teacherA, examA, "VSA" + tag, "SCHEDULED");
        jdbc.update("INSERT INTO exam_session_classes (exam_session_id, classroom_id) VALUES ("
                + preArchived + "," + classA1 + ")");
        jdbc.update("UPDATE classrooms SET status='ARCHIVED' WHERE id=" + classA1);
        em.clear();
        long sched = session(teacherA, examA, "VS" + tag, "SCHEDULED");
        mockMvc.perform(post("/api/classrooms/" + classA1 + "/sessions/" + sched).with(jwtFor(teacherA)))
                .andExpect(status().isForbidden());
        // A picker for archived classes must not advertise actions that always fail.
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/sessions/assignable")
                        .with(jwtFor(teacherA)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/classrooms/" + classA1 + "/sessions/" + preArchived)
                        .with(jwtFor(teacherA)))
                .andExpect(status().isForbidden());
        // The archived classroom's existing association is untouched.
        assertThat(associationCount(preArchived)).isEqualTo(1);

        // Unauthenticated.
        mockMvc.perform(post("/api/classrooms/" + classA1 + "/sessions/" + sched))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unassignRevokesEligibilityButKeepsAttemptsAndReplaceSetStillWorks() throws Exception {
        long classA1 = createClassroom(teacherA, "US" + tag);
        long classA2 = createClassroom(teacherA, "US2" + tag);
        long s1 = session(teacherA, examA, "UU" + tag, "SCHEDULED");
        assign(teacherA, classA1, s1);
        assign(teacherA, classA2, s1);
        jdbc.update("INSERT INTO classroom_members (classroom_id, user_id) VALUES ("
                + classA1 + "," + learner + ")");
        jdbc.update("INSERT INTO attempts (exam_session_id, user_id, attempt_number, status, "
                + "started_at, deadline_at, submitted_at, submission_idempotency_key) VALUES ("
                + s1 + "," + learner + ",1,'SUBMITTED',now(),now()+interval '1 hour',now(),'"
                + tag + "')");

        // Unassign from the learner's class: attempt history survives, the
        // learner's class view loses the session…
        mockMvc.perform(delete("/api/classrooms/" + classA1 + "/sessions/" + s1).with(jwtFor(teacherA)))
                .andExpect(status().isNoContent());
        Integer attempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempts WHERE exam_session_id=" + s1
                        + " AND user_id=" + learner, Integer.class);
        assertThat(attempts).isEqualTo(1);
        mockMvc.perform(get("/api/classrooms/joined/" + classA1 + "/sessions").with(jwtFor(learner)))
                .andExpect(jsonPath("$.totalElements").value(0));

        // …and the existing replace-set endpoint still works (replaces the set).
        mockMvc.perform(put("/api/exams/" + examA + "/sessions/" + s1 + "/classes")
                        .with(jwtFor(teacherA)).contentType(MediaType.APPLICATION_JSON)
                        .content("[" + classA2 + "]"))
                .andExpect(status().isOk());
        assertThat(associationCount(s1)).isEqualTo(1);
        mockMvc.perform(get("/api/classrooms/" + classA2 + "/sessions").with(jwtFor(teacherA)))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void dueScheduledClassAssignmentsAreGuardedAndClassReadsRefreshLifecycle() throws Exception {
        long classA1 = createClassroom(teacherA, "TL" + tag);
        long assigned = session(teacherA, examA, "TLS" + tag, "SCHEDULED");
        long unassigned = session(teacherA, examA, "TLU" + tag, "SCHEDULED");
        assign(teacherA, classA1, assigned);
        // The scheduled start passes without any prior exam/available read.
        // A stale persisted SCHEDULED status must not permit mid-flight changes.
        jdbc.update("UPDATE exam_sessions SET starts_at=now() - interval '1 minute' "
                + "WHERE id IN (" + assigned + "," + unassigned + ")");
        // The prior assign() populated the test transaction's first-level
        // persistence context. JDBC bypasses it; clear before another MockMvc
        // request so findByIdForUpdate reads the UPDATED starts_at from DB,
        // as it would in a fresh production HTTP transaction.
        em.clear();
        mockMvc.perform(delete("/api/classrooms/" + classA1 + "/sessions/" + assigned)
                        .with(jwtFor(teacherA)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/classrooms/" + classA1 + "/sessions/" + unassigned)
                        .with(jwtFor(teacherA)))
                .andExpect(status().isConflict());
        assertThat(associationCount(assigned)).isEqualTo(1);
        assertThat(associationCount(unassigned)).isZero();

        // The class endpoints must use the canonical lazy lifecycle transition,
        // including status filtering and the learner's safe projection.
        jdbc.update("INSERT INTO classroom_members (classroom_id, user_id) VALUES ("
                + classA1 + "," + learner + ")");
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/sessions")
                        .with(jwtFor(teacherA)).param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].sessionId").value((int) assigned));
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/sessions/assignable")
                        .with(jwtFor(teacherA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/classrooms/joined/" + classA1 + "/sessions")
                        .with(jwtFor(learner)).param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].status").value("OPEN"));
    }

    // ============================================================
    // Learner: joined detail + assigned sessions
    // ============================================================

    @Test
    void learnerDetailAndSessionsAreMembershipScopedWithoutDraftLeakage() throws Exception {
        long classA1 = createClassroom(teacherA, "LD" + tag);
        long classB1 = createClassroom(teacherB, "LDB" + tag);
        long s1 = session(teacherA, examA, "LS1" + tag, "SCHEDULED", "Algebra Sitting");
        long s2 = session(teacherA, examA, "LS2" + tag, "SCHEDULED", "Algebra Past");
        long draftS = session(teacherA, examA, "LS3" + tag, "DRAFT", "Algebra Secret");
        long cancelledS = session(teacherA, examA, "LS4" + tag, "SCHEDULED", "Algebra Cancelled");
        long sB = session(teacherB, examB, "LS5" + tag, "SCHEDULED");
        assign(teacherA, classA1, s1);
        assign(teacherA, classA1, s2);
        assign(teacherA, classA1, draftS); // DRAFT: must never reach the learner
        assign(teacherA, classA1, cancelledS);
        assign(teacherB, classB1, sB);
        // Post-assignment lifecycle transitions (real order of operations).
        transition(s2, "CLOSED");
        transition(cancelledS, "CANCELLED");
        transition(sB, "OPEN");
        jdbc.update("INSERT INTO classroom_members (classroom_id, user_id) VALUES ("
                + classA1 + "," + learner + "),(" + classB1 + "," + learner + ")");

        mockMvc.perform(get("/api/classrooms/joined/" + classA1).with(jwtFor(learner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classroomId").value((int) classA1))
                .andExpect(jsonPath("$.name").value("Class LD" + tag))
                .andExpect(jsonPath("$.teacherDisplayName").value("Teacher A " + tag))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.joinedAt").isNotEmpty())
                .andExpect(jsonPath("$.assignedSessionCount").value(3)); // DRAFT excluded

        MvcResult result = mockMvc.perform(get("/api/classrooms/joined/" + classA1 + "/sessions")
                        .with(jwtFor(learner)).param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3)).andReturn();
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
        java.util.List<Long> ids = new java.util.ArrayList<>();
        items.forEach(i -> ids.add(i.get("sessionId").asLong()));
        assertThat(ids).containsExactlyInAnyOrder(s1, s2, cancelledS); // no DRAFT
        // Learner-safe projection: no participant counts, no visibility/maxAttempts fields.
        assertThat(items.get(0).has("participantCount")).isFalse();
        assertThat(items.get(0).has("maxAttempts")).isFalse();

        // Other class list is independent.
        mockMvc.perform(get("/api/classrooms/joined/" + classB1 + "/sessions").with(jwtFor(learner)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].sessionId").value((int) sB));

        // Status filter (learner allowlist) + invalid filter.
        mockMvc.perform(get("/api/classrooms/joined/" + classA1 + "/sessions").with(jwtFor(learner))
                        .param("status", "CLOSED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].sessionId").value((int) s2));
        mockMvc.perform(get("/api/classrooms/joined/" + classA1 + "/sessions").with(jwtFor(learner))
                        .param("status", "DRAFT"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sharedSessionAppearsOnceWithinEachMemberClass() throws Exception {
        long classA1 = createClassroom(teacherA, "SH1" + tag);
        long classA2 = createClassroom(teacherA, "SH2" + tag);
        long s1 = session(teacherA, examA, "SS" + tag, "SCHEDULED");
        assign(teacherA, classA1, s1);
        assign(teacherA, classA2, s1);
        jdbc.update("INSERT INTO classroom_members (classroom_id, user_id) VALUES ("
                + classA1 + "," + learner + "),(" + classA2 + "," + learner + ")");

        mockMvc.perform(get("/api/classrooms/joined/" + classA1 + "/sessions").with(jwtFor(learner)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].sessionId").value((int) s1));
        mockMvc.perform(get("/api/classrooms/joined/" + classA2 + "/sessions").with(jwtFor(learner)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].sessionId").value((int) s1));
        // One association per (session,classroom): no duplicated cards possible.
        assertThat(associationCount(s1)).isEqualTo(2);
    }

    @Test
    void learnerAuthorizationOutsiderLeaveAndArchived() throws Exception {
        // Archive EARLY (before any service call caches the classroom in the
        // shared test persistence context) — members keep read-only access.
        long classA1 = createClassroom(teacherA, "AZ" + tag);
        long s1 = session(teacherA, examA, "AZS" + tag, "SCHEDULED");
        assign(teacherA, classA1, s1);
        jdbc.update("INSERT INTO classroom_members (classroom_id, user_id) VALUES ("
                + classA1 + "," + learner + ")");
        jdbc.update("UPDATE classrooms SET status='ARCHIVED' WHERE id=" + classA1);
        em.clear(); // fresh classroom state, as a production request would see
        // Archived: current members keep read-only historical access.
        mockMvc.perform(get("/api/classrooms/joined/" + classA1).with(jwtFor(learner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        mockMvc.perform(get("/api/classrooms/joined/" + classA1 + "/sessions").with(jwtFor(learner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // Leaving (allowed on archived classes) revokes class-page access.
        mockMvc.perform(post("/api/classrooms/" + classA1 + "/leave").with(jwtFor(learner)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/classrooms/joined/" + classA1).with(jwtFor(learner)))
                .andExpect(status().isNotFound());

        // Outsider and even the OWNER (not a member) get 404 — no oracle.
        mockMvc.perform(get("/api/classrooms/joined/" + classA1).with(jwtFor(outsider)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/classrooms/joined/" + classA1).with(jwtFor(teacherA)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/classrooms/joined/" + classA1 + "/sessions").with(jwtFor(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    void teacherMemberRemovalRevokesClassPageAccessAndKeepsAttempts() throws Exception {
        long classA1 = createClassroom(teacherA, "RM" + tag);
        long s1 = session(teacherA, examA, "RMS" + tag, "SCHEDULED");
        assign(teacherA, classA1, s1);
        jdbc.update("INSERT INTO classroom_members (classroom_id, user_id) VALUES ("
                + classA1 + "," + learner + ")");
        jdbc.update("INSERT INTO attempts (exam_session_id, user_id, attempt_number, status, "
                + "started_at, deadline_at, submitted_at, submission_idempotency_key) VALUES ("
                + s1 + "," + learner + ",1,'SUBMITTED',now(),now()+interval '1 hour',now(),'"
                + tag + "rm')");

        // Teacher-initiated removal (M1 endpoint) revokes the class pages…
        mockMvc.perform(delete("/api/classrooms/" + classA1 + "/members/" + learner)
                        .with(jwtFor(teacherA)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/classrooms/joined/" + classA1).with(jwtFor(learner)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/classrooms/joined/" + classA1 + "/sessions").with(jwtFor(learner)))
                .andExpect(status().isNotFound());
        // …while the attempt history row survives untouched.
        Integer attempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempts WHERE exam_session_id=" + s1
                        + " AND user_id=" + learner, Integer.class);
        assertThat(attempts).isEqualTo(1);
    }

    @Test
    void blockedMemberSeesClassCardButCannotStart() throws Exception {
        long classA1 = createClassroom(teacherA, "BL" + tag);
        long s1 = session(teacherA, examA, "BLS" + tag, "OPEN");
        assign(teacherA, classA1, session(teacherA, examA, "BLP" + tag, "SCHEDULED"));
        jdbc.update("INSERT INTO classroom_members (classroom_id, user_id) VALUES ("
                + classA1 + "," + learner + ")");
        jdbc.update("INSERT INTO exam_session_classes (exam_session_id, classroom_id) VALUES ("
                + s1 + "," + classA1 + ")");
        // Blocking requires a known participant (attempt row — anti-enumeration).
        jdbc.update("INSERT INTO attempts (exam_session_id, user_id, attempt_number, status, "
                + "started_at, deadline_at) VALUES (" + s1 + "," + learner
                + ",1,'IN_PROGRESS',now(),now()+interval '1 hour')");

        // BLOCKED participant (P0-R7/R8): the class page still lists the
        // session (metadata only), but start rights are denied server-side.
        mockMvc.perform(post("/api/exam-sessions/" + s1 + "/participants/by-user/" + learner + "/block")
                        .with(jwtFor(teacherA)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/classrooms/joined/" + classA1 + "/sessions").with(jwtFor(learner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/exam-sessions/" + s1 + "/eligibility").with(jwtFor(learner)))
                .andExpect(status().isForbidden());
    }

    @Test
    void pickerSearchPagingAndLearnerPagingWork() throws Exception {
        long classA1 = createClassroom(teacherA, "PG" + tag);
        long s1 = session(teacherA, examA, "PG1" + tag, "SCHEDULED", "Alpha Searchable One");
        long s2 = session(teacherA, examA, "PG2" + tag, "SCHEDULED", "Alpha Searchable Two");
        long s3 = session(teacherA, examA, "PG3" + tag, "SCHEDULED", "Beta Other");
        jdbc.update("INSERT INTO classroom_members (classroom_id, user_id) VALUES ("
                + classA1 + "," + learner + ")");

        // Picker: search narrows; paging splits.
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/sessions/assignable")
                        .with(jwtFor(teacherA)).param("search", "alpha searchable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/sessions/assignable")
                        .with(jwtFor(teacherA)).param("size", "2").param("page", "0"))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(2));
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/sessions/assignable")
                        .with(jwtFor(teacherA)).param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.items.length()").value(1));

        // Defense-in-depth (review M2-QA5b): archiving the exam removes its
        // sessions from the picker (e.status='PUBLISHED' filter).
        jdbc.update("UPDATE exams SET status='ARCHIVED' WHERE id=" + examA);
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/sessions/assignable")
                        .with(jwtFor(teacherA)).param("size", "50"))
                .andExpect(jsonPath("$.totalElements").value(0));
        // Learner list paging across three assigned sessions.
        assign(teacherA, classA1, s1);
        assign(teacherA, classA1, s2);
        assign(teacherA, classA1, s3);
        mockMvc.perform(get("/api/classrooms/joined/" + classA1 + "/sessions")
                        .with(jwtFor(learner)).param("size", "2").param("page", "0"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(2));
        mockMvc.perform(get("/api/classrooms/joined/" + classA1 + "/sessions")
                        .with(jwtFor(learner)).param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void unknownClassroomAndSessionReturn404OnNewEndpoints() throws Exception {
        // Unknown classroom (teacher routes) → 404 via requireOwnedClassroom.
        mockMvc.perform(get("/api/classrooms/999999999/sessions").with(jwtFor(teacherA)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/classrooms/999999999/sessions/assignable").with(jwtFor(teacherA)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/classrooms/999999999/sessions/1").with(jwtFor(teacherA)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/classrooms/999999999/sessions/1").with(jwtFor(teacherA)))
                .andExpect(status().isNotFound());
        // Unknown learner route → 404 via requireJoinedClassroom.
        mockMvc.perform(get("/api/classrooms/joined/999999999").with(jwtFor(learner)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/classrooms/joined/999999999/sessions").with(jwtFor(learner)))
                .andExpect(status().isNotFound());
    }

    // ============================================================
    // Route resolution: /joined/{id} vs /{id}
    // ============================================================

    @Test
    void joinedRoutesAreNotCapturedByGenericIdMappings() throws Exception {
        long classA1 = createClassroom(teacherA, "RR" + tag);
        // The OWNER gets 200 from the generic detail route but 404 from the
        // member-scoped /joined route (owner is not a member) — proves the
        // two mappings resolve to different handlers.
        mockMvc.perform(get("/api/classrooms/" + classA1).with(jwtFor(teacherA)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/classrooms/joined/" + classA1).with(jwtFor(teacherA)))
                .andExpect(status().isNotFound());
        // /joined/{id}/sessions (member-scoped) vs /{id}/sessions (owner-scoped):
        // a member LEARNER gets 200/403 respectively.
        jdbc.update("INSERT INTO classroom_members (classroom_id, user_id) VALUES ("
                + classA1 + "," + learner + ")");
        mockMvc.perform(get("/api/classrooms/joined/" + classA1 + "/sessions").with(jwtFor(learner)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/sessions").with(jwtFor(learner)))
                .andExpect(status().isForbidden());
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static JwtRequestPostProcessor jwtFor(long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private long insertUser(String username, String displayName) {
        return jdbc.queryForObject("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('" + username + "','" + username + "@t.com','h','" + displayName + "') RETURNING id",
                Long.class);
    }

    private long teacher(String username, String displayName) {
        long id = insertUser(username, displayName);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT " + id
                + ", id FROM roles WHERE code='TEACHER'");
        return id;
    }

    private long verifiedStudent(String username, String displayName) {
        long id = insertUser(username, displayName);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT " + id
                + ", id FROM roles WHERE code='STUDENT'");
        jdbc.update("UPDATE users SET email_verified_at=now() WHERE id=" + id);
        return id;
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }

    private long publishedExam(long ownerId, String code, String title) {
        long gl = insert("INSERT INTO grade_levels (code, name) VALUES ('" + code + "','G')");
        long subj = insert("INSERT INTO subjects (grade_level_id, code, name) VALUES ("
                + gl + ",'" + code + "S','Subj')");
        return insert("INSERT INTO exams (created_by_user_id, subject_id, code, title, status, "
                + "published_at, total_points) VALUES (" + ownerId + "," + subj + ",'" + code
                + "','" + title + "','PUBLISHED',now(),1)");
    }

    private long createClassroom(long ownerId, String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/classrooms").with(jwtFor(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Class " + code + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long session(long ownerId, long examId, String code, String status) {
        return session(ownerId, examId, code, status, null, "CLASS_RESTRICTED");
    }

    private long session(long ownerId, long examId, String code, String status, String title) {
        return session(ownerId, examId, code, status, title, "CLASS_RESTRICTED");
    }

    private long session(long ownerId, long examId, String code, String status, String title,
            String visibility) {
        // chk_exam_sessions_state_timestamps: OPEN needs opened_at; CLOSED
        // needs opened_at + closed_at; DRAFT/SCHEDULED/CANCELLED need none.
        String openedAt = switch (status) {
            case "OPEN", "CLOSED" -> "now() - interval '2 hours'";
            default -> "NULL";
        };
        String closedAt = "CLOSED".equals(status) ? "now() - interval '1 hours'" : "NULL";
        // SCHEDULED fixtures must be genuinely future-dated; otherwise the
        // lifecycle-aware class read correctly moves them to OPEN immediately.
        String startsAt = "SCHEDULED".equals(status)
                ? "now() + interval '1 day'" : "now() - interval '2 hours'";
        return insert("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, "
                + "max_attempts, created_by, opened_at, closed_at, visibility, duration_minutes) VALUES ("
                + examId + ",'" + code + "','" + (title != null ? title : "S " + code) + "','" + status
                + "', " + startsAt + ", now() + interval '2 days', 1," + ownerId
                + ", " + openedAt + ", " + closedAt + ", '" + visibility + "', 30)");
    }

    private void assign(long teacherId, long classroomId, long sessionId) throws Exception {
        mockMvc.perform(post("/api/classrooms/" + classroomId + "/sessions/" + sessionId)
                        .with(jwtFor(teacherId)))
                .andExpect(status().isNoContent());
    }

    /** SQL lifecycle transition of an already-assigned session (with the
     *  state/timestamp invariants of chk_exam_sessions_state_timestamps). */
    private void transition(long sessionId, String status) {
        switch (status) {
            case "OPEN" -> jdbc.update("UPDATE exam_sessions SET status='OPEN', "
                    + "opened_at=now() WHERE id=" + sessionId);
            case "CLOSED" -> jdbc.update("UPDATE exam_sessions SET status='CLOSED', "
                    + "opened_at=COALESCE(opened_at, now()), closed_at=now() WHERE id=" + sessionId);
            case "CANCELLED" -> jdbc.update("UPDATE exam_sessions SET status='CANCELLED' "
                    + "WHERE id=" + sessionId);
            default -> throw new IllegalArgumentException("unsupported transition: " + status);
        }
    }

    private int associationCount(long sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_session_classes WHERE exam_session_id=" + sessionId,
                Integer.class);
        return count == null ? 0 : count;
    }
}
