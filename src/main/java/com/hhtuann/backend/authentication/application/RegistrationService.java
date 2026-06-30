package com.hhtuann.backend.authentication.application;

import com.hhtuann.backend.authentication.dto.AccountType;
import com.hhtuann.backend.authentication.dto.RegisterRequest;
import com.hhtuann.backend.authentication.dto.RegisterResponse;
import com.hhtuann.backend.authentication.exception.AuthErrorCode;
import com.hhtuann.backend.authentication.exception.AuthenticationException;
import com.hhtuann.backend.identity.domain.model.Role;
import com.hhtuann.backend.identity.domain.model.User;
import com.hhtuann.backend.identity.domain.model.UserRole;
import com.hhtuann.backend.identity.repository.RoleRepository;
import com.hhtuann.backend.identity.repository.UserRepository;
import com.hhtuann.backend.identity.repository.UserRoleRepository;
import com.hhtuann.backend.security.config.SecurityProperties;
import com.hhtuann.backend.security.encryption.SensitiveDataEncryptor;
import com.hhtuann.backend.security.password.PasswordHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/**
 * Public account registration. Hashes the password with Argon2id, encrypts phone
 * and national identifier with AES-256-GCM (only ciphertext is stored), resolves
 * the foundational role by code, and persists user + role assignment in one
 * transaction. The response echoes the plaintext phone/national-id the caller
 * supplied; the ciphertext, password hash and token version are never returned.
 */
@Service
public class RegistrationService {

    static final String STUDENT_ROLE_CODE = "STUDENT";
    static final String TEACHER_ROLE_CODE = "TEACHER";

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final PasswordHasher passwordHasher;
    private final SensitiveDataEncryptor encryptor;
    private final SecurityProperties properties;

    public RegistrationService(UserRepository userRepository,
                                UserRoleRepository userRoleRepository,
                                RoleRepository roleRepository,
                                PasswordHasher passwordHasher,
                                SensitiveDataEncryptor encryptor,
                                SecurityProperties properties) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.passwordHasher = passwordHasher;
        this.encryptor = encryptor;
        this.properties = properties;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String username = trimmed(request.username());
        String email = trimmed(request.email());
        String displayName = trimmed(request.displayName());
        String phone = trimmed(request.phone());
        String nationalId = trimmed(request.nationalId());

        if (username.contains("@")) {
            throw new AuthenticationException(AuthErrorCode.AUTH_VALIDATION_ERROR,
                    "Username must not contain '@'");
        }

        AccountType accountType = request.accountType() == null ? AccountType.STUDENT : request.accountType();

        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new AuthenticationException(AuthErrorCode.AUTH_USERNAME_ALREADY_EXISTS);
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new AuthenticationException(AuthErrorCode.AUTH_EMAIL_ALREADY_EXISTS);
        }

        String roleCode = switch (accountType) {
            case STUDENT -> STUDENT_ROLE_CODE;
            case TEACHER -> {
                verifyTeacherInvite(request.teacherInviteCode());
                yield TEACHER_ROLE_CODE;
            }
        };

        String passwordHash = passwordHasher.hash(request.password());
        String phoneEncrypted = encryptor.encrypt(phone);
        String nationalIdEncrypted = encryptor.encrypt(nationalId);

        User user = new User(username, email, passwordHash, displayName);
        user.setPhoneEncrypted(phoneEncrypted);
        user.setNationalIdEncrypted(nationalIdEncrypted);
        userRepository.saveAndFlush(user);

        Role role = roleRepository.findByCode(roleCode)
                .orElseThrow(() -> new IllegalStateException(
                        "Required role '" + roleCode + "' is not seeded; check Flyway V3"));

        userRoleRepository.save(new UserRole(user, role, null, null));

        return new RegisterResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getDisplayName(),
                phone,
                nationalId,
                user.getStatus(),
                List.of(role.getCode()));
    }

    /**
     * Verifies the teacher invite code using a constant-time comparison. The
     * expected code is never logged. A blank or mismatched code yields the same
     * {@link AuthErrorCode#AUTH_TEACHER_INVITE_INVALID} error.
     */
    private void verifyTeacherInvite(String provided) {
        Objects.requireNonNull(properties.getTeacherInvite(), "teacher invite config must be present");
        String expected = properties.getTeacherInvite().getCode();
        boolean valid = provided != null
                && !provided.isBlank()
                && MessageDigest.isEqual(
                        provided.getBytes(StandardCharsets.UTF_8),
                        expected.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            throw new AuthenticationException(AuthErrorCode.AUTH_TEACHER_INVITE_INVALID);
        }
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }
}
