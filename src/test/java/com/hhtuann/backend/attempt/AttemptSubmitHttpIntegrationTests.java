package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.dto.StartAttemptRequest;
import com.hhtuann.backend.testsupport.MutableClock;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import com.hhtuann.backend.testsupport.TestClockConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MockMvc HTTP tests for POST /api/attempts/{id}/submit (A3.2-4 idempotent submit). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class AttemptSubmitHttpIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AttemptService attemptService;
    @Autowired private MutableClock clock;
    @Autowired private EntityManager em;

    private long userId;
    private long attemptId;

    private static final String KEY = "submit-key-001";

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        clock.setInstant(now);
        userId = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('s','s@t.com','h','S')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + userId + "," + roleId + ")");
        long school = ins("INSERT INTO schools (code, name) VALUES ('SS','S')");
        long gl = ins("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
        long subj = ins("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','M')");
        long tp = ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + userId + "," + school + ",'TC')");
        long sp = ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + userId + "," + school + ",'SC')");
        long bank = ins("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + "," + tp + ",'B','B')");
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q'," + userId + ")");
        long qv = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','Q',1,'{}'::jsonb," + userId + ")");
        long exam = ins("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + "," + tp + ",'E','E')");
        long ver = ins("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',1,now()," + userId + ")");
        long sec = ins("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
        long eq = ins("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','Q',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
        long session = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES (" + school + "," + ver + "," + tp + ",'S','S','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + userId + ",'" + now.minusSeconds(3600) + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + session + "," + sp + "," + userId + ")");
        attemptId = attemptService.startAttempt(userId, session, new StartAttemptRequest(null)).attemptId();
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtWith(long uid) {
        return jwt().jwt(j -> j.subject(String.valueOf(uid)));
    }

    private String submitPath() { return "/api/attempts/" + attemptId + "/submit"; }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rawWith(long uid, String content) {
        return post(submitPath()).with(jwtWith(uid)).contentType(MediaType.APPLICATION_JSON).content(content);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder raw(String content) { return rawWith(userId, content); }

    private String keyBody(String key) {
        return "{\"submissionIdempotencyKey\":" + (key == null ? "null" : "\"" + key + "\"") + "}";
    }

    // === success / cached ===

    @Test void returns200FirstSubmit() throws Exception {
        String body = mockMvc.perform(raw(keyBody(KEY))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED")).andExpect(jsonPath("$.attemptId").value(attemptId))
                .andReturn().getResponse().getContentAsString();
        assertNoLeak(body);
    }

    @Test void returns200SameKeyRetry() throws Exception {
        markSubmitted(attemptId, KEY);
        String body = mockMvc.perform(raw(keyBody(KEY))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED")).andReturn().getResponse().getContentAsString();
        assertNoLeak(body);
    }

    @Test void retryReturnsImmutableSubmittedAtAndServerTime() throws Exception {
        markSubmitted(attemptId, KEY);
        Instant cached = jdbc.queryForObject("SELECT (response_body->>'submittedAt')::timestamptz FROM idempotency_records WHERE attempt_id=?", Instant.class, attemptId);
        mockMvc.perform(raw(keyBody(KEY))).andExpect(status().isOk())
                .andExpect(jsonPath("$.submittedAt").value(cached.toString()))
                .andExpect(jsonPath("$.serverTime").value(cached.toString()));
    }

    // === malformed / missing body / key ===

    @Test void returns400MalformedJson() throws Exception {
        assertNoInternalLeak(err(raw("{bad"), 400, "ATTEMPT_VALIDATION_ERROR"));
    }

    @Test void returns400LiteralNullBody() throws Exception {
        assertNoInternalLeak(err(raw("null"), 400, "ATTEMPT_VALIDATION_ERROR"));
    }

    @Test void returns400EmptyBody() throws Exception {
        assertNoInternalLeak(err(raw(""), 400, "ATTEMPT_VALIDATION_ERROR"));
    }

    @Test void returns400EmptyObjectBody() throws Exception {
        assertNoInternalLeak(err(raw("{}"), 400, "ATTEMPT_VALIDATION_ERROR"));
    }

    @Test void returns400NullKey() throws Exception {
        assertNoInternalLeak(err(raw(keyBody(null)), 400, "ATTEMPT_VALIDATION_ERROR"));
    }

    @Test void returns400BlankKey() throws Exception {
        assertNoInternalLeak(err(raw(keyBody(" ")), 400, "ATTEMPT_VALIDATION_ERROR"));
    }

    @Test void returns400WhitespaceKey() throws Exception {
        assertNoInternalLeak(err(raw(keyBody("key value")), 400, "ATTEMPT_VALIDATION_ERROR"));
    }

    @Test void returns400OverlongKey() throws Exception {
        assertNoInternalLeak(err(raw(keyBody("k".repeat(101))), 400, "ATTEMPT_VALIDATION_ERROR"));
    }

    // === auth / ownership ===

    @Test void returns401Unauthenticated() throws Exception {
        mockMvc.perform(post(submitPath()).contentType(MediaType.APPLICATION_JSON).content(keyBody(KEY))).andExpect(status().isUnauthorized());
    }

    @Test void returns403MissingRole() throws Exception {
        long teacherRole = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("UPDATE user_roles SET role_id=" + teacherRole + " WHERE user_id=" + userId);
        assertNoInternalLeak(err(raw(keyBody(KEY)), 403, "ATTEMPT_ACCESS_DENIED"));
    }

    @Test void returns403RevokedPermission() throws Exception {
        jdbc.update("DELETE FROM role_permissions WHERE role_id=(SELECT id FROM roles WHERE code='STUDENT') AND permission_id=(SELECT id FROM permissions WHERE code='ATTEMPT_SUBMIT')");
        assertNoInternalLeak(err(raw(keyBody(KEY)), 403, "ATTEMPT_ACCESS_DENIED"));
    }

    @Test void returns403Foreign() throws Exception {
        long other = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('o','o@t.com','h','O')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + other + "," + roleId + ")");
        ins("INSERT INTO student_profiles (user_id, school_id, student_code) SELECT " + other + ", school_id, 'OS' FROM student_profiles WHERE user_id=" + userId);
        assertNoInternalLeak(err(rawWith(other, keyBody(KEY)), 403, "ATTEMPT_ACCESS_DENIED"));
    }

    @Test void returns403CrossSchool() throws Exception {
        long xs = ins("INSERT INTO schools (code, name) VALUES ('XS','XS')");
        long other = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('x','x@t.com','h','X')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + other + "," + roleId + ")");
        ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + other + "," + xs + ",'XS')");
        assertNoInternalLeak(err(rawWith(other, keyBody(KEY)), 403, "ATTEMPT_ACCESS_DENIED"));
    }

    @Test void returns404MissingProfile() throws Exception {
        long np = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('np','np@t.com','h','NP')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + np + "," + roleId + ")");
        mockMvc.perform(rawWith(np, keyBody(KEY))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ATTEMPT_STUDENT_PROFILE_NOT_FOUND"));
    }

    @Test void returns404MissingAttempt() throws Exception {
        mockMvc.perform(post("/api/attempts/999999/submit").with(jwtWith(userId)).contentType(MediaType.APPLICATION_JSON).content(keyBody(KEY)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ATTEMPT_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/attempts/999999/submit"));
    }

    // === state / deadline / conflict ===

    @Test void returns409DeadlineExceeded() throws Exception {
        Instant deadline = jdbc.queryForObject("SELECT deadline_at FROM attempts WHERE id=" + attemptId, Instant.class);
        clock.setInstant(deadline.plusSeconds(1));
        assertNoInternalLeak(err(raw(keyBody(KEY)), 409, "ATTEMPT_DEADLINE_EXCEEDED"));
    }

    @Test void returns409Graded() throws Exception {
        jdbc.update("UPDATE attempts SET status='GRADED', submitted_at=now(), submission_idempotency_key='G' WHERE id=" + attemptId);
        em.clear();
        assertNoInternalLeak(err(raw(keyBody("G")), 409, "ATTEMPT_INVALID_STATE"));
    }

    @Test void returns409SameAttemptDifferentKey() throws Exception {
        markSubmitted(attemptId, KEY);
        assertNoInternalLeak(err(raw(keyBody("other-key-002")), 409, "ATTEMPT_ALREADY_SUBMITTED"));
    }

    @Test void returns409DifferentAttemptSameKey() throws Exception {
        // attempt1 (the test attempt) holds a cache for KEY; start attempt2 then submit KEY → CONFLICT.
        markSubmitted(attemptId, KEY);
        long session = jdbc.queryForObject("SELECT exam_session_id FROM attempts WHERE id=" + attemptId, Long.class);
        long attempt2 = attemptService.startAttempt(userId, session, new StartAttemptRequest(null)).attemptId();
        String content = mockMvc.perform(post("/api/attempts/" + attempt2 + "/submit").with(jwtWith(userId)).contentType(MediaType.APPLICATION_JSON).content(keyBody(KEY)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATTEMPT_IDEMPOTENCY_CONFLICT"))
                .andReturn().getResponse().getContentAsString();
        assertNoInternalLeak(content);
    }

    // === cache failure → sanitized 500 ===

    @Test void returns500WhenCacheMissing() throws Exception {
        markSubmitted(attemptId, KEY);
        jdbc.update("DELETE FROM idempotency_records WHERE attempt_id=?", attemptId);
        em.clear();
        String content = mockMvc.perform(raw(keyBody(KEY))).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn().getResponse().getContentAsString();
        assertNoInternalLeak(content);
    }

    @Test void returns500WhenCacheMalformed() throws Exception {
        markSubmitted(attemptId, KEY);
        // A valid JSON object (passes chk_idempotency_body) that is not a SubmitResponse → 500.
        jdbc.update("UPDATE idempotency_records SET response_body='{\"foo\":\"bar\"}'::jsonb WHERE attempt_id=?", attemptId);
        em.clear();
        String content = mockMvc.perform(raw(keyBody(KEY))).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn().getResponse().getContentAsString();
        assertNoInternalLeak(content);
    }

    // === helpers ===

    private String err(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, int status, String code) throws Exception {
        return mockMvc.perform(req).andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.path").value(submitPath())).andReturn().getResponse().getContentAsString();
    }

    /** Marks the attempt SUBMITTED and inserts a well-formed cache row (so retry/conflict paths read committed state). */
    private void markSubmitted(long attemptId, String key) {
        Instant now = Instant.now(clock);
        jdbc.update("UPDATE attempts SET status='SUBMITTED', submitted_at=?, submission_idempotency_key=?, updated_at=? WHERE id=?",
                java.sql.Timestamp.from(now), key, java.sql.Timestamp.from(now), attemptId);
        Integer attemptNumber = jdbc.queryForObject("SELECT attempt_number FROM attempts WHERE id=?", Integer.class, attemptId);
        String body = "'{\"attemptId\":" + attemptId + ",\"status\":\"SUBMITTED\","
                + "\"submittedAt\":\"" + now.toString() + "\",\"serverTime\":\"" + now.toString() + "\","
                + "\"attemptNumber\":" + attemptNumber + "}'::jsonb";
        jdbc.update("INSERT INTO idempotency_records (user_id, attempt_id, operation, idempotency_key, response_status, response_body, expires_at) VALUES ("
                + userId + "," + attemptId + ",'ATTEMPT_SUBMIT','" + key + "',200," + body + ",NULL)");
        em.clear();
    }

    private void assertNoLeak(String content) {
        // Day 8: the submit response legitimately carries the student's own score/maxScore/percentage.
        // The forbidden tokens are answer-key/identity/internal fields — never the answer key.
        assertThat(content).doesNotContain("submissionIdempotencyKey").doesNotContain("idempotencyKey")
                .doesNotContain("responseBody").doesNotContain("answerPayload").doesNotContain("answerKey")
                .doesNotContain("expectedAnswer").doesNotContain("isCorrect")
                .doesNotContain("grade").doesNotContain("studentProfileId").doesNotContain("schoolId")
                .doesNotContain("clientInstanceId");
    }

    private void assertNoInternalLeak(String content) {
        assertThat(content).doesNotContain("constraint").doesNotContain("org.hibernate").doesNotContain("org.postgresql")
                .doesNotContain("Caused by").doesNotContain("at com.hhtuann");
    }

    private long ins(String sql) { return jdbc.queryForObject(sql + " RETURNING id", Long.class); }
}
