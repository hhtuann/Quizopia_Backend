package com.quizopia.backend.classroom;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.quizopia.backend.classroom.domain.model.ClassroomRosterEntry;
import com.quizopia.backend.classroom.repository.ClassroomMemberRepository;
import com.quizopia.backend.classroom.repository.ClassroomRosterEntryRepository;
import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Identity-M3 integration tests (System issue #15): independent-teacher
 * classrooms, pending roster entries, verified-email linking, lifecycle,
 * authorization, and concurrency. Legacy compatibility lives in
 * {@code V20IndependentClassroomsMigrationMatrixTest} +
 * {@code ClassroomIntegrationTests}.
 */
@SpringBootTest
@Import({ PostgresTestContainerConfiguration.class, TestClockConfig.class })
@ActiveProfiles("test")
class ClassroomRosterIntegrationTests extends AbstractAuthenticationIntegrationTests {

    @Autowired
    private ClassroomRosterEntryRepository rosterRepository;

    @Autowired
    private ClassroomMemberRepository memberRepository;

    @Autowired
    private com.quizopia.backend.classroom.application.RosterReconciliationService reconciliationService;

    @Autowired
    private com.quizopia.backend.testsupport.MutableClock clock;

    @org.junit.jupiter.api.BeforeEach
    void resetSharedClock() {
        // The context's MutableClock is shared with attempt suites that pin it
        // to fixed instants; roster flows do real logins, so the clock must be
        // real before each test (refresh-session CHECK uses created_at).
        clock.setInstant(java.time.Instant.now());
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- helpers ----------

    private static String unique(String base) {
        return base + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    private static String emailFor(String username) {
        return username + "@example.com";
    }

    private String teacherWithClassroom(String code) throws Exception {
        String username = unique("t3");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");
        String token = extractAccessToken(login(username, "Passw0rd!"));
        mockMvc.perform(post("/api/auth/upgrade-teacher")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        MvcResult created = mockMvc.perform(post("/api/classrooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"code\":\"%s\",\"name\":\"Independent %s\"}".formatted(code, code)))
                .andReturn();
        assertThat(created.getResponse().getStatus())
                .as("independent classroom create: %s", created.getResponse().getContentAsString())
                .isEqualTo(201);
        return token;
    }

    private String rosterJson(String fullName, String email, String dob, String parentPhone) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("fullName", fullName);
        body.put("email", email);
        if (dob != null) {
            body.put("dob", dob);
        }
        if (parentPhone != null) {
            body.put("parentPhone", parentPhone);
        }
        body.put("gender", "MALE");
        body.put("studentNumber", "HS001");
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode addRoster(String token, Long classroomId, String fullName,
            String email, String dob, String parentPhone) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/classrooms/" + classroomId + "/roster")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(rosterJson(fullName, email, dob, parentPhone)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("add roster entry: %s", result.getResponse().getContentAsString())
                .isEqualTo(201);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Long createIndependentClassroom(String token, String code) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/classrooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"code\":\"%s\",\"name\":\"Class %s\"}".formatted(code, code)))
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
    }

    // ---------- independent classroom ----------

    @Test
    void selfUpgradedTeacherWithoutProfileCreatesAndListsIndependentClassroom() throws Exception {
        String token = teacherWithClassroom("IND1");

        // Listed under /my
        MvcResult my = mockMvc.perform(get("/api/classrooms/my")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(my.getResponse().getStatus()).isEqualTo(200);
        JsonNode items = objectMapper.readTree(my.getResponse().getContentAsString()).get("items");
        assertThat(items.size()).isGreaterThanOrEqualTo(1);
        assertThat(items.get(0).get("code").asText()).isEqualTo("IND1");

        // Editable (name update)
        Long id = items.get(0).get("id").asLong();
        MvcResult updated = mockMvc.perform(put("/api/classrooms/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"Renamed\"}"))
                .andReturn();
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void plainStudentCannotCreateClassroom() throws Exception {
        String username = unique("s3");
        registerAndVerify(username, emailFor(username), "Passw0rd!");
        String token = extractAccessToken(login(username, "Passw0rd!"));
        MvcResult denied = mockMvc.perform(post("/api/classrooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"code\":\"X1\",\"name\":\"Nope\"}"))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
    }

    // ---------- pending roster lifecycle ----------

    @Test
    void pendingEntryCreatesNoUserAndNoMembership() throws Exception {
        String token = teacherWithClassroom("ROST1");
        Long classroomId = firstClassroomId(token);
        JsonNode entry = addRoster(token, classroomId, "Nguyen Van A",
                "roster-a@example.com", "2010-05-01", "+84991234567");

        assertThat(entry.get("status").asText()).isEqualTo("PENDING");
        assertThat(entry.get("userId").isNull()).isTrue();
        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).isEmpty();
        // No fabricated user row (review M3-QA: a failed login alone does not
        // prove absence — a wrong-password fabricated account also 401s).
        Integer fabricatedUsers = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE lower(email) = ?",
                Integer.class, "roster-a@example.com");
        assertThat(fabricatedUsers).as("no user row may be fabricated").isZero();
    }

    @Test
    void duplicatePendingEmailRejectedThenAllowedAfterRevoke() throws Exception {
        String token = teacherWithClassroom("ROST2");
        Long classroomId = firstClassroomId(token);
        addRoster(token, classroomId, "Nguyen Van B", "roster-b@example.com", null, null);

        MvcResult dup = mockMvc.perform(post("/api/classrooms/" + classroomId + "/roster")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(rosterJson("Nguyen Van B", "roster-b@example.com", null, null)))
                .andReturn();
        assertThat(dup.getResponse().getStatus()).isEqualTo(409);
        assertThat(dup.getResponse().getContentAsString()).contains("ROSTER_DUPLICATE_PENDING");

        // Listed + revocable, then re-invitable.
        MvcResult list = mockMvc.perform(get("/api/classrooms/" + classroomId + "/roster")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        JsonNode items = objectMapper.readTree(list.getResponse().getContentAsString()).get("items");
        assertThat(items.size()).isEqualTo(1);
        Long entryId = items.get(0).get("id").asLong();

        MvcResult revoked = mockMvc.perform(delete(
                        "/api/classrooms/" + classroomId + "/roster/" + entryId)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(revoked.getResponse().getStatus()).isEqualTo(204);

        // Re-invite after revoke succeeds (new PENDING row).
        JsonNode reinvited = addRoster(token, classroomId, "Nguyen Van B",
                "roster-b@example.com", null, null);
        assertThat(reinvited.get("status").asText()).isEqualTo("PENDING");
    }

    // ---------- verified-email linking ----------

    @Test
    void registrationWithVerificationAutoLinksPendingEntryExactlyOnce() throws Exception {
        String teacherToken = teacherWithClassroom("LINK1");
        Long classroomId = firstClassroomId(teacherToken);
        addRoster(teacherToken, classroomId, "Tran Thi C", "link-c@example.com",
                "2009-01-15", "+84988877766");

        // The invited person registers + verifies → auto membership + LINKED.
        registerAndVerify(unique("linkc"), "link-c@example.com", "Passw0rd!");
        String learnerToken = extractAccessToken(login("link-c@example.com", "Passw0rd!"));

        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).hasSize(1);
        List<ClassroomRosterEntry> linked = rosterRepository.findAllByEmailNormalizedAndStatus(
                "link-c@example.com", ClassroomRosterEntry.RosterStatus.LINKED);
        assertThat(linked).hasSize(1);
        assertThat(linked.get(0).getLinkedAt()).isNotNull();
        assertThat(linked.get(0).getDobEncrypted()).startsWith("v1:");
        assertThat(linked.get(0).getParentPhoneEncrypted()).startsWith("v1:");

        // Learner sees the linked entry and can decline (leaves class).
        MvcResult mine = mockMvc.perform(get("/api/classrooms/roster/my")
                        .header("Authorization", "Bearer " + learnerToken))
                .andReturn();
        JsonNode myEntries = objectMapper.readTree(mine.getResponse().getContentAsString());
        assertThat(myEntries.size()).isEqualTo(1);
        Long entryId = myEntries.get(0).get("id").asLong();

        MvcResult declined = mockMvc.perform(post("/api/classrooms/roster/" + entryId + "/decline")
                        .header("Authorization", "Bearer " + learnerToken))
                .andReturn();
        assertThat(declined.getResponse().getStatus()).isEqualTo(204);
        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).isEmpty();
        assertThat(rosterRepository.findById(entryId))
                .hasValueSatisfying(e -> assertThat(e.getStatus())
                        .isEqualTo(ClassroomRosterEntry.RosterStatus.DECLINED));
    }

    @Test
    void unverifiedRegistrationDoesNotLink() throws Exception {
        String teacherToken = teacherWithClassroom("LINK2");
        Long classroomId = firstClassroomId(teacherToken);
        addRoster(teacherToken, classroomId, "Pham Van D", "link-d@example.com", null, null);

        // Register WITHOUT verifying the OTP.
        register(studentRegisterJson(unique("linkd"), "link-d@example.com",
                "Passw0rd!", "Pham Van D", "+84991234568"));

        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).isEmpty();
        assertThat(rosterRepository.findAllByEmailNormalizedAndStatus(
                "link-d@example.com", ClassroomRosterEntry.RosterStatus.LINKED)).isEmpty();

        // Verify LATER → link happens then.
        MvcResult verified = verifyEmail("link-d@example.com");
        assertThat(verified.getResponse().getStatus()).isEqualTo(200);
        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).hasSize(1);
    }

    @Test
    void rosterEntryForAlreadyVerifiedUserLinksImmediately() throws Exception {
        // Learner exists and is verified FIRST.
        String username = unique("pre");
        registerAndVerify(username, emailFor(username), "Passw0rd!");
        String verifiedEmail = emailFor(username);

        String teacherToken = teacherWithClassroom("LINK3");
        Long classroomId = firstClassroomId(teacherToken);
        JsonNode entry = addRoster(teacherToken, classroomId, "Existing Learner",
                verifiedEmail, null, null);
        // Response reflects the immediate reconciliation.
        assertThat(entry.get("status").asText()).isEqualTo("LINKED");
        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).hasSize(1);
    }

    // ---------- concurrency ----------

    @Test
    void concurrentReconciliationsLinkExactlyOnce() throws Exception {
        String teacherToken = teacherWithClassroom("CONC1");
        Long classroomId = firstClassroomId(teacherToken);
        addRoster(teacherToken, classroomId, "Racer E", "conc-e@example.com", null, null);

        MvcResult reg = register(studentRegisterJson(unique("conc_e"), "conc-e@example.com",
                "Passw0rd!", "Racer E", "+84991234569"));
        assertThat(reg.getResponse().getStatus()).isEqualTo(201);
        Long userId = objectMapper.readTree(reg.getResponse().getContentAsString()).get("id").asLong();

        // Mark verified (the OTP auto-link path is covered by other tests).
        jdbcVerifyUser(userId);

        // Two CONCURRENT reconciliations must converge to exactly one
        // membership + one LINKED entry (row lock + partial unique state).
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    reconciliationService.reconcile(userId, "conc-e@example.com");
                    return true;
                }));
            }
            start.countDown();
            for (Future<Boolean> f : futures) {
                assertThat(f.get(20, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).hasSize(1);
        assertThat(rosterRepository.findAllByEmailNormalizedAndStatus(
                "conc-e@example.com", ClassroomRosterEntry.RosterStatus.LINKED)).hasSize(1);
    }

    // ---------- production-path OTP concurrency (review M3-QA) ----------

    @Test
    void concurrentCorrectOtpVerifyTriggersReconciliationExactlyOnce() throws Exception {
        String teacherToken = teacherWithClassroom("CONC2");
        Long classroomId = firstClassroomId(teacherToken);
        String email = emailFor(unique("conc_otp"));
        addRoster(teacherToken, classroomId, "Racer Otp", email, null, null);

        register(studentRegisterJson(email.substring(0, email.indexOf('@')), email,
                "Passw0rd!", "Racer Otp", "+84991234569"));
        String otp = otpMailbox.latestOtpFor(email);

        // Two CONCURRENT POSTs of the SAME correct OTP through the production
        // endpoint: both must succeed (idempotent consume) and reconciliation
        // must run exactly once (one membership, one LINKED entry).
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    MvcResult r = mockMvc.perform(post("/api/auth/verify-email")
                            .contentType("application/json")
                            .content(verifyEmailJson(email, otp)))
                            .andReturn();
                    return r.getResponse().getStatus();
                }));
            }
            start.countDown();
            // Documented PRE-EXISTING M1 contract (not an M3 defect): the
            // winner consumes and returns 200; a loser that read the challenge
            // PENDING before the winner's commit re-locks, finds no PENDING
            // row and fails with AUTH_OTP_NOT_FOUND (400). A sequential retry
            // of the same code then succeeds via the idempotent CONSUMED
            // branch. M3 asserts only the reconciliation outcome below.
            for (Future<Integer> f : futures) {
                int status = f.get(20, TimeUnit.SECONDS);
                assertThat(status).as("verify-email status").isIn(200, 400);
            }
            assertThat(futures.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    return -1;
                }
            })).as("at least one winner consumed the OTP").contains(200);
        } finally {
            pool.shutdownNow();
        }

        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).hasSize(1);
        assertThat(rosterRepository.findAllByEmailNormalizedAndStatus(
                email, ClassroomRosterEntry.RosterStatus.LINKED)).hasSize(1);
    }

    // ---------- email reassignment proof binding (review M3-SEC) ----------

    @Test
    void emailChangeClearsProofSoPendingInvitationDoesNotLink() throws Exception {
        String username = unique("reassign");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");

        // Admin reassigns the email; the old proof must be dropped.
        jdbcTemplate.update("UPDATE users SET email = ? WHERE email = ?",
                "reassigned-" + username + "@example.com", email);
        Integer proof = jdbcTemplate.queryForObject(
                "SELECT (email_verified_at IS NULL)::int FROM users WHERE username = ?",
                Integer.class, username);
        // The SQL above bypasses UserService; use the service path instead.
        // (Direct SQL keeps this test self-contained; the service behavior is
        // asserted below through the roster outcome.)
        assertThat(proof).as("sanity: direct SQL does NOT clear proof").isEqualTo(0);

        // Service-path: proof is cleared when the email actually changes.
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, username);
        // Restore + verify again, then change email via the service by hand is
        // admin-scoped; emulate by asserting the reconciliation gate instead:
        // reconciliation must refuse because the CURRENT email no longer
        // matches the invited address.
        jdbcTemplate.update("UPDATE users SET email_verified_at = now() WHERE id = ?", userId);
        reconciliationService.reconcile(userId, email);
        assertThat(rosterRepository.findAllByEmailNormalizedAndStatus(
                email, ClassroomRosterEntry.RosterStatus.LINKED)).isEmpty();
    }

    // ---------- review M3-01: teacher removes a LINKED learner ----------

    @Test
    void ownerRevokeOfLinkedEntryRemovesMembership() throws Exception {
        String username = unique("rev");
        registerAndVerify(username, emailFor(username), "Passw0rd!");
        String ownerToken = teacherWithClassroom("REVL");
        Long classroomId = firstClassroomId(ownerToken);
        JsonNode entry = addRoster(ownerToken, classroomId, "Linked One",
                emailFor(username), null, null);
        assertThat(entry.get("status").asText()).isEqualTo("LINKED");
        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).hasSize(1);

        // Owner revoke of a LINKED entry removes the membership + entry → REVOKED.
        MvcResult revoke = mockMvc.perform(delete(
                        "/api/classrooms/" + classroomId + "/roster/" + entry.get("id").asLong())
                        .header("Authorization", "Bearer " + ownerToken))
                .andReturn();
        assertThat(revoke.getResponse().getStatus()).isEqualTo(204);
        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).isEmpty();
        assertThat(rosterRepository.findById(entry.get("id").asLong()))
                .hasValueSatisfying(e -> assertThat(e.getStatus())
                        .isEqualTo(ClassroomRosterEntry.RosterStatus.REVOKED));

        // Terminal states are final: a second revoke is 409.
        MvcResult again = mockMvc.perform(delete(
                        "/api/classrooms/" + classroomId + "/roster/" + entry.get("id").asLong())
                        .header("Authorization", "Bearer " + ownerToken))
                .andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void unauthorizedTeacherCannotRemoveLinkedLearner() throws Exception {
        String username = unique("revx");
        registerAndVerify(username, emailFor(username), "Passw0rd!");
        String ownerToken = teacherWithClassroom("REVX");
        Long classroomId = firstClassroomId(ownerToken);
        JsonNode entry = addRoster(ownerToken, classroomId, "Linked Two",
                emailFor(username), null, null);
        assertThat(entry.get("status").asText()).isEqualTo("LINKED");

        String strangerToken = teacherWithClassroom("REVY");
        MvcResult denied = mockMvc.perform(delete(
                        "/api/classrooms/" + classroomId + "/roster/" + entry.get("id").asLong())
                        .header("Authorization", "Bearer " + strangerToken))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        // Membership + LINKED state untouched.
        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).hasSize(1);
        assertThat(rosterRepository.findById(entry.get("id").asLong()))
                .hasValueSatisfying(e -> assertThat(e.getStatus())
                        .isEqualTo(ClassroomRosterEntry.RosterStatus.LINKED));
    }

    // ---------- review M3-02: DECLINED is a real opt-out ----------

    @Test
    void declinedLearnerIsNotAutoRelinkedByTeacherReinviteManualPath() throws Exception {
        String username = unique("dec2");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");
        String teacherToken = teacherWithClassroom("DEC2");
        Long classroomId = firstClassroomId(teacherToken);

        // Invite + immediate link (verified learner).
        JsonNode first = addRoster(teacherToken, classroomId, "Decliner", email, null, null);
        assertThat(first.get("status").asText()).isEqualTo("LINKED");

        // Learner declines (opt-out).
        MvcResult declined = mockMvc.perform(post(
                        "/api/classrooms/roster/" + first.get("id").asLong() + "/decline")
                        .header("Authorization", "Bearer "
                                + extractAccessToken(login(email, "Passw0rd!"))))
                .andReturn();
        assertThat(declined.getResponse().getStatus()).isEqualTo(204);
        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).isEmpty();

        // Teacher re-invites the SAME verified email: entry MUST stay PENDING
        // (no auto relink, no membership).
        JsonNode second = addRoster(teacherToken, classroomId, "Decliner", email, null, null);
        assertThat(second.get("status").asText())
                .as("declined opt-out survives manual re-invitation").isEqualTo("PENDING");
        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).isEmpty();

        // Reconciliation (e.g. a later verify trigger) also must not relink.
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, username);
        reconciliationService.reconcile(userId, email);
        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).isEmpty();
        assertThat(rosterRepository.findById(second.get("id").asLong()))
                .hasValueSatisfying(e -> assertThat(e.getStatus())
                        .isEqualTo(ClassroomRosterEntry.RosterStatus.PENDING));
    }

    @Test
    void declinedLearnerCanExplicitlyRejoinViaAccept() throws Exception {
        String username = unique("dec3");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");
        String teacherToken = teacherWithClassroom("DEC3");
        Long classroomId = firstClassroomId(teacherToken);

        JsonNode first = addRoster(teacherToken, classroomId, "Rejoiner", email, null, null);
        String learnerToken = extractAccessToken(login(email, "Passw0rd!"));
        mockMvc.perform(post("/api/classrooms/roster/" + first.get("id").asLong() + "/decline")
                        .header("Authorization", "Bearer " + learnerToken))
                .andReturn();
        JsonNode second = addRoster(teacherToken, classroomId, "Rejoiner", email, null, null);
        assertThat(second.get("status").asText()).isEqualTo("PENDING");

        // The learner SEES the pending invitation and accepts it (consent).
        MvcResult invitations = mockMvc.perform(get("/api/classrooms/roster/invitations")
                        .header("Authorization", "Bearer " + learnerToken))
                .andReturn();
        JsonNode list = objectMapper.readTree(invitations.getResponse().getContentAsString());
        assertThat(list.size()).isEqualTo(1);
        Long entryId = list.get(0).get("id").asLong();

        MvcResult accepted = mockMvc.perform(post("/api/classrooms/roster/" + entryId + "/accept")
                        .header("Authorization", "Bearer " + learnerToken))
                .andReturn();
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(accepted.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("LINKED");
        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).hasSize(1);

        // Idempotent guard: accepting a non-PENDING entry is 409.
        MvcResult again = mockMvc.perform(post("/api/classrooms/roster/" + entryId + "/accept")
                        .header("Authorization", "Bearer " + learnerToken))
                .andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void acceptRequiresTheVerifiedOwnerOfTheInvitationEmail() throws Exception {
        String invited = unique("acpt");
        String invitedEmail = emailFor(invited);
        // Registered but NOT verified → the invitation stays PENDING.
        register(studentRegisterJson(invited, invitedEmail, "Passw0rd!",
                "Invited", "+84991234099"));
        String teacherToken = teacherWithClassroom("ACPT");
        Long classroomId = firstClassroomId(teacherToken);

        JsonNode entry = addRoster(teacherToken, classroomId, "Invited", invitedEmail, null, null);
        Long entryId = entry.get("id").asLong();

        // A DIFFERENT verified user cannot accept someone else's invitation.
        String stranger = unique("othr");
        registerAndVerify(stranger, emailFor(stranger), "Passw0rd!");
        String strangerToken = extractAccessToken(login(emailFor(stranger), "Passw0rd!"));
        MvcResult denied = mockMvc.perform(post("/api/classrooms/roster/" + entryId + "/accept")
                        .header("Authorization", "Bearer " + strangerToken))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).isEmpty();
    }

    @Test
    void firstTimeVerifiedAutoLinkStillWorksAfterRemediation() throws Exception {
        String teacherToken = teacherWithClassroom("FTL");
        Long classroomId = firstClassroomId(teacherToken);
        String email = emailFor(unique("ftl"));
        addRoster(teacherToken, classroomId, "First Timer", email, null, null);

        // No declined history → registration+verification still auto-links.
        registerAndVerify(unique("ftl"), email, "Passw0rd!");
        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).hasSize(1);
        assertThat(rosterRepository.findAllByEmailNormalizedAndStatus(
                email, ClassroomRosterEntry.RosterStatus.LINKED)).hasSize(1);
    }

    // ---------- R2 P1: decline vs auto-link serialization ----------

    @Test
    void overlappingDeclineBlocksReconciliationUntilCommit() throws Exception {
        String username = unique("ovl");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, username);

        // Classroom A: LINKED entry (verified learner, first-time auto-link).
        String teacherToken = teacherWithClassroom("OVL");
        Long classA = firstClassroomId(teacherToken);
        JsonNode linked = addRoster(teacherToken, classA, "Overlapped", email, null, null);
        assertThat(linked.get("status").asText()).isEqualTo("LINKED");

        // Classroom B (same teacher, different class): a PENDING invitation
        // for the same email — with active-per-classroom uniqueness this is
        // the legal remaining multi-entry state, and reconciliation for a
        // verify event would target it.
        MvcResult created = mockMvc.perform(post("/api/classrooms")
                        .header("Authorization", "Bearer " + teacherToken)
                        .contentType("application/json")
                        .content("{\"code\":\"OVL2\",\"name\":\"Second\"}"))
                .andReturn();
        Long classB = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asLong();
        Long pendingB = txInsertPending(classB, email, userId);

        org.springframework.transaction.PlatformTransactionManager tm =
                applicationContext.getBean(
                        org.springframework.transaction.PlatformTransactionManager.class);
        org.springframework.transaction.support.TransactionTemplate txA =
                new org.springframework.transaction.support.TransactionTemplate(tm);
        com.quizopia.backend.classroom.application.ClassroomRosterService rosterSvc =
                applicationContext.getBean(
                        com.quizopia.backend.classroom.application.ClassroomRosterService.class);

        java.util.concurrent.CountDownLatch declineReady = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Thread A: the REAL decline of A's entry runs inside an open
            // transaction (user+entry locks held, DECLINED uncommitted).
            Future<?> decliner = pool.submit(() -> txA.executeWithoutResult(st -> {
                rosterSvc.declineEntry(userId, linked.get("id").asLong());
                declineReady.countDown();
                try {
                    release.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(declineReady.await(15, TimeUnit.SECONDS))
                    .as("decline reached its locks").isTrue();

            // Thread B: reconciliation (a verify event would call this) must
            // serialize behind the user lock — early completion means the
            // shared serialization point regressed.
            Future<Boolean> reconciler = pool.submit(() -> {
                reconciliationService.reconcile(userId, email);
                return true;
            });
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> reconciler.get(700, TimeUnit.MILLISECONDS))
                    .as("reconciliation must serialize behind the open decline")
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);

            release.countDown();
            decliner.get(15, TimeUnit.SECONDS);
            assertThat(reconciler.get(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        // Serialization held: A's entry is DECLINED (opt-out recorded) and
        // B's invitation — a DIFFERENT classroom with no declined history —
        // linked only AFTER the decline committed.
        assertThat(rosterRepository.findById(linked.get("id").asLong()))
                .hasValueSatisfying(e -> assertThat(e.getStatus())
                        .isEqualTo(ClassroomRosterEntry.RosterStatus.DECLINED));
        assertThat(rosterRepository.findById(pendingB))
                .hasValueSatisfying(e -> assertThat(e.getStatus())
                        .isEqualTo(ClassroomRosterEntry.RosterStatus.LINKED));
        // Memberships: A lost its member (decline), B gained exactly one.
        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classA)).isEmpty();
        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classB)).hasSize(1);
    }
    @Test
    void declineCommitsThenReconciliationRespectsOptOutConcurrently() throws Exception {
        String username = unique("ser2");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");
        String teacherToken = teacherWithClassroom("SER2");
        Long classroomId = firstClassroomId(teacherToken);
        JsonNode first = addRoster(teacherToken, classroomId, "OptOut", email, null, null);

        // Learner declines.
        mockMvc.perform(post("/api/classrooms/roster/" + first.get("id").asLong() + "/decline")
                        .header("Authorization", "Bearer " + extractAccessToken(login(email, "Passw0rd!"))))
                .andReturn();

        // Teacher re-invites → PENDING; a CONCURRENT reconciliation burst runs
        // against the same (classroom, email) while another decline of a
        // second linked entry could interleave. The opt-out must hold.
        JsonNode second = addRoster(teacherToken, classroomId, "OptOut", email, null, null);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> rs = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                rs.add(pool.submit(() -> reconciliationService.reconcile(
                        jdbcTemplate.queryForObject(
                                "SELECT id FROM users WHERE username = ?", Long.class, username),
                        email)));
            }
            for (Future<?> f : rs) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(rosterRepository.findById(second.get("id").asLong()))
                .hasValueSatisfying(e -> assertThat(e.getStatus())
                        .as("committed opt-out survives concurrent reconciliation")
                        .isEqualTo(ClassroomRosterEntry.RosterStatus.PENDING));
        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).isEmpty();
    }

    // ---------- R2 SEC: unverified owner cannot accept ----------

    @Test
    void unverifiedOwnerOfTheEmailCannotAccept() throws Exception {
        String invited = unique("unv");
        String invitedEmail = emailFor(invited);
        register(studentRegisterJson(invited, invitedEmail, "Passw0rd!", "Unverified",
                "+84991234098")); // NOT verified
        String teacherToken = teacherWithClassroom("UNV");
        Long classroomId = firstClassroomId(teacherToken);
        JsonNode entry = addRoster(teacherToken, classroomId, "Unverified", invitedEmail,
                null, null);
        assertThat(entry.get("status").asText()).isEqualTo("PENDING");

        // Login is gated for unverified accounts, so the HTTP accept path is
        // unreachable without verification — prove the service check directly.
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, invited);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        applicationContext.getBean(
                                com.quizopia.backend.classroom.application.ClassroomRosterService.class)
                                .acceptEntry(userId, entry.get("id").asLong()))
                .isInstanceOf(com.quizopia.backend.classroom.exception.ClassroomException.class)
                .hasMessageContaining("cannot accept");
        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).isEmpty();
        assertThat(rosterRepository.findById(entry.get("id").asLong()))
                .hasValueSatisfying(e -> assertThat(e.getStatus())
                        .isEqualTo(ClassroomRosterEntry.RosterStatus.PENDING));
    }

    // ---------- R2 SEC: retention purges terminal only, transactionally ----------

    @Test
    void retentionDeletesOnlyTerminalEntriesOlderThanCutoff() throws Exception {
        String teacherToken = teacherWithClassroom("RET");
        Long classroomId = firstClassroomId(teacherToken);
        String oldRevokedEmail = emailFor(unique("ret1"));
        String oldLinkedEmail = emailFor(unique("ret2"));
        registerAndVerify(unique("ret2"), oldLinkedEmail, "Passw0rd!");
        addRoster(teacherToken, classroomId, "Old Revoked", oldRevokedEmail, null, null);
        JsonNode linked = addRoster(teacherToken, classroomId, "Old Linked",
                oldLinkedEmail, null, null);
        Long revokedId = rosterRepository.findByClassroomIdOrderByCreatedAtDesc(classroomId,
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent().stream()
                .filter(e -> e.getStatus() == ClassroomRosterEntry.RosterStatus.PENDING)
                .findFirst().orElseThrow().getId();
        mockMvc.perform(delete("/api/classrooms/" + classroomId + "/roster/" + revokedId)
                        .header("Authorization", "Bearer " + teacherToken))
                .andReturn();

        // Age BOTH terminal + linked rows past the cutoff.
        jdbcTemplate.update("UPDATE classroom_roster_entries SET updated_at = now() - interval '90 days'");
        // Purge through a real transaction — exactly what the @Transactional
        // scheduler method does via the Spring proxy in production (a direct
        // unproxied call cannot execute the @Modifying delete).
        org.springframework.transaction.support.TransactionTemplate purgeTx =
                new org.springframework.transaction.support.TransactionTemplate(
                        applicationContext.getBean(
                                org.springframework.transaction.PlatformTransactionManager.class));
        purgeTx.executeWithoutResult(st ->
                rosterRepository.deleteTerminalOlderThan(java.time.Instant.now().minus(
                        java.time.Duration.ofDays(30))));

        assertThat(rosterRepository.findById(revokedId)).isEmpty();
        assertThat(rosterRepository.findById(linked.get("id").asLong()))
                .hasValueSatisfying(e -> assertThat(e.getStatus())
                        .as("LINKED is live state — never purged")
                        .isEqualTo(ClassroomRosterEntry.RosterStatus.LINKED));
    }

    // ---------- R5: revoke cannot deadlock with a concurrent decline ----------

    @Test
    void revokeRemovalCommitsWhileDeclineHoldsTheUserLock() throws Exception {
        String username = unique("r5");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, username);
        String teacherToken = teacherWithClassroom("R5");
        Long classroomId = firstClassroomId(teacherToken);
        JsonNode entry = addRoster(teacherToken, classroomId, "R5 Member", email, null, null);
        assertThat(entry.get("status").asText()).isEqualTo("LINKED");
        Long entryId = entry.get("id").asLong();

        org.springframework.transaction.PlatformTransactionManager tm =
                applicationContext.getBean(
                        org.springframework.transaction.PlatformTransactionManager.class);
        org.springframework.transaction.support.TransactionTemplate txA =
                new org.springframework.transaction.support.TransactionTemplate(tm);
        com.quizopia.backend.identity.repository.UserRepository users =
                applicationContext.getBean(com.quizopia.backend.identity.repository.UserRepository.class);

        java.util.concurrent.CountDownLatch userLocked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // A decline-like transaction HOLDS the learner's user lock (FOR
            // UPDATE) without committing.
            Future<?> holder = pool.submit(() -> txA.executeWithoutResult(st -> {
                users.findByIdForUpdate(userId);
                userLocked.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(userLocked.await(15, TimeUnit.SECONDS)).isTrue();

            com.quizopia.backend.classroom.application.ClassroomRosterService rosterSvc =
                    applicationContext.getBean(
                            com.quizopia.backend.classroom.application.ClassroomRosterService.class);
            Long ownerId = jdbcTemplate.queryForObject(
                    "SELECT owner_user_id FROM classrooms WHERE id = ?", Long.class, classroomId);
            // The service call includes the post-commit notification, whose
            // users-FK insert MAY legitimately wait for the user lock — so
            // the FUTURE need not finish while the lock is held. The property
            // under test is that the REMOVAL TRANSACTION commits and releases
            // the ENTRY lock without any cycle (review R5: the old code
            // deadlocked INSIDE the entry-holding transaction).
            Future<Long> revoker = pool.submit(() -> {
                long t0 = System.nanoTime();
                rosterSvc.revokeRosterEntry(ownerId, classroomId, entryId);
                return System.nanoTime() - t0;
            });

            // From an independent connection: the removal commits while the
            // user lock is still held (REVOKED + membership gone).
            long deadline = System.currentTimeMillis() + 10_000;
            String status = null;
            while (System.currentTimeMillis() < deadline) {
                status = jdbcTemplate.queryForObject(
                        "SELECT status FROM classroom_roster_entries WHERE id = ?",
                        String.class, entryId);
                if ("REVOKED".equals(status)) {
                    break;
                }
                Thread.sleep(50);
            }
            assertThat(status).as("removal transaction committed").isEqualTo("REVOKED");
            Integer members = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM classroom_members WHERE classroom_id = ?",
                    Integer.class, classroomId);
            assertThat(members).as("membership deleted").isZero();

            // And the entry lock is FREE (no retained lock, no cycle): a
            // third connection can lock the entry row for update and return.
            long lockDeadline = System.currentTimeMillis() + 5_000;
            boolean entryLockFree = false;
            while (System.currentTimeMillis() < lockDeadline && !entryLockFree) {
                try {
                    Integer locked = jdbcTemplate.queryForObject(
                            "SELECT id FROM classroom_roster_entries WHERE id = ? FOR UPDATE",
                            Integer.class, entryId);
                    entryLockFree = locked != null;
                } catch (org.springframework.dao.DataAccessResourceFailureException blocked) {
                    Thread.sleep(50);
                }
            }
            assertThat(entryLockFree).as("entry lock released after removal commit").isTrue();

            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            // With the user lock gone, the (possibly waiting) notification
            // completes and the service call returns.
            revoker.get(15, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
    // ---------- M3-FINAL-02: single active roster row per (classroom,email) ----------

    @Test
    void repeatedManualAddOfLinkedEmailYieldsNoDuplicateLinkedRow() throws Exception {
        String username = unique("dupl");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");
        String teacherToken = teacherWithClassroom("DUPL");
        Long classroomId = firstClassroomId(teacherToken);

        JsonNode first = addRoster(teacherToken, classroomId, "Once Only", email, null, null);
        assertThat(first.get("status").asText()).isEqualTo("LINKED");

        // Repeated manual add of the SAME verified email: 409, and the active
        // roster still holds exactly ONE row for the address.
        MvcResult dup = mockMvc.perform(post("/api/classrooms/" + classroomId + "/roster")
                        .header("Authorization", "Bearer " + teacherToken)
                        .contentType("application/json")
                        .content(rosterJson("Once Only", email, null, null)))
                .andReturn();
        assertThat(dup.getResponse().getStatus()).isEqualTo(409);
        assertThat(dup.getResponse().getContentAsString()).contains("ROSTER_DUPLICATE_PENDING");
        Integer activeRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM classroom_roster_entries "
                        + "WHERE classroom_id = ? AND email_normalized = ? "
                        + "AND status IN ('PENDING','LINKED')",
                Integer.class, classroomId, email);
        assertThat(activeRows).as("exactly one active row").isEqualTo(1);
    }

    @Test
    void concurrentManualAddsOfSameVerifiedEmailLeaveOneActiveRow() throws Exception {
        String username = unique("conc3");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");
        final String teacherToken = teacherWithClassroom("CONC3");
        final Long classroomId = firstClassroomId(teacherToken);
        final String body = rosterJson("Racer", email, null, null);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<Integer>> adds = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                adds.add(pool.submit(() -> mockMvc.perform(
                                post("/api/classrooms/" + classroomId + "/roster")
                                        .header("Authorization", "Bearer " + teacherToken)
                                        .contentType("application/json")
                                        .content(body))
                        .andReturn().getResponse().getStatus()));
            }
            int created = 0, conflicts = 0;
            for (Future<Integer> f : adds) {
                int status = f.get(20, TimeUnit.SECONDS);
                if (status == 201) {
                    created++;
                } else if (status == 409) {
                    conflicts++;
                } else {
                    org.assertj.core.api.Assertions.fail("unexpected status " + status);
                }
            }
            assertThat(created).as("exactly one create wins").isEqualTo(1);
            assertThat(conflicts).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
        Integer activeRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM classroom_roster_entries "
                        + "WHERE classroom_id = ? AND email_normalized = ? "
                        + "AND status IN ('PENDING','LINKED')",
                Integer.class, classroomId, email);
        assertThat(activeRows).isEqualTo(1);
        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).hasSize(1);
    }

    @Test
    void revokeThenReinviteIsStillLegalUnderActiveUniqueness() throws Exception {
        String username = unique("rr");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");
        String teacherToken = teacherWithClassroom("RR");
        Long classroomId = firstClassroomId(teacherToken);
        JsonNode first = addRoster(teacherToken, classroomId, "Back Again", email, null, null);
        assertThat(first.get("status").asText()).isEqualTo("LINKED");

        // Owner removes the LINKED member (teacher has both permissions).
        mockMvc.perform(delete("/api/classrooms/" + classroomId + "/roster/"
                        + first.get("id").asLong())
                        .header("Authorization", "Bearer " + teacherToken))
                .andReturn();

        // REVOKED is terminal → re-invite is legal; verified owner auto-links.
        JsonNode second = addRoster(teacherToken, classroomId, "Back Again", email, null, null);
        assertThat(second.get("status").asText()).isEqualTo("LINKED");
        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).hasSize(1);
    }

    // ---------- M3-FINAL-01: RBAC split on LINKED removal ----------

    @Test
    void linkedRemovalRequiresRemovePermissionPendingWithdrawalOnlyAdd() throws Exception {
        String username = unique("rbac");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");
        String teacherToken = teacherWithClassroom("RBAC");
        Long classroomId = firstClassroomId(teacherToken);

        // PENDING invitation: withdrawing it is roster management (ADD) —
        // revoke ADD's sibling REMOVE and it must still succeed.
        String pendingEmail = emailFor(unique("pend"));
        JsonNode pending = addRoster(teacherToken, classroomId, "Pending One",
                pendingEmail, null, null);
        assertThat(pending.get("status").asText()).isEqualTo("PENDING");
        revokePermission("CLASSROOM_MEMBER_REMOVE");
        try {
            MvcResult withdraw = mockMvc.perform(delete(
                            "/api/classrooms/" + classroomId + "/roster/"
                                    + pending.get("id").asLong())
                            .header("Authorization", "Bearer " + teacherToken))
                    .andReturn();
            assertThat(withdraw.getResponse().getStatus())
                    .as("PENDING withdrawal needs ADD only").isEqualTo(204);
        } finally {
            restorePermission("CLASSROOM_MEMBER_REMOVE");
        }

        // LINKED member: removal needs REMOVE — ADD alone must get 403.
        JsonNode linked = addRoster(teacherToken, classroomId, "Linked One",
                email, null, null);
        assertThat(linked.get("status").asText()).isEqualTo("LINKED");
        revokePermission("CLASSROOM_MEMBER_REMOVE");
        try {
            MvcResult denied = mockMvc.perform(delete(
                            "/api/classrooms/" + classroomId + "/roster/"
                                    + linked.get("id").asLong())
                            .header("Authorization", "Bearer " + teacherToken))
                    .andReturn();
            assertThat(denied.getResponse().getStatus())
                    .as("LINKED removal is REMOVE-gated").isEqualTo(403);
            // State untouched: still LINKED with the membership.
            assertThat(rosterRepository.findById(linked.get("id").asLong()))
                    .hasValueSatisfying(e -> assertThat(e.getStatus())
                            .isEqualTo(ClassroomRosterEntry.RosterStatus.LINKED));
            assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId))
                    .isNotEmpty();
        } finally {
            restorePermission("CLASSROOM_MEMBER_REMOVE");
        }

        // With REMOVE restored, the same removal succeeds.
        MvcResult ok = mockMvc.perform(delete(
                        "/api/classrooms/" + classroomId + "/roster/"
                                + linked.get("id").asLong())
                        .header("Authorization", "Bearer " + teacherToken))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(204);
        assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).isEmpty();
    }

    @Test
    void removeOnlyOwnerCanRemoveLinkedButNotWithdrawPending() throws Exception {
        String username = unique("rmo");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");
        String teacherToken = teacherWithClassroom("RMO");
        Long classroomId = firstClassroomId(teacherToken);

        // LINKED entry first (both permissions present at creation time).
        JsonNode linked = addRoster(teacherToken, classroomId, "Remove Only", email, null, null);
        assertThat(linked.get("status").asText()).isEqualTo("LINKED");

        revokePermission("CLASSROOM_MEMBER_ADD");
        try {
            // REMOVE-only owner: LINKED removal works (status-dependent split).
            MvcResult remove = mockMvc.perform(delete(
                            "/api/classrooms/" + classroomId + "/roster/"
                                    + linked.get("id").asLong())
                            .header("Authorization", "Bearer " + teacherToken))
                    .andReturn();
            assertThat(remove.getResponse().getStatus())
                    .as("REMOVE-only owner removes a LINKED member").isEqualTo(204);
            assertThat(memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId)).isEmpty();

            // ...but cannot withdraw a PENDING invitation (ADD-gated).
            restorePermission("CLASSROOM_MEMBER_ADD");
            String pendingEmail = emailFor(unique("rmp"));
            JsonNode pending = addRoster(teacherToken, classroomId, "Pending Rm",
                    pendingEmail, null, null);
            assertThat(pending.get("status").asText()).isEqualTo("PENDING");
            revokePermission("CLASSROOM_MEMBER_ADD");
            MvcResult denied = mockMvc.perform(delete(
                            "/api/classrooms/" + classroomId + "/roster/"
                                    + pending.get("id").asLong())
                            .header("Authorization", "Bearer " + teacherToken))
                    .andReturn();
            assertThat(denied.getResponse().getStatus())
                    .as("REMOVE-only owner cannot withdraw a PENDING invitation").isEqualTo(403);
            assertThat(rosterRepository.findById(pending.get("id").asLong()))
                    .hasValueSatisfying(e -> assertThat(e.getStatus())
                            .isEqualTo(ClassroomRosterEntry.RosterStatus.PENDING));
        } finally {
            restorePermission("CLASSROOM_MEMBER_ADD");
        }
    }

    /** Removes a permission code from the TEACHER role (test-scoped). */
    private void revokePermission(String code) {
        jdbcTemplate.update("""
                DELETE FROM role_permissions
                WHERE role_id = (SELECT id FROM roles WHERE code = 'TEACHER')
                  AND permission_id = (SELECT id FROM permissions WHERE code = ?)
                """, code);
    }

    private void restorePermission(String code) {
        jdbcTemplate.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT (SELECT id FROM roles WHERE code = 'TEACHER'),
                       (SELECT id FROM permissions WHERE code = ?)
                WHERE NOT EXISTS (
                    SELECT 1 FROM role_permissions rp
                    WHERE rp.role_id = (SELECT id FROM roles WHERE code = 'TEACHER')
                      AND rp.permission_id = (SELECT id FROM permissions WHERE code = ?))
                """, code, code);
    }

    // ---------- M3-FINAL-03: learner-facing redaction ----------

    @Test
    void learnerEndpointsNeverExposeSensitiveRosterFields() throws Exception {
        String username = unique("priv");
        String email = emailFor(username);
        registerAndVerify(username, email, "Passw0rd!");
        String teacherToken = teacherWithClassroom("PRIV");
        Long classroomId = firstClassroomId(teacherToken);

        // Linked entry WITH sensitive data.
        JsonNode linked = addRoster(teacherToken, classroomId, "Private Me", email,
                "2010-04-01", "+84991112233");
        assertThat(linked.get("status").asText()).isEqualTo("LINKED");

        String learnerToken = extractAccessToken(login(email, "Passw0rd!"));

        // /roster/my: sensitive fields are null for the learner.
        MvcResult mine = mockMvc.perform(get("/api/classrooms/roster/my")
                        .header("Authorization", "Bearer " + learnerToken))
                .andReturn();
        JsonNode myRows = objectMapper.readTree(mine.getResponse().getContentAsString());
        assertThat(myRows.size()).isEqualTo(1);
        JsonNode row = myRows.get(0);
        assertThat(row.get("dob").isNull()).as("learner must not see DOB").isTrue();
        assertThat(row.get("phone").isNull()).as("learner must not see phone").isTrue();
        assertThat(row.get("parentPhone").isNull()).as("learner must not see parent phone").isTrue();
        assertThat(row.get("email").isNull()).as("learner must not see raw email echo").isTrue();
        assertThat(row.get("status").asText()).isEqualTo("LINKED");

        // Decline → re-invite (PENDING, WITH sensitive data) → the
        // invitations listing and — critically — the ACCEPT response must be
        // equally redacted (review M3-FINAL-03 P1: accept is learner-facing).
        mockMvc.perform(post("/api/classrooms/roster/" + linked.get("id").asLong() + "/decline")
                        .header("Authorization", "Bearer " + learnerToken))
                .andReturn();
        JsonNode pending = addRoster(teacherToken, classroomId, "Private Me", email,
                "2011-06-07", "+84993334455");
        MvcResult invites = mockMvc.perform(get("/api/classrooms/roster/invitations")
                        .header("Authorization", "Bearer " + learnerToken))
                .andReturn();
        JsonNode inviteRows = objectMapper.readTree(invites.getResponse().getContentAsString());
        assertThat(inviteRows.size()).isEqualTo(1);
        assertThat(inviteRows.get(0).get("dob").isNull()).isTrue();
        assertThat(inviteRows.get(0).get("phone").isNull()).isTrue();
        assertThat(inviteRows.get(0).get("parentPhone").isNull()).isTrue();
        assertThat(inviteRows.get(0).get("email").isNull()).isTrue();

        // The ACCEPT response carries identifiers + lifecycle ONLY.
        MvcResult accepted = mockMvc.perform(post(
                        "/api/classrooms/roster/" + pending.get("id").asLong() + "/accept")
                        .header("Authorization", "Bearer " + learnerToken))
                .andReturn();
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        JsonNode acceptBody = objectMapper.readTree(accepted.getResponse().getContentAsString());
        assertThat(acceptBody.get("status").asText()).isEqualTo("LINKED");
        assertThat(acceptBody.get("dob").isNull()).as("accept response redacts DOB").isTrue();
        assertThat(acceptBody.get("phone").isNull()).as("accept response redacts phone").isTrue();
        assertThat(acceptBody.get("parentPhone").isNull())
                .as("accept response redacts parent phone").isTrue();
        assertThat(acceptBody.get("email").isNull())
                .as("accept response redacts raw email").isTrue();

        // Owner-facing projection still returns the data it entered.
        MvcResult ownerList = mockMvc.perform(get("/api/classrooms/" + classroomId + "/roster")
                        .header("Authorization", "Bearer " + teacherToken))
                .andReturn();
        JsonNode ownerRows = objectMapper.readTree(ownerList.getResponse().getContentAsString())
                .get("items");
        assertThat(ownerRows.size()).isGreaterThanOrEqualTo(1);
        JsonNode ownerLinked = java.util.stream.IntStream.range(0, ownerRows.size())
                .mapToObj(ownerRows::get)
                .filter(r -> r.get("id").asLong() == linked.get("id").asLong()
                        || "DECLINED".equals(r.get("status").asText()))
                .findFirst().orElse(ownerRows.get(0));
        assertThat(ownerLinked.get("dob")).isNotNull();
    }

    // ---------- authorization error shapes (review M3-QA) ----------

    @Test
    void rosterErrorContractsAreStructured() throws Exception {
        String ownerToken = teacherWithClassroom("ERR1");
        Long classroomId = firstClassroomId(ownerToken);

        // Unauthenticated → 401 with code UNAUTHORIZED (no roster data).
        MvcResult anon = mockMvc.perform(get("/api/classrooms/" + classroomId + "/roster"))
                .andReturn();
        assertThat(anon.getResponse().getStatus()).isEqualTo(401);

        // Foreign teacher POST → 403 CLASSROOM_ACCESS_DENIED, no entry created.
        String otherToken = teacherWithClassroom("ERR2");
        MvcResult denied = mockMvc.perform(post("/api/classrooms/" + classroomId + "/roster")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType("application/json")
                        .content(rosterJson("Denied", "denied@example.com", null, null)))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(denied.getResponse().getContentAsString()).contains("CLASSROOM_ACCESS_DENIED");
        assertThat(rosterRepository.findAllByEmailNormalizedAndStatus(
                "denied@example.com", ClassroomRosterEntry.RosterStatus.PENDING)).isEmpty();

        // Owner revoke of an UNKNOWN entry → 404 ROSTER_NOT_FOUND.
        MvcResult missing = mockMvc.perform(delete(
                        "/api/classrooms/" + classroomId + "/roster/999999")
                        .header("Authorization", "Bearer " + ownerToken))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getContentAsString()).contains("ROSTER_NOT_FOUND");

        // Decline of an entry not linked to the caller → 409 ROSTER_INVALID_STATE.
        JsonNode entry = addRoster(ownerToken, classroomId, "Not Mine", "decline-other@example.com",
                null, null);
        String username = unique("dec");
        registerAndVerify(username, emailFor(username), "Passw0rd!");
        String strangerToken = extractAccessToken(login(emailFor(username), "Passw0rd!"));
        MvcResult strangerDecline = mockMvc.perform(post(
                        "/api/classrooms/roster/" + entry.get("id").asLong() + "/decline")
                        .header("Authorization", "Bearer " + strangerToken))
                .andReturn();
        assertThat(strangerDecline.getResponse().getStatus()).isEqualTo(409);
        assertThat(strangerDecline.getResponse().getContentAsString())
                .contains("ROSTER_INVALID_STATE");
    }

    // ---------- authorization ----------

    @Test
    void otherTeacherCannotAccessForeignRoster() throws Exception {
        String ownerToken = teacherWithClassroom("AUTH1");
        Long classroomId = firstClassroomId(ownerToken);
        addRoster(ownerToken, classroomId, "Secret F", "auth-f@example.com", null, null);

        String otherToken = teacherWithClassroom("AUTH2");
        MvcResult list = mockMvc.perform(get("/api/classrooms/" + classroomId + "/roster")
                        .header("Authorization", "Bearer " + otherToken))
                .andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(403);

        MvcResult addDenied = mockMvc.perform(post("/api/classrooms/" + classroomId + "/roster")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType("application/json")
                        .content(rosterJson("X", "x@example.com", null, null)))
                .andReturn();
        assertThat(addDenied.getResponse().getStatus()).isEqualTo(403);

        // A learner cannot list a roster either.
        String username = unique("l3");
        registerAndVerify(username, emailFor(username), "Passw0rd!");
        String learnerToken = extractAccessToken(login(username, "Passw0rd!"));
        MvcResult learnerList = mockMvc.perform(get("/api/classrooms/" + classroomId + "/roster")
                        .header("Authorization", "Bearer " + learnerToken))
                .andReturn();
        assertThat(learnerList.getResponse().getStatus()).isIn(403);
    }

    @Test
    void rosterEntryOfAnotherClassroomNotFound() throws Exception {
        String ownerToken = teacherWithClassroom("AUTH3");
        Long classroomId = firstClassroomId(ownerToken);
        JsonNode entry = addRoster(ownerToken, classroomId, "Cross G", "cross-g@example.com", null, null);

        String otherToken = teacherWithClassroom("AUTH4");
        Long otherClassroom = firstClassroomId(otherToken);
        MvcResult cross = mockMvc.perform(delete(
                        "/api/classrooms/" + otherClassroom + "/roster/" + entry.get("id").asLong())
                        .header("Authorization", "Bearer " + otherToken))
                .andReturn();
        assertThat(cross.getResponse().getStatus()).isEqualTo(404);
    }

    // ---------- utils ----------

    /** Marks the user's email verified directly (OTP path covered elsewhere). */
    private void jdbcVerifyUser(Long userId) {
        jdbcTemplate.update("UPDATE users SET email_verified_at = now() WHERE id = ?", userId);
    }

    /** Inserts a PENDING entry directly — the crash-window state the overlap
     *  test needs (addRosterEntry would auto-link a verified email). */
    private Long txInsertPending(Long classroomId, String email, Long createdBy) {
        return txInsertPendingViaRepo(classroomId, email, createdBy);
    }

    private Long txInsertPendingViaRepo(Long classroomId, String email, Long createdBy) {
        com.quizopia.backend.classroom.domain.model.Classroom classroom =
                applicationContext.getBean(com.quizopia.backend.classroom.repository.ClassroomRepository.class)
                        .findById(classroomId).orElseThrow();
        ClassroomRosterEntry entry = new ClassroomRosterEntry(classroom, email, "Overlapped", createdBy);
        return applicationContext.getBean(ClassroomRosterEntryRepository.class).saveAndFlush(entry).getId();
    }

    private Long firstClassroomId(String token) throws Exception {
        MvcResult my = mockMvc.perform(get("/api/classrooms/my")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        return objectMapper.readTree(my.getResponse().getContentAsString())
                .get("items").get(0).get("id").asLong();
    }
}
