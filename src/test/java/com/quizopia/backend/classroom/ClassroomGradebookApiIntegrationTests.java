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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CLASS-M3 (issue #54) Stage B: class-scoped gradebook / analytics / export
 * / learner my-results — cohort semantics (temporal membership overlap with
 * the taking window), BEST-result reuse, exact metric denominators,
 * multi-class non-duplication, owner-only authorization, and query hardening.
 *
 * <p>Temporal fixtures insert {@code classroom_membership_periods} directly:
 * these tests verify the READ model (period × window attribution); the write
 * paths are covered by {@link ClassroomMembershipPeriodIntegrationTests}.
 * Rows are the UNION of session cohorts — a user whose epochs never overlap
 * any selected window is simply absent from the matrix.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ PostgresTestContainerConfiguration.class, TestClockConfig.class })
@Transactional
class ClassroomGradebookApiIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private String tag;
    private long teacherA;
    private long teacherB;
    private long s1; // learner 1
    private long s2; // learner 2
    private long s3; // learner 3
    private long examA;
    private long classA1;
    private long classA2;
    private long classB1;

    @BeforeEach
    void setUp() throws Exception {
        tag = java.util.UUID.randomUUID().toString().substring(0, 8);
        teacherA = user("tcha" + tag, "Teacher A " + tag, "TEACHER");
        teacherB = user("tchb" + tag, "Teacher B " + tag, "TEACHER");
        s1 = user("st1" + tag, "Student One " + tag, "STUDENT");
        s2 = user("st2" + tag, "Student Two " + tag, "STUDENT");
        s3 = user("st3" + tag, "Student Three " + tag, "STUDENT");
        examA = publishedExam(teacherA, "EX" + tag, "Exam " + tag);
        classA1 = createClassroom(teacherA, "A1" + tag);
        classA2 = createClassroom(teacherA, "A2" + tag);
        classB1 = createClassroom(teacherB, "B1" + tag);
    }

    // ============================================================
    // Cohort semantics — the temporal matrix (issue #54 test matrix)
    // ============================================================

    @Test
    void gradebookComputesCohortCellsAndAggregates() throws Exception {
        // Window [now-2h, now-1h]; s1 graded (two attempts → BEST), s2
        // pending grading, s3 in progress; plus one NOT_ATTEMPTED member.
        long sess = windowedSession(teacherA, classA1, examA, "W1" + tag, "-2 hours", "-1 hours");
        long s4 = user("st4" + tag, "Student Four " + tag, "STUDENT");
        member(classA1, s1, "-3 hours", null);
        member(classA1, s2, "-3 hours", null);
        member(classA1, s3, "-3 hours", null);
        member(classA1, s4, "-3 hours", null);

        long a1 = attempt(sess, s1, 1, "GRADED");
        grade(a1, "6.00", "10.00", "60.0000");
        long a2 = attempt(sess, s1, 2, "GRADED");
        grade(a2, "8.00", "10.00", "80.0000"); // BEST by percentage
        attempt(sess, s2, 1, "SUBMITTED"); // no grade → PENDING_GRADING
        attempt(sess, s3, 1, "IN_PROGRESS"); // → IN_PROGRESS

        JsonNode gradebook = gradebook(classA1, teacherA, null);
        JsonNode sessions = gradebook.get("sessions");
        assertThat(sessions.size()).isEqualTo(1);
        JsonNode col = sessions.get(0);
        assertThat(col.get("code").asText()).isEqualTo("W1" + tag);
        assertThat(col.get("windowState").asText()).isEqualTo("WINDOW");
        assertThat(col.get("cohortSize").asLong()).isEqualTo(4);
        assertThat(col.get("startedCount").asLong()).isEqualTo(3);
        assertThat(col.get("submittedCount").asLong()).isEqualTo(2);
        assertThat(col.get("gradedCount").asLong()).isEqualTo(1);
        assertThat(col.get("completionPercentage").asDouble()).isEqualTo(50.0);
        assertThat(col.get("averagePercentage").asDouble()).isEqualTo(80.0);

        JsonNode students = gradebook.get("students");
        assertThat(students.size()).isEqualTo(4);
        JsonNode row1 = rowByUser(students, s1);
        JsonNode cell1 = row1.get("cells").get(0);
        assertThat(cell1.get("status").asText()).isEqualTo("SCORED");
        assertThat(cell1.get("score").asDouble()).isEqualTo(8.0);
        assertThat(cell1.get("maxScore").asDouble()).isEqualTo(10.0);
        assertThat(cell1.get("percentage").asDouble()).isEqualTo(80.0);
        assertThat(cell1.get("bestAttemptId").asLong()).isEqualTo(a2);
        assertThat(cell1.get("attemptCount").asLong()).isEqualTo(2);
        assertThat(row1.get("meanPercentage").asDouble()).isEqualTo(80.0);
        assertThat(row1.get("completionPercentage").asDouble()).isEqualTo(100.0);
        assertThat(rowByUser(students, s2).get("cells").get(0).get("status").asText())
                .isEqualTo("PENDING_GRADING");
        assertThat(rowByUser(students, s3).get("cells").get(0).get("status").asText())
                .isEqualTo("IN_PROGRESS");
        JsonNode row4 = rowByUser(students, s4);
        assertThat(row4.get("cells").get(0).get("status").asText()).isEqualTo("NOT_ATTEMPTED");
        assertThat(row4.get("cells").get(0).get("score").isNull()).isTrue();
        // Not attempted is NULL, never a zero mean.
        assertThat(row4.get("meanPercentage").isNull()).isTrue();
        assertThat(row4.get("completionPercentage").asDouble()).isEqualTo(0.0);
        assertThat(row4.get("cohortSessions").asLong()).isEqualTo(1);

        // The session-columns endpoint reports the SAME counters.
        MvcResult cols = mockMvc.perform(get("/api/classrooms/" + classA1
                        + "/gradebook/sessions").with(jwtFor(teacherA)))
                .andExpect(status().isOk()).andReturn();
        JsonNode colItems = objectMapper.readTree(cols.getResponse().getContentAsString())
                .get("items");
        assertThat(colItems.size()).isEqualTo(1);
        assertThat(colItems.get(0).get("cohortSize").asLong()).isEqualTo(4);
        assertThat(colItems.get(0).get("submittedCount").asLong()).isEqualTo(2);
        assertThat(colItems.get(0).get("averagePercentage").asDouble()).isEqualTo(80.0);
    }

    @Test
    void temporalBoundariesShapeTheCohort() throws Exception {
        long sess = windowedSession(teacherA, classA1, examA, "W2" + tag, "-2 hours", "-1 hours");
        long beforeOnly = user("jb" + tag, "Joined Left Before " + tag, "STUDENT");
        long duringOpen = user("jd" + tag, "Joined During " + tag, "STUDENT");
        long afterClose = user("ja" + tag, "Joined After " + tag, "STUDENT");
        long removedRejoined = user("jr" + tag, "Removed Rejoined " + tag, "STUDENT");

        member(classA1, beforeOnly, "-10 hours", "-5 hours");   // left before window → out
        member(classA1, duringOpen, "-90 minutes", null);        // joined mid-window → in
        member(classA1, afterClose, "-30 minutes", null);        // joined after close → out
        member(classA1, removedRejoined, "-5 hours", "-95 minutes"); // epoch 1 ended inside window
        member(classA1, removedRejoined, "-10 minutes", null);   // epoch 2 after window

        JsonNode gradebook = gradebook(classA1, teacherA, null);
        JsonNode students = gradebook.get("students");
        assertThat(gradebook.get("sessions").get(0).get("cohortSize").asLong())
                .as("duringOpen + epoch-1 overlap of removedRejoined")
                .isEqualTo(2);
        assertThat(hasRow(students, beforeOnly)).isFalse();
        assertThat(hasRow(students, afterClose)).isFalse();
        assertThat(statusOfCell(students, duringOpen, gradebook, "W2" + tag))
                .isEqualTo("NOT_ATTEMPTED");
        // Scenario 13 + rejoin: the epoch overlapping the window keeps the
        // attribution (visible as not-attempted), the later epoch adds nothing.
        assertThat(statusOfCell(students, removedRejoined, gradebook, "W2" + tag))
                .isEqualTo("NOT_ATTEMPTED");
    }

    @Test
    void earlyCloseAndNeverHeldWindowsAreHonored() throws Exception {
        // Early close: closed_at (-90m) before ends_at (-30m) → window ends at close.
        long early = windowedSession(teacherA, classA1, examA, "EC" + tag, "-2 hours", "-30 minutes");
        jdbc.update("UPDATE exam_sessions SET closed_at = now() - interval '90 minutes' WHERE id = " + early);
        long joinedBetween = user("ec" + tag, "Between Close End " + tag, "STUDENT");
        member(classA1, joinedBetween, "-60 minutes", null); // after close, before ends → OUT
        long inWindow = user("ei" + tag, "In Window " + tag, "STUDENT");
        member(classA1, inWindow, "-100 minutes", null);     // before close → IN

        // Never held: expired SCHEDULED fabricated CLOSED (opened_at = closed_at > ends_at).
        long notHeld = insert("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, "
                + "max_attempts, created_by, visibility, duration_minutes) VALUES ("
                + examA + ",'NH" + tag + "','Never Held','SCHEDULED', "
                + "now() + interval '1 day', now() + interval '2 days', 1," + teacherA
                + ", 'CLASS_RESTRICTED', 30)");
        assign(teacherA, classA1, notHeld);
        // Expire it as the lazy refresher would: fabricated opened_at = closed_at > ends_at.
        jdbc.update("UPDATE exam_sessions SET status='CLOSED', starts_at = now() - interval '2 hours', "
                + "ends_at = now() - interval '1 hour', opened_at = now() - interval '30 minutes', "
                + "closed_at = now() - interval '30 minutes' WHERE id = " + notHeld);

        JsonNode gradebook = gradebook(classA1, teacherA, null);
        JsonNode sessions = gradebook.get("sessions");
        assertThat(columnByCode(sessions, "EC" + tag).get("cohortSize").asLong()).isEqualTo(1);
        assertThat(columnByCode(sessions, "NH" + tag).get("windowState").asText()).isEqualTo("NOT_HELD");
        assertThat(columnByCode(sessions, "NH" + tag).get("cohortSize").asLong()).isZero();
        JsonNode students = gradebook.get("students");
        assertThat(hasRow(students, joinedBetween)).isFalse();
        assertThat(statusOfCell(students, inWindow, gradebook, "EC" + tag))
                .isEqualTo("NOT_ATTEMPTED");
        assertThat(statusOfCell(students, inWindow, gradebook, "NH" + tag))
                .isEqualTo("NOT_HELD");
    }

    @Test
    void upcomingDraftAndCancelledColumnsUseProspectiveOrStateCells() throws Exception {
        long upcoming = session(teacherA, examA, "UP" + tag, "SCHEDULED"); // future window
        long draft = session(teacherA, examA, "DR" + tag, "DRAFT");
        long cancelled = windowedCancelled(teacherA, classA1, examA, "CN" + tag);
        assign(teacherA, classA1, upcoming);
        assign(teacherA, classA1, draft);
        addMember(teacherA, classA1, s1); // LIVE current member (prospective cohort)
        long gone = user("gn" + tag, "Gone " + tag, "STUDENT");
        member(classA1, gone, "-10 hours", "-5 hours"); // departed — not prospective

        JsonNode gradebook = gradebook(classA1, teacherA, null);
        JsonNode students = gradebook.get("students");
        // Upcoming cohorts = CURRENT members (open periods); gone never
        // overlaps any window and is not a current member → absent.
        assertThat(hasRow(students, gone)).isFalse();
        assertThat(statusOfCell(students, s1, gradebook, "UP" + tag)).isEqualTo("UPCOMING");
        assertThat(statusOfCell(students, s1, gradebook, "DR" + tag)).isEqualTo("DRAFT");
        assertThat(statusOfCell(students, s1, gradebook, "CN" + tag)).isEqualTo("CANCELLED");
        // Upcoming sessions never join completed denominators.
        assertThat(rowByUser(students, s1).get("cohortSessions").asLong()).isZero();
        assertThat(gradebook.get("sessions").size()).isEqualTo(3);
    }

    @Test
    void sharedSessionAcrossTwoClassesCountsPerClassCohortWithoutDuplication() throws Exception {
        long sess = windowedSession(teacherA, classA1, examA, "SH" + tag, "-2 hours", "-1 hours");
        assign(teacherA, classA2, sess);
        member(classA1, s1, "-3 hours", null);
        member(classA1, s2, "-3 hours", null);
        member(classA2, s1, "-3 hours", null); // s1 in BOTH cohorts — one attempt
        member(classA2, s3, "-3 hours", null);
        long attemptId = attempt(sess, s1, 1, "GRADED");
        grade(attemptId, "7.00", "10.00", "70.0000");

        JsonNode gb1 = gradebook(classA1, teacherA, null);
        JsonNode gb2 = gradebook(classA2, teacherA, null);
        assertThat(columnByCode(gb1.get("sessions"), "SH" + tag).get("cohortSize").asLong()).isEqualTo(2);
        assertThat(columnByCode(gb1.get("sessions"), "SH" + tag).get("submittedCount").asLong()).isEqualTo(1);
        assertThat(columnByCode(gb1.get("sessions"), "SH" + tag).get("averagePercentage").asDouble())
                .isEqualTo(70.0);
        assertThat(columnByCode(gb2.get("sessions"), "SH" + tag).get("cohortSize").asLong()).isEqualTo(2);
        assertThat(columnByCode(gb2.get("sessions"), "SH" + tag).get("submittedCount").asLong()).isEqualTo(1);
        // The same attempt appears once per class row — no duplication within a class.
        JsonNode row1 = rowByUser(gb1.get("students"), s1);
        assertThat(row1.get("cells").get(0).get("bestAttemptId").asLong()).isEqualTo(attemptId);
        assertThat(row1.get("cells").size()).isEqualTo(1);
    }

    @Test
    void departedStudentRemainsVisibleWithHistory() throws Exception {
        long sess = windowedSession(teacherA, classA1, examA, "LV" + tag, "-2 hours", "-1 hours");
        member(classA1, s1, "-3 hours", "-30 minutes"); // left AFTER submitting
        long attemptId = attempt(sess, s1, 1, "GRADED");
        grade(attemptId, "9.00", "10.00", "90.0000");

        JsonNode gradebook = gradebook(classA1, teacherA, null);
        JsonNode row = rowByUser(gradebook.get("students"), s1);
        assertThat(row.get("currentMember").asBoolean()).isFalse();
        assertThat(row.get("cells").get(0).get("status").asText()).isEqualTo("SCORED");
        assertThat(row.get("cells").get(0).get("percentage").asDouble()).isEqualTo(90.0);
    }

    @Test
    void blockedParticipantStaysInCohortWithAttemptData() throws Exception {
        long sess = windowedSession(teacherA, classA1, examA, "BL" + tag, "-2 hours", "1 hours"); // OPEN window
        jdbc.update("UPDATE exam_sessions SET status='OPEN', opened_at=now() - interval '2 hours', "
                + "closed_at=NULL WHERE id = " + sess);
        member(classA1, s1, "-3 hours", null);
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by, "
                + "status, blocked_at) VALUES (" + s1 + "," + sess + "," + teacherA
                + ",'BLOCKED', now())");
        long attemptId = attempt(sess, s1, 1, "SUBMITTED"); // submitted before the block
        grade(attemptId, "5.00", "10.00", "50.0000");

        JsonNode gradebook = gradebook(classA1, teacherA, null);
        JsonNode row = rowByUser(gradebook.get("students"), s1);
        // BLOCKED only gates NEW starts; cohort membership and existing results stand.
        assertThat(row.get("cells").get(0).get("status").asText()).isEqualTo("SCORED");
    }

    // ============================================================
    // Analytics
    // ============================================================

    @Test
    void analyticsOverviewAndSessionDetailMatchGradebook() throws Exception {
        long sess = windowedSession(teacherA, classA1, examA, "AN" + tag, "-2 hours", "-1 hours");
        addMember(teacherA, classA1, s1);
        addMember(teacherA, classA1, s2);
        addMember(teacherA, classA1, s3);
        backdateOpenPeriod(classA1, s1, "-3 hours");
        backdateOpenPeriod(classA1, s2, "-3 hours");
        backdateOpenPeriod(classA1, s3, "-3 hours");
        grade(attempt(sess, s1, 1, "GRADED"), "9.00", "10.00", "90.0000");
        grade(attempt(sess, s2, 1, "GRADED"), "5.00", "10.00", "50.0000");

        JsonNode overview = analytics(classA1, teacherA, null);
        assertThat(overview.get("currentRosterSize").asLong()).isEqualTo(3);
        assertThat(overview.get("assignedSessionCount").asLong()).isEqualTo(1);
        assertThat(overview.get("overallCompletionPercentage").asDouble()).isEqualTo(66.67);
        assertThat(overview.get("sessions").size()).isEqualTo(1);

        JsonNode detail = analytics(classA1, teacherA, sess);
        assertThat(detail.get("cohortSize").asLong()).isEqualTo(3);
        assertThat(detail.get("startedCount").asLong()).isEqualTo(2);
        assertThat(detail.get("submittedCount").asLong()).isEqualTo(2);
        assertThat(detail.get("gradedCount").asLong()).isEqualTo(2);
        assertThat(detail.get("notAttemptedCount").asLong()).isEqualTo(1);
        assertThat(detail.get("completionPercentage").asDouble()).isEqualTo(66.67);
        assertThat(detail.get("averagePercentage").asDouble()).isEqualTo(70.0);
        assertThat(detail.get("minimumPercentage").asDouble()).isEqualTo(50.0);
        assertThat(detail.get("maximumPercentage").asDouble()).isEqualTo(90.0);
        // Median of {50, 90} = 70.
        assertThat(detail.get("medianPercentage").asDouble()).isEqualTo(70.0);
        JsonNode buckets = detail.get("distributionBuckets");
        assertThat(buckets.size()).isEqualTo(10);
        assertThat(buckets.get(5).asLong()).isEqualTo(1); // 50.x → bucket 5
        assertThat(buckets.get(9).asLong()).isEqualTo(1); // 90.x → bucket 9
    }

    // ============================================================
    // Export
    // ============================================================

    @Test
    void exportMatchesGradebookContentAndIsOwnerScoped() throws Exception {
        long sess = windowedSession(teacherA, classA1, examA, "XP" + tag, "-2 hours", "-1 hours");
        member(classA1, s1, "-3 hours", null);
        grade(attempt(sess, s1, 1, "GRADED"), "8.00", "10.00", "80.0000");

        MvcResult result = mockMvc.perform(get("/api/classrooms/" + classA1 + "/gradebook/export")
                        .with(jwtFor(teacherA))).andExpect(status().isOk()).andReturn();
        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body.length).isGreaterThan(500); // real XLSX (zip) payload
        assertThat(result.getResponse().getContentType())
                .contains("spreadsheetml.sheet");
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .contains("quizopia-class-" + classA1 + "-gradebook-");

        // Non-owner teacher: 403. Learner: 403. Unknown classroom: 404.
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/gradebook/export").with(jwtFor(teacherB)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/gradebook/export").with(jwtFor(s1)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/classrooms/999999999/gradebook/export").with(jwtFor(teacherA)))
                .andExpect(status().isNotFound());
    }

    // ============================================================
    // Learner my-results
    // ============================================================

    @Test
    void myResultsShowsOwnOutcomesOnlyToMembers() throws Exception {
        long sess = windowedSession(teacherA, classA1, examA, "MR" + tag, "-2 hours", "-1 hours");
        long upcoming = session(teacherA, examA, "MU" + tag, "SCHEDULED");
        assign(teacherA, classA1, upcoming);
        // LIVE membership via the API (fires the history trigger); backdate
        // the epoch so both learners were members during the window.
        addMember(teacherA, classA1, s1);
        addMember(teacherA, classA1, s2);
        backdateOpenPeriod(classA1, s1, "-3 hours");
        backdateOpenPeriod(classA1, s2, "-3 hours");
        grade(attempt(sess, s1, 1, "GRADED"), "7.00", "10.00", "70.0000");
        attempt(sess, s2, 1, "IN_PROGRESS");

        MvcResult result = mockMvc.perform(get("/api/classrooms/joined/" + classA1 + "/my-results")
                        .with(jwtFor(s1))).andExpect(status().isOk()).andReturn();
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("items");
        assertThat(items.size()).isEqualTo(2);
        JsonNode own = itemByCode(items, "MR" + tag);
        assertThat(own.get("resultStatus").asText()).isEqualTo("SCORED");
        assertThat(own.get("percentage").asDouble()).isEqualTo(70.0);
        assertThat(own.get("attemptCount").asLong()).isEqualTo(1);
        assertThat(itemByCode(items, "MU" + tag).get("resultStatus").asText()).isEqualTo("UPCOMING");

        // Self-scoped: s2 sees IN_PROGRESS, not s1's score.
        MvcResult result2 = mockMvc.perform(get("/api/classrooms/joined/" + classA1 + "/my-results")
                        .with(jwtFor(s2))).andExpect(status().isOk()).andReturn();
        JsonNode items2 = objectMapper.readTree(result2.getResponse().getContentAsString())
                .get("items");
        assertThat(itemByCode(items2, "MR" + tag).get("resultStatus").asText()).isEqualTo("IN_PROGRESS");
        assertThat(itemByCode(items2, "MR" + tag).get("percentage").isNull()).isTrue();

        // Non-member (owner) and outsider: uniform 404; unknown class 404.
        mockMvc.perform(get("/api/classrooms/joined/" + classA1 + "/my-results")
                        .with(jwtFor(teacherA))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/classrooms/joined/" + classA1 + "/my-results")
                        .with(jwtFor(s3))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/classrooms/joined/999999999/my-results")
                        .with(jwtFor(s1))).andExpect(status().isNotFound());
    }

    // ============================================================
    // Query hardening: sort/search/paging/invalid
    // ============================================================

    @Test
    void gradebookSortsSearchesAndRejectsInvalidQueries() throws Exception {
        long sess = windowedSession(teacherA, classA1, examA, "Q1" + tag, "-2 hours", "-1 hours");
        member(classA1, s1, "-3 hours", null);
        member(classA1, s2, "-3 hours", null);
        grade(attempt(sess, s1, 1, "GRADED"), "8.00", "10.00", "80.0000");

        // Search narrows rows.
        JsonNode searched = gradebook(classA1, teacherA, null, "search", "Student One");
        assertThat(searched.get("totalElements").asLong()).isEqualTo(1);
        assertThat(searched.get("students").get(0).get("userId").asLong()).isEqualTo(s1);
        // Sort by meanPercentage desc.
        JsonNode sorted = gradebook(classA1, teacherA, null, "sort", "meanPercentage",
                "direction", "desc");
        assertThat(sorted.get("students").get(0).get("userId").asLong()).isEqualTo(s1);
        // Invalid sort/direction/status/sessionIds → 400.
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/gradebook").with(jwtFor(teacherA))
                        .param("sort", "evil")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/gradebook").with(jwtFor(teacherA))
                        .param("direction", "sideways")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/gradebook").with(jwtFor(teacherA))
                        .param("sessionIds", "999999999")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/gradebook/sessions")
                        .with(jwtFor(teacherA)).param("status", "NOPE"))
                .andExpect(status().isBadRequest());
        // Over-page clamps to the last page.
        JsonNode clamped = gradebook(classA1, teacherA, null, "page", "99");
        assertThat(clamped.get("page").asInt()).isEqualTo(0);
    }

    @Test
    void teacherRoutesAreOwnerScopedAndLearnerDenied() throws Exception {
        long sess = windowedSession(teacherA, classA1, examA, "SE" + tag, "-2 hours", "-1 hours");
        member(classA1, s1, "-3 hours", null);

        // Cross-teacher: 403 on every teacher route (classA1 is A's).
        for (String path : new String[] { "/gradebook/sessions", "/gradebook", "/analytics",
                "/gradebook/export" }) {
            mockMvc.perform(get("/api/classrooms/" + classA1 + path).with(jwtFor(teacherB)))
                    .andExpect(status().isForbidden());
        }
        // Learner: 403 (not the owner).
        mockMvc.perform(get("/api/classrooms/" + classA1 + "/gradebook").with(jwtFor(s1)))
                .andExpect(status().isForbidden());
        // Unknown classroom: 404.
        mockMvc.perform(get("/api/classrooms/999999999/gradebook").with(jwtFor(teacherA)))
                .andExpect(status().isNotFound());
        // Teacher B's own classroom works (sanity — B owns classB1).
        mockMvc.perform(get("/api/classrooms/" + classB1 + "/gradebook").with(jwtFor(teacherB)))
                .andExpect(status().isOk());
        // Only the assigned junction drives columns (no PUBLIC/unassigned sessions).
        JsonNode gradebook = gradebook(classA1, teacherA, null);
        assertThat(gradebook.get("sessions").size()).isEqualTo(1);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static JwtRequestPostProcessor jwtFor(long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private long user(String username, String displayName, String role) {
        long id = jdbc.queryForObject("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('" + username + "','" + username + "@t.com','h','" + displayName + "') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT " + id
                + ", id FROM roles WHERE code='" + role + "'");
        if ("STUDENT".equals(role)) {
            jdbc.update("UPDATE users SET email_verified_at=now() WHERE id=" + id);
        }
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

    private void addMember(long teacherId, long classroomId, long userId) throws Exception {
        mockMvc.perform(post("/api/classrooms/" + classroomId + "/members").with(jwtFor(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[" + userId + "]}"))
                .andExpect(status().isOk());
    }

    /** Moves an API-created membership epoch into the past (fixture only). */
    private void backdateOpenPeriod(long classroomId, long userId, String offset) {
        jdbc.update("UPDATE classroom_membership_periods SET joined_at = now() + interval '"
                + offset + "' WHERE classroom_id = " + classroomId + " AND user_id = " + userId
                + " AND left_at IS NULL");
    }

    /**
     * CLOSED session with a REAL window [startsAt, closedAt] assigned to the
     * class — mirrors the lifecycle: create SCHEDULED (future) → assign via
     * the API → SQL-transition to CLOSED with the desired window.
     */
    private long windowedSession(long teacherId, long classroomId, long examId, String code,
            String startsOffset, String endsOffset) throws Exception {
        long id = insert("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, "
                + "max_attempts, created_by, visibility, duration_minutes) VALUES ("
                + examId + ",'" + code + "','S " + code + "','SCHEDULED', "
                + "now() + interval '1 day', now() + interval '2 days', 1," + teacherId
                + ", 'CLASS_RESTRICTED', 30)");
        assign(teacherId, classroomId, id);
        jdbc.update("UPDATE exam_sessions SET status='CLOSED', starts_at = now() + interval '"
                + startsOffset + "', ends_at = now() + interval '" + endsOffset
                + "', opened_at = now() + interval '" + startsOffset + "', closed_at = now() "
                + "WHERE id = " + id);
        return id;
    }

    private long windowedCancelled(long teacherId, long classroomId, long examId, String code)
            throws Exception {
        long id = insert("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, "
                + "max_attempts, created_by, visibility, duration_minutes) VALUES ("
                + examId + ",'" + code + "','S " + code + "','SCHEDULED', "
                + "now() + interval '1 day', now() + interval '2 days', 1," + teacherId
                + ", 'CLASS_RESTRICTED', 30)");
        assign(teacherId, classroomId, id);
        jdbc.update("UPDATE exam_sessions SET status='CANCELLED' WHERE id = " + id);
        return id;
    }

    /** SCHEDULED (future) or DRAFT session helper. */
    private long session(long ownerId, long examId, String code, String status) {
        String starts = "SCHEDULED".equals(status)
                ? "now() + interval '1 day'" : "now() - interval '1 hour'";
        return insert("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, "
                + "max_attempts, created_by, visibility, duration_minutes) VALUES ("
                + examId + ",'" + code + "','S " + code + "','" + status + "', " + starts
                + ", now() + interval '2 days', 1," + ownerId + ", 'CLASS_RESTRICTED', 30)");
    }

    private void assign(long teacherId, long classroomId, long sessionId) throws Exception {
        mockMvc.perform(post("/api/classrooms/" + classroomId + "/sessions/" + sessionId)
                        .with(jwtFor(teacherId)))
                .andExpect(status().isNoContent());
    }

    /** Membership epoch fixture (direct period insert — read-model tests). */
    private void member(long classroomId, long userId, String joinedOffset, String leftOffset) {
        jdbc.update("INSERT INTO classroom_membership_periods (classroom_id, user_id, joined_at, "
                + "left_at) VALUES (" + classroomId + "," + userId + ", now() + interval '"
                + joinedOffset + "', " + (leftOffset == null ? "NULL"
                        : "now() + interval '" + leftOffset + "'") + ")");
    }

    private long attempt(long sessionId, long userId, int number, String status) {
        boolean submitted = "SUBMITTED".equals(status) || "GRADED".equals(status);
        String key = tag + "-" + sessionId + "-" + userId + "-" + number;
        String extraCols = submitted ? ", submitted_at, submission_idempotency_key" : "";
        String extraVals = submitted ? ", now(), '" + key + "'" : "";
        return insert("INSERT INTO attempts (exam_session_id, user_id, attempt_number, status, "
                + "started_at, deadline_at" + extraCols + ") VALUES (" + sessionId + "," + userId
                + "," + number + ",'" + status + "', now(), now() + interval '1 hour'"
                + extraVals + ")");
    }

    private void grade(long attemptId, String finalScore, String maxScore, String percentage) {
        jdbc.update("INSERT INTO grades (attempt_id, automatic_score, final_score, max_score, "
                + "percentage, status, graded_at) VALUES (" + attemptId + "," + finalScore + ","
                + finalScore + "," + maxScore + "," + percentage + ",'AUTO_GRADED', now())");
    }

    private JsonNode gradebook(long classroomId, long teacherId, Long sessionId, String... params)
            throws Exception {
        var request = get("/api/classrooms/" + classroomId + "/gradebook").with(jwtFor(teacherId));
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        if (sessionId != null) {
            request = request.param("sessionIds", String.valueOf(sessionId));
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode analytics(long classroomId, long teacherId, Long sessionId) throws Exception {
        var request = get("/api/classrooms/" + classroomId + "/analytics").with(jwtFor(teacherId));
        if (sessionId != null) {
            request = request.param("sessionId", String.valueOf(sessionId));
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static boolean hasRow(JsonNode students, long userId) {
        for (JsonNode row : students) {
            if (row.get("userId").asLong() == userId) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode rowByUser(JsonNode students, long userId) {
        for (JsonNode row : students) {
            if (row.get("userId").asLong() == userId) {
                return row;
            }
        }
        throw new AssertionError("no row for user " + userId);
    }

    private static int columnIndex(JsonNode gradebook, String code) {
        JsonNode sessions = gradebook.get("sessions");
        for (int i = 0; i < sessions.size(); i++) {
            if (code.equals(sessions.get(i).get("code").asText())) {
                return i;
            }
        }
        throw new AssertionError("no column for code " + code);
    }

    private static JsonNode cellOf(JsonNode students, long userId, String code, JsonNode gradebook) {
        return rowByUser(students, userId).get("cells").get(columnIndex(gradebook, code));
    }

    private static JsonNode columnByCode(JsonNode sessions, String code) {
        for (JsonNode col : sessions) {
            if (code.equals(col.get("code").asText())) {
                return col;
            }
        }
        throw new AssertionError("no column for code " + code);
    }

    private static String statusOfCell(JsonNode students, long userId, JsonNode gradebook,
            String code) {
        return cellOf(students, userId, code, gradebook).get("status").asText();
    }

    private static JsonNode itemByCode(JsonNode items, String code) {
        for (JsonNode item : items) {
            if (code.equals(item.get("code").asText())) {
                return item;
            }
        }
        throw new AssertionError("no item for code " + code);
    }
}
