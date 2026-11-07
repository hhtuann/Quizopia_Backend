package com.quizopia.backend.authentication;

import com.quizopia.backend.authentication.application.CredentialSecurityService;
import com.quizopia.backend.authentication.application.EmailVerificationService;
import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.repository.EmailVerificationChallengeRepository;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.notification.email.CaptureOtpMailSender;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class EmailVerificationAddressBindingIntegrationTests {
    @Autowired private EmailVerificationService verification;
    @Autowired private CredentialSecurityService credentials;
    @Autowired private UserRepository users;
    @Autowired private EmailVerificationChallengeRepository challenges;
    @Autowired private CaptureOtpMailSender mailbox;
    @Autowired private TransactionTemplate tx;

    @Test
    void staleRegistrationSnapshotCannotIssueForReassignedAddress() {
        User stale = createUser();
        String replacement = UUID.randomUUID() + "@example.com";
        reassign(stale.getId(), replacement);
        assertThat(verification.issueForNewRegistration(stale, null)).isFalse();
        assertThat(mailbox.latestOtpFor(replacement)).isNull();
        assertThat(users.findById(stale.getId()).orElseThrow().getEmail()).isEqualTo(replacement);
    }

    @Test
    void consumedProofCannotSurviveAddressChangedAwayAndBack() {
        User user = createUser();
        String address = user.getEmail();
        assertThat(verification.issueForNewRegistration(user, null)).isTrue();
        String otp = mailbox.latestOtpFor(address);
        verification.verify(address, otp);
        verification.verify(address, otp);
        reassign(user.getId(), UUID.randomUUID() + "@example.com");
        reassign(user.getId(), address);
        AuthenticationException failure = catchThrowableOfType(
                () -> verification.verify(address, otp), AuthenticationException.class);
        assertThat(failure.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_OTP_INVALID);
        assertThat(users.findById(user.getId()).orElseThrow().getEmailVerifiedAt()).isNull();
    }

    @Test
    void pendingProofDeletedDuringAddressChangeCannotVerifyNewAddress() {
        User user = createUser();
        verification.issueForNewRegistration(user, null);
        String otp = mailbox.latestOtpFor(user.getEmail());
        String replacement = UUID.randomUUID() + "@example.com";
        reassign(user.getId(), replacement);
        AuthenticationException failure = catchThrowableOfType(
                () -> verification.verify(replacement, otp), AuthenticationException.class);
        assertThat(failure.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_OTP_INVALID);
    }

    private User createUser() {
        String name = "binding" + UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(status -> users.saveAndFlush(new User(name, name + "@example.com", "unused", name)));
    }

    private void reassign(Long id, String email) {
        tx.executeWithoutResult(status -> {
            User locked = credentials.lockUser(id);
            challenges.deleteAllByUserId(id);
            locked.setEmail(email);
            locked.setEmailVerifiedAt(null);
            users.flush();
        });
    }
}
