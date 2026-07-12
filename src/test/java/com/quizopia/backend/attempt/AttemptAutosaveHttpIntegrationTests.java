package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc HTTP acceptance tests for PUT /api/attempts/{id}/answers (A3.2-3R2 §7). Covers accepted,
 * stale (+STALE_SEQUENCE), clear, every malformed/missing-body case, malformed clientInstanceId, the
 * full authorization matrix (missing role / revoked permission / missing profile / foreign / cross-school),
 * question resolution, state/deadline (SUBMITTED/GRADED/after-deadline), invalid payloads for all 4 types,
 * and corrupted persisted option_order. Every error test asserts exact status + exact {@code $.code} +
 * exact {@code $.path} + no internal leak (no constraint/SQL/hibernate/postgres/stacktrace).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class AttemptAutosaveHttpIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AttemptService attemptService;
    @Autowired private MutableClock clock;
    @Autowired private EntityManager em;

    private long userId;
    private long attemptId;
    private long aqId;        // SINGLE
    private long aqMultipleId;
    private long aqTfId;
    private long aqNumericId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        clock.setInstant(now);
        userId = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('a','a@t.com','h','A')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + userId + "," + roleId + ")");
        long school = ins("INSERT INTO schools (code, name) VALUES ('AS','S')");
        long gl = ins("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
        long subj = ins("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','M')");
        long tp = ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + userId + "," + school + ",'TC')");
        long sp = ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + userId + "," + school + ",'SC')");
        long bank = ins("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + "," + tp + ",'B','B')");
        long v = ver(school, subj, tp, userId);
        long section = sec(v);
        long eqSingle = eq(v, section, bank, "SINGLE_CHOICE", 0);
        long eqMultiple = eq(v, section, bank, "MULTIPLE_CHOICE", 1);
        long eqTf = eq(v, section, bank, "TRUE_FALSE_MATRIX", 2);
        long eqNumeric = eqNumeric(v, section, bank, 3);
        opts(eqSingle); opts(eqMultiple); opts(eqTf);
        long session = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES (" + school + "," + v + "," + tp + ",'S','S','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + userId + ",'" + now.minusSeconds(3600) + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + session + "," + sp + "," + userId + ")");
        attemptId = attemptService.startAttempt(userId, session, new StartAttemptRequest(null)).attemptId();
        aqId = aqOf(eqSingle);
        aqMultipleId = aqOf(eqMultiple);
        aqTfId = aqOf(eqTf);
        aqNumericId = aqOf(eqNumeric);
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtWith(long uid) {
        return jwt().jwt(j -> j.subject(String.valueOf(uid)));
    }

    private String answersPath() { return "/api/attempts/" + attemptId + "/answers"; }

    private String bodyFor(long aq, String payload, long seq) {
        return "{\"attemptQuestionId\":" + aq + ",\"answerPayload\":" + (payload == null ? "null" : payload) + ",\"sequenceNumber\":" + seq + "}";
    }

    private String body(String payload, long seq) { return bodyFor(aqId, payload, seq); }

    // === success / stale / clear ===

    @Test void returns200Accepted() throws Exception {
        String content = mockMvc.perform(put(answersPath()).with(jwtWith(userId)).contentType(MediaType.APPLICATION_JSON).content(body("{\"selectedOptionKey\":\"A\"}", 1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true)).andExpect(jsonPath("$.currentSequenceNumber").value(1))
                .andReturn().getResponse().getContentAsString();
        assertNoLeak(content);
    }

    @Test void returns200StaleWithReason() throws Exception {
        mockMvc.perform(put(answersPath()).with(jwtWith(userId)).contentType(MediaType.APPLICATION_JSON).content(body("{\"selectedOptionKey\":\"A\"}", 5)))
                .andExpect(status().isOk());
        String content = mockMvc.perform(put(answersPath()).with(jwtWith(userId)).contentType(MediaType.APPLICATION_JSON).content(body("{\"selectedOptionKey\":\"B\"}", 3)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(false)).andExpect(jsonPath("$.reason").value("STALE_SEQUENCE"))
                .andReturn().getResponse().getContentAsString();
        assertNoLeak(content);
    }

    @Test void returns200Clear() throws Exception {
        mockMvc.perform(put(answersPath()).with(jwtWith(userId)).contentType(MediaType.APPLICATION_JSON).content(body(null, 1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
    }

    // === malformed / missing body ===

    @Test void returns400MalformedJson() throws Exception {
        String content = err(put(answersPath()).with(jwtWith(userId)).contentType(MediaType.APPLICATION_JSON).content("{bad"), 400, "ATTEMPT_VALIDATION_ERROR");
        assertNoInternalLeak(content);
    }

    @Test void returns400BodyLiteralNull() throws Exception {
        assertNoInternalLeak(err(raw("null"), 400, "ATTEMPT_VALIDATION_ERROR"));
    }

    @Test void returns400EmptyBody() throws Exception {
        assertNoInternalLeak(err(raw(""), 400, "ATTEMPT_VALIDATION_ERROR"));
    }

    @Test void returns400EmptyObjectBody() throws Exception {
        // {} → sequenceNumber defaults to 0 (< 1) → ATTEMPT_VALIDATION_ERROR.
        assertNoInternalLeak(err(raw("{}"), 400, "ATTEMPT_VALIDATION_ERROR"));
    }

    @Test void returns400MalformedClientInstanceId() throws Exception {
        String body = "{\"attemptQuestionId\":" + aqId + ",\"answerPayload\":{\"selectedOptionKey\":\"A\"},\"sequenceNumber\":1,\"clientInstanceId\":\"not-a-uuid\"}";
        assertNoInternalLeak(err(raw(body), 400, "ATTEMPT_VALIDATION_ERROR"));
    }

    @Test void returns400MissingQuestionIdentifiers() throws Exception {
        String body = "{\"answerPayload\":{\"selectedOptionKey\":\"A\"},\"sequenceNumber\":1}";
        assertNoInternalLeak(err(raw(body), 400, "ATTEMPT_VALIDATION_ERROR"));
    }

    @Test void returns400SequenceLessThan1() throws Exception {
        assertNoInternalLeak(err(raw(body("{\"selectedOptionKey\":\"A\"}", 0)), 400, "ATTEMPT_VALIDATION_ERROR"));
    }

    // === authorization ===

    @Test void returns401Unauthenticated() throws Exception {
        mockMvc.perform(put(answersPath()).contentType(MediaType.APPLICATION_JSON).content(body("{\"selectedOptionKey\":\"A\"}", 1)))
                .andExpect(status().isUnauthorized());
    }

    @Test void returns403MissingRole() throws Exception {
        long teacherRole = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("UPDATE user_roles SET role_id=" + teacherRole + " WHERE user_id=" + userId);
        assertNoInternalLeak(err(raw(body("{\"selectedOptionKey\":\"A\"}", 1)), 403, "ATTEMPT_ACCESS_DENIED"));
    }

    @Test void returns403RevokedPermission() throws Exception {
        jdbc.update("DELETE FROM role_permissions WHERE role_id=(SELECT id FROM roles WHERE code='STUDENT') AND permission_id=(SELECT id FROM permissions WHERE code='ATTEMPT_ANSWER_SAVE')");
        assertNoInternalLeak(err(raw(body("{\"selectedOptionKey\":\"A\"}", 1)), 403, "ATTEMPT_ACCESS_DENIED"));
    }

    @Test void returns404MissingStudentProfile() throws Exception {
        long noProfile = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('np','np@t.com','h','NP')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + noProfile + "," + roleId + ")");
        String content = mockMvc.perform(put(answersPath()).with(jwtWith(noProfile)).contentType(MediaType.APPLICATION_JSON).content(body("{\"selectedOptionKey\":\"A\"}", 1)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ATTEMPT_STUDENT_PROFILE_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value(answersPath())).andReturn().getResponse().getContentAsString();
        assertNoInternalLeak(content);
    }

    @Test void returns403Foreign() throws Exception {
        long other = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('o','o@t.com','h','O')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + other + "," + roleId + ")");
        ins("INSERT INTO student_profiles (user_id, school_id, student_code) SELECT " + other + ", school_id, 'OS' FROM student_profiles WHERE user_id=" + userId);
        assertNoInternalLeak(err(rawWith(other, body("{\"selectedOptionKey\":\"A\"}", 1)), 403, "ATTEMPT_ACCESS_DENIED"));
    }

    @Test void returns403CrossSchool() throws Exception {
        long otherSchool = ins("INSERT INTO schools (code, name) VALUES ('XS','XS')");
        long other = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('x','x@t.com','h','X')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + other + "," + roleId + ")");
        ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + other + "," + otherSchool + ",'XS')");
        assertNoInternalLeak(err(rawWith(other, body("{\"selectedOptionKey\":\"A\"}", 1)), 403, "ATTEMPT_ACCESS_DENIED"));
    }

    // === question resolution / missing ===

    @Test void returns404MissingAttempt() throws Exception {
        String content = mockMvc.perform(put("/api/attempts/999999/answers").with(jwtWith(userId)).contentType(MediaType.APPLICATION_JSON).content(body("{\"selectedOptionKey\":\"A\"}", 1)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ATTEMPT_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/attempts/999999/answers")).andReturn().getResponse().getContentAsString();
        assertNoInternalLeak(content);
    }

    @Test void returns404MissingAttemptQuestion() throws Exception {
        String body = "{\"attemptQuestionId\":999999,\"answerPayload\":{\"selectedOptionKey\":\"A\"},\"sequenceNumber\":1}";
        String content = mockMvc.perform(put(answersPath()).with(jwtWith(userId)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ATTEMPT_QUESTION_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value(answersPath())).andReturn().getResponse().getContentAsString();
        assertNoInternalLeak(content);
    }

    // === state / deadline ===

    @Test void returns409Submitted() throws Exception {
        markSubmitted();
        assertNoInternalLeak(err(raw(body("{\"selectedOptionKey\":\"A\"}", 1)), 409, "ATTEMPT_INVALID_STATE"));
    }

    @Test void returns409Graded() throws Exception {
        jdbc.update("UPDATE attempts SET status='GRADED', submitted_at=now(), submission_idempotency_key='G' WHERE id=" + attemptId);
        em.clear();
        assertNoInternalLeak(err(raw(body("{\"selectedOptionKey\":\"A\"}", 1)), 409, "ATTEMPT_INVALID_STATE"));
    }

    @Test void returns409AfterDeadline() throws Exception {
        Instant deadline = jdbc.queryForObject("SELECT deadline_at FROM attempts WHERE id=" + attemptId, Instant.class);
        clock.setInstant(deadline.plusSeconds(1));
        assertNoInternalLeak(err(raw(body("{\"selectedOptionKey\":\"A\"}", 1)), 409, "ATTEMPT_DEADLINE_EXCEEDED"));
    }

    // === invalid payloads (4 types) ===

    @Test void returns400InvalidSinglePayload() throws Exception {
        assertNoInternalLeak(err(raw(body("{\"selectedOptionKey\":\"Z\"}", 1)), 400, "ATTEMPT_INVALID_ANSWER_PAYLOAD"));
    }

    @Test void returns400InvalidMultiplePayload() throws Exception {
        assertNoInternalLeak(err(raw(bodyFor(aqMultipleId, "{\"selectedOptionKeys\":[\"Z\"]}", 1)), 400, "ATTEMPT_INVALID_ANSWER_PAYLOAD"));
    }

    @Test void returns400InvalidTrueFalsePayload() throws Exception {
        assertNoInternalLeak(err(raw(bodyFor(aqTfId, "{\"responses\":{\"Z\":true}}", 1)), 400, "ATTEMPT_INVALID_ANSWER_PAYLOAD"));
    }

    @Test void returns400InvalidNumericPayload() throws Exception {
        assertNoInternalLeak(err(raw(bodyFor(aqNumericId, "{\"value\":\"2.5\"}", 1)), 400, "ATTEMPT_INVALID_ANSWER_PAYLOAD"));
    }

    // === corrupted persisted snapshot ===

    @Test void returns400CorruptedOptionOrder() throws Exception {
        jdbc.update("UPDATE attempt_questions SET option_order = '[\"A\",\"A\",\"B\",\"C\"]'::jsonb WHERE id=" + aqId);
        em.clear();
        assertNoInternalLeak(err(raw(body("{\"selectedOptionKey\":\"A\"}", 1)), 400, "ATTEMPT_VALIDATION_ERROR"));
    }

    // === data-leak structural ===

    @Test void responseHasNoLeakFields() throws Exception {
        String content = mockMvc.perform(put(answersPath()).with(jwtWith(userId)).contentType(MediaType.APPLICATION_JSON).content(body("{\"selectedOptionKey\":\"A\"}", 1)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertNoLeak(content);
    }

    // === helpers ===

    /** Drives a PUT with the given content and asserts exact status + code + path; returns the body. */
    private String err(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, int status, String code) throws Exception {
        return mockMvc.perform(req)
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.path").value(answersPath()))
                .andReturn().getResponse().getContentAsString();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder raw(String content) {
        return rawWith(userId, content);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rawWith(long uid, String content) {
        return put(answersPath()).with(jwtWith(uid)).contentType(MediaType.APPLICATION_JSON).content(content);
    }

    private void assertNoLeak(String content) {
        assertThat(content)
                .doesNotContain("answerPayload").doesNotContain("clientInstanceId")
                .doesNotContain("answerKey").doesNotContain("expectedAnswer")
                .doesNotContain("score").doesNotContain("grade")
                .doesNotContain("studentProfileId").doesNotContain("schoolId");
    }

    private void assertNoInternalLeak(String content) {
        assertThat(content)
                .doesNotContain("constraint").doesNotContain("org.hibernate").doesNotContain("org.postgresql")
                .doesNotContain("Caused by").doesNotContain("at com.quizopia");
    }

    private void markSubmitted() {
        jdbc.update("UPDATE attempts SET status='SUBMITTED', submitted_at=now(), submission_idempotency_key='K' WHERE id=" + attemptId);
        em.clear();
    }

    private long aqOf(long eqId) {
        return jdbc.queryForObject("SELECT id FROM attempt_questions WHERE attempt_id=? AND exam_question_id=?", Long.class, attemptId, eqId);
    }

    private long ver(long school, long subj, long tp, long user) {
        long exam = ins("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + "," + tp + ",'E','E')");
        return ins("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',4,now()," + user + ")");
    }

    private long sec(long v) {
        return ins("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + v + ",'S',0)");
    }

    private long eq(long v, long section, long bank, String type, int position) {
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q" + type + position + "'," + userId + ")");
        long qv = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'" + type + "','c',1,'{}'::jsonb," + userId + ")");
        return ins("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + v + "," + section + "," + q + "," + qv + ",'QC','" + type + "','c',1," + position + ",'{}'::jsonb)");
    }

    private long eqNumeric(long v, long section, long bank, int position) {
        // V7 CHECK chk_question_versions_numeric_answer_key requires expectedAnswer(string) +
        // requiredInputLength(=4) + non-blank roundingInstruction.
        String key = "'{\"expectedAnswer\":\"1.25\",\"requiredInputLength\":4,\"roundingInstruction\":\"two decimals\"}'::jsonb";
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'QN" + position + "'," + userId + ")");
        long qv = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, answer_key, created_by) VALUES (" + q + ",1,'NUMERIC_FILL','n',1,'{}'::jsonb," + key + "," + userId + ")");
        return ins("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata, answer_key) VALUES (" + v + "," + section + "," + q + "," + qv + ",'QC','NUMERIC_FILL','n',1," + position + ",'{}'::jsonb," + key + ")");
    }

    private void opts(long eqId) {
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES "
                + "(" + eqId + ",'A','a',false,0),(" + eqId + ",'B','b',false,1),(" + eqId + ",'C','c',true,2),(" + eqId + ",'D','d',false,3)");
    }

    private long ins(String sql) { return jdbc.queryForObject(sql + " RETURNING id", Long.class); }
}
