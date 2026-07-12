package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.api.ResultController;
import com.quizopia.backend.attempt.dto.AttemptResultResponse;
import com.quizopia.backend.attempt.exception.AttemptErrorCode;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.attempt.application.SessionResultService;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 8 R4 controller-level authorization evidence (FINDING 1A). Drives the REAL {@link ResultController}
 * bean with a hand-crafted {@link Authentication} + {@link Jwt} whose authorities are deliberately
 * MISMATCHED against the JWT {@code roles} claim. This proves authorization is driven by the effective
 * Authentication authorities (via {@code EffectiveRoleResolver.resolve(authentication.getAuthorities())}),
 * NOT by the JWT {@code roles} claim — and that unsupported/null authorities are fail-closed.
 *
 * <p>These tests do NOT call the service layer with a {@code String} role; they call the controller,
 * which itself resolves the role from authorities exactly as it does in production.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class ResultControllerAuthorityIntegrationTests {

    @Autowired private ResultController resultController;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private long teacherUserId;
    private long otherTeacherUserId;
    private long sessionId;
    private long studentUserId;
    private long studentAttemptId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        Instant now = Instant.parse("2026-07-06T08:00:00Z");
        clock.setInstant(now);
        long[] chain = teacherOwnedSession("T" + tag, now);
        teacherUserId = chain[0];
        sessionId = chain[1];
        long schoolId = chain[2];
        long verId = chain[3];
        // A second (non-owning) teacher
        otherTeacherUserId = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('ot" + tag + "','ot" + tag + "@t.com','h','OT')");
        long tr = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + otherTeacherUserId + "," + tr + ")");
        ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + otherTeacherUserId + "," + schoolId + ",'OTC" + tag + "')");
        // A student with a submitted attempt on this session
        studentUserId = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('stu" + tag + "','stu" + tag + "@t.com','h','S')");
        long sr = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + studentUserId + "," + sr + ")");
        long sp = ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + studentUserId + "," + schoolId + ",'SC" + tag + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + schoolId + "," + sessionId + "," + sp + "," + teacherUserId + ")");
        studentAttemptId = ins("INSERT INTO attempts (school_id, exam_session_id, student_profile_id, exam_version_id, attempt_number, status, started_at, deadline_at, submitted_at, submission_idempotency_key) VALUES (" + schoolId + "," + sessionId + "," + sp + "," + verId + ",1,'SUBMITTED','" + now.minusSeconds(1800) + "','" + now.plusSeconds(1800) + "','" + now.minusSeconds(60) + "','K" + tag + "')");
        jdbc.update("INSERT INTO grades (attempt_id, automatic_score, final_score, max_score, percentage, status, graded_at) VALUES (" + studentAttemptId + ",0,0,1,0.0000,'AUTO_GRADED','" + now.minusSeconds(60) + "')");
    }

    // 1. authorities=ROLE_TEACHER, JWT roles claim EMPTY → owning teacher ALLOWED
    @Test
    void owningTeacherAllowedEvenWithEmptyJwtRolesClaim() {
        Jwt jwt = jwtFor(teacherUserId, List.of()); // empty roles claim
        Authentication auth = auth(new SimpleGrantedAuthority("ROLE_TEACHER"));
        var page = resultController.getSessionResults(jwt, auth, sessionId, 0, 20, null, null, null, null, null);
        assertThat(page).isNotNull();
    }

    // 2. JWT roles claim = [SYSTEM_ADMIN] but effective authority only ROLE_STUDENT → session-wide DENIED
    @Test
    void jwtSystemAdminClaimIgnoredWhenEffectiveAuthorityIsStudent() {
        Jwt jwt = jwtFor(otherTeacherUserId, List.of("SYSTEM_ADMIN")); // misleading claim
        Authentication auth = auth(new SimpleGrantedAuthority("ROLE_STUDENT"));
        assertThatThrownBy(() -> resultController.getSessionResults(jwt, auth, sessionId, 0, 20, null, null, null, null, null))
                .isInstanceOf(AttemptException.class)
                .extracting(e -> ((AttemptException) e).getErrorCode())
                .isEqualTo(AttemptErrorCode.SESSION_RESULTS_ACCESS_DENIED);
    }

    // 3. JWT roles claim = [TEACHER] but effective authority has NO supported role → DENIED
    @Test
    void jwtTeacherClaimIgnoredWhenEffectiveAuthorityUnsupported() {
        Jwt jwt = jwtFor(otherTeacherUserId, List.of("TEACHER")); // misleading claim
        Authentication auth = auth(new SimpleGrantedAuthority("ROLE_GUEST")); // unsupported authority
        assertThatThrownBy(() -> resultController.getStatistics(jwt, auth, sessionId))
                .isInstanceOf(AttemptException.class)
                .extracting(e -> ((AttemptException) e).getErrorCode())
                .isEqualTo(AttemptErrorCode.SESSION_RESULTS_ACCESS_DENIED);
    }

    // 4. Unsupported authority (fail-closed)
    @Test
    void unsupportedAuthorityFailsClosed() {
        Jwt jwt = jwtFor(otherTeacherUserId, List.of("TEACHER"));
        Authentication auth = auth(new SimpleGrantedAuthority("ROLE_AUDITOR"));
        assertThatThrownBy(() -> resultController.getStatistics(jwt, auth, sessionId))
                .isInstanceOf(AttemptException.class)
                .extracting(e -> ((AttemptException) e).getErrorCode())
                .isEqualTo(AttemptErrorCode.SESSION_RESULTS_ACCESS_DENIED);
    }

    // 5. ROLE_SYSTEM_ADMIN → ALLOWED
    @Test
    void systemAdminAllowed() {
        Jwt jwt = jwtFor(otherTeacherUserId, List.of());
        Authentication auth = auth(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));
        var stats = resultController.getStatistics(jwt, auth, sessionId);
        assertThat(stats).isNotNull();
    }

    // 6. ROLE_ACADEMIC_ADMIN → ALLOWED (frozen MVP scope)
    @Test
    void academicAdminAllowed() {
        Jwt jwt = jwtFor(otherTeacherUserId, List.of());
        Authentication auth = auth(new SimpleGrantedAuthority("ROLE_ACADEMIC_ADMIN"));
        SessionResultService.SessionResultsPage page = resultController.getSessionResults(jwt, auth, sessionId, 0, 20, null, null, null, null, null);
        assertThat(page).isNotNull();
    }

    // 7. STUDENT own attempt → ALLOWED (via getAttemptResult)
    @Test
    void studentOwnAttemptAllowed() {
        Jwt jwt = jwtFor(studentUserId, List.of("STUDENT"));
        Authentication auth = auth(new SimpleGrantedAuthority("ROLE_STUDENT"));
        AttemptResultResponse r = resultController.getAttemptResult(jwt, auth, studentAttemptId);
        assertThat(r).isNotNull();
    }

    // 8. STUDENT other's attempt → DENIED
    @Test
    void studentOtherAttemptDenied() {
        // Create a second student's attempt
        long otherStudent = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('os','os@t.com','h','OS')");
        long sr = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + otherStudent + "," + sr + ")");
        long schoolId = jdbc.queryForObject("SELECT school_id FROM exam_sessions WHERE id = " + sessionId, Long.class);
        long verId = jdbc.queryForObject("SELECT exam_version_id FROM exam_sessions WHERE id = " + sessionId, Long.class);
        long osp = ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + otherStudent + "," + schoolId + ",'OSC')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + schoolId + "," + sessionId + "," + osp + "," + teacherUserId + ")");
        long otherAttempt = ins("INSERT INTO attempts (school_id, exam_session_id, student_profile_id, exam_version_id, attempt_number, status, started_at, deadline_at, submitted_at, submission_idempotency_key) VALUES (" + schoolId + "," + sessionId + "," + osp + "," + verId + ",1,'SUBMITTED',now()-interval '1 hour',now()+interval '1 hour',now()-interval '30 min','OK')");
        jdbc.update("INSERT INTO grades (attempt_id, automatic_score, final_score, max_score, percentage, status, graded_at) VALUES (" + otherAttempt + ",0,0,1,0.0000,'AUTO_GRADED',now())");

        // First student (studentUserId) tries to read other student's attempt → DENIED
        Jwt jwt = jwtFor(studentUserId, List.of("STUDENT"));
        Authentication auth = auth(new SimpleGrantedAuthority("ROLE_STUDENT"));
        assertThatThrownBy(() -> resultController.getAttemptResult(jwt, auth, otherAttempt))
                .isInstanceOf(AttemptException.class)
                .extracting(e -> ((AttemptException) e).getErrorCode())
                .isEqualTo(AttemptErrorCode.RESULT_ACCESS_DENIED);
    }

    // === helpers ===

    private Jwt jwtFor(long userId, List<String> rolesClaim) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(String.valueOf(userId))
                .claim("roles", rolesClaim)
                .issuedAt(Instant.parse("2026-07-06T08:00:00Z"))
                .expiresAt(Instant.parse("2026-07-06T09:00:00Z"))
                .build();
    }

    private Authentication auth(GrantedAuthority... authorities) {
        return new UsernamePasswordAuthenticationToken("principal", "creds", List.of(authorities));
    }

    private long[] teacherOwnedSession(String tag, Instant now) {
        long teacher = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('" + tag + "','" + tag + "@t.com','h','T')");
        long tr = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + teacher + "," + tr + ")");
        long school = ins("INSERT INTO schools (code, name) VALUES ('SC" + tag + "','S')");
        long gl = ins("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
        long subj = ins("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','M')");
        long tp = ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacher + "," + school + ",'TC" + tag + "')");
        long bank = ins("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + "," + tp + ",'B','B')");
        long exam = ins("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + "," + tp + ",'E','E')");
        long ver = ins("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',1,now()," + teacher + ")");
        long sec = ins("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q'," + teacher + ")");
        long qv = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + teacher + ")");
        jdbc.update("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        long session = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES (" + school + "," + ver + "," + tp + ",'S" + tag + "','S','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',5," + teacher + ",'" + now.minusSeconds(3600) + "')");
        return new long[]{teacher, session, school, ver};
    }

    private long ins(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
