package com.quizopia.backend.authentication.application;


import com.quizopia.backend.authentication.dto.AccountType;
import com.quizopia.backend.authentication.dto.ClientContext;
import com.quizopia.backend.authentication.dto.RegisterRequest;
import com.quizopia.backend.authentication.dto.RegisterResponse;
import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.identity.domain.model.Role;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.domain.model.UserRole;
import com.quizopia.backend.identity.repository.RoleRepository;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.identity.repository.UserRoleRepository;
import com.quizopia.backend.security.encryption.SensitiveDataEncryptor;
import com.quizopia.backend.security.password.PasswordHasher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Public account registration (Identity-M1, ADR-002 §1).
 *
 * <p>Every new account is STUDENT: the public form no longer offers a role
 * choice and a client-supplied {@code accountType=TEACHER} is rejected before
 * any write. The user row is created with {@code email_verified_at = NULL};
 * an OTP challenge is issued in the same transaction and its delivery result
 * is reported so the UI can offer an immediate resend. Login stays blocked
 * until the OTP is verified (see {@link EmailVerificationService}).
 *
 * <p>Password is hashed with Argon2id, phone is encrypted with AES-256-GCM
 * (only ciphertext stored), and a personal workspace is provisioned
 * transactionally and idempotently (ADR-001). Concurrent registrations of the
 * same username/email are settled by the DB unique indexes and mapped to 409.
 */
@Service
public class RegistrationService {

    static final String STUDENT_ROLE_CODE = "STUDENT";
    private static final String USERNAME_UNIQUE_INDEX = "uk_users_username_ci";
    private static final String EMAIL_UNIQUE_INDEX = "uk_users_email_ci";

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final PasswordHasher passwordHasher;
    private final SensitiveDataEncryptor encryptor;

    private final EmailVerificationService emailVerificationService;
    private final TransactionTemplate tx;

    public RegistrationService(UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RoleRepository roleRepository,
            PasswordHasher passwordHasher,
            SensitiveDataEncryptor encryptor,
            EmailVerificationService emailVerificationService,
            TransactionTemplate tx) {

        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.passwordHasher = passwordHasher;
        this.encryptor = encryptor;

        this.emailVerificationService = emailVerificationService;
        this.tx = tx;
    }

    /**
     * NOT {@code @Transactional} (review M1-07): account persistence runs in
     * one short {@link TransactionTemplate} transaction; OTP delivery is
     * invoked only AFTER that commit (EmailVerificationService delivers
     * outside any database transaction), so an SMTP outage can neither roll
     * back the registration nor hold the transaction open.
     */
    public RegisterResponse register(RegisterRequest request, ClientContext client) {
        String username = trimmed(request.username());
        String email = trimmed(request.email());
        String displayName = trimmed(request.displayName());
        String phone = trimmed(request.phone());

        if (username.contains("@")) {
            throw new AuthenticationException(AuthErrorCode.AUTH_VALIDATION_ERROR,
                    "Username must not contain '@'");
        }

        // Identity-M1: public registration is STUDENT-only. The client cannot
        // self-select TEACHER (or any other role) on the public form; teacher
        // access is granted by the authenticated self-upgrade flow (M2).
        AccountType accountType = request.accountType() == null ? AccountType.STUDENT : request.accountType();
        if (accountType != AccountType.STUDENT) {
            throw new AuthenticationException(AuthErrorCode.AUTH_TEACHER_REGISTRATION_DISABLED);
        }

        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new AuthenticationException(AuthErrorCode.AUTH_USERNAME_ALREADY_EXISTS);
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new AuthenticationException(AuthErrorCode.AUTH_EMAIL_ALREADY_EXISTS);
        }

        String passwordHash = passwordHasher.hash(request.password());
        String phoneEncrypted = encryptor.encrypt(phone);

        // Review R3-M1-02: the OTP-issuance IP rate limit is evaluated
        // BEFORE any persistence so a denied registration leaves NO user row
        // (previously the account committed first and the later 429 made the
        // caller believe the registration failed while the email/username
        // were already taken).
        emailVerificationService.assertRegistrationIssuanceAllowed(
                client == null ? null : client.remoteAddress());

        User user;
        try {
            user = tx.execute(status -> persistStudentAccount(
                    username, email, displayName, phone, passwordHash, phoneEncrypted));
        } catch (DataIntegrityViolationException race) {
            // Two concurrent registrations of the same username/email: the
            // unique lower() indexes decide the winner; the loser gets 409.
            throw translateUniqueViolation(race);
        }

        // Identity-M1: email OTP verification, issued and delivered strictly
        // after the account commit. Delivery is best-effort; a failure never
        // blocks the registration and never verifies the account — the resend
        // endpoint is the recovery flow.
        boolean emailSent = emailVerificationService.issueForNewRegistration(
                user, client == null ? null : client.remoteAddress());

        return new RegisterResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getDisplayName(),
                phone,
                user.getStatus(),
                List.of(STUDENT_ROLE_CODE),
                true,
                emailSent);
    }

    private User persistStudentAccount(String username, String email, String displayName,
            String phone, String passwordHash, String phoneEncrypted) {
        User user = new User(username, email, passwordHash, displayName);
        user.setLocalCredentialState(com.quizopia.backend.identity.domain.model.LocalCredentialState.ENABLED);
        user.setPhoneEncrypted(phoneEncrypted);
        user = userRepository.saveAndFlush(user);

        Role role = roleRepository.findByCode(STUDENT_ROLE_CODE)
                .orElseThrow(() -> new IllegalStateException(
                        "Required role '" + STUDENT_ROLE_CODE + "' is not seeded; check Flyway V3"));
        userRoleRepository.save(new UserRole(user, role, null, null));

        return user;
    }

    /**
     * Maps a unique-index violation raised by a concurrent registration to the
     * matching 409 error code. Falls back to the email error when the exact
     * index cannot be identified (both are conflict responses).
     */
    private static AuthenticationException translateUniqueViolation(DataIntegrityViolationException ex) {
        String message = String.valueOf(ex.getMostSpecificCause());
        if (message.contains(USERNAME_UNIQUE_INDEX)) {
            return new AuthenticationException(AuthErrorCode.AUTH_USERNAME_ALREADY_EXISTS);
        }
        return new AuthenticationException(AuthErrorCode.AUTH_EMAIL_ALREADY_EXISTS);
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }
}
