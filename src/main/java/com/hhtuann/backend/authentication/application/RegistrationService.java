package com.hhtuann.backend.authentication.application;

import com.hhtuann.backend.academic.application.DemoDataSeeder;
import com.hhtuann.backend.academic.domain.model.School;
import com.hhtuann.backend.academic.domain.model.StudentProfile;
import com.hhtuann.backend.academic.domain.model.TeacherProfile;
import com.hhtuann.backend.academic.repository.SchoolRepository;
import com.hhtuann.backend.academic.repository.StudentProfileRepository;
import com.hhtuann.backend.academic.repository.TeacherProfileRepository;
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
import org.springframework.beans.factory.annotation.Value;
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
    private final SchoolRepository schoolRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final boolean demoEnabled;

    public RegistrationService(UserRepository userRepository,
                                UserRoleRepository userRoleRepository,
                                RoleRepository roleRepository,
                                PasswordHasher passwordHasher,
                                SensitiveDataEncryptor encryptor,
                                SecurityProperties properties,
                                SchoolRepository schoolRepository,
                                TeacherProfileRepository teacherProfileRepository,
                                StudentProfileRepository studentProfileRepository,
                                @Value("${quizopia.demo.data.enabled:false}") boolean demoEnabled) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.passwordHasher = passwordHasher;
        this.encryptor = encryptor;
        this.properties = properties;
        this.schoolRepository = schoolRepository;
        this.teacherProfileRepository = teacherProfileRepository;
        this.studentProfileRepository = studentProfileRepository;
        this.demoEnabled = demoEnabled;
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

        // NOTE: Registration no longer auto-creates academic profiles (V11 Student Onboarding).
        // Students self-register → PENDING (user + STUDENT role, no profile).
        // ACADEMIC_ADMIN assigns them to a school via POST /api/admin/students/{userId}/assign-school
        // which creates the student_profiles row with an auto-generated student_code.
        // DemoDataSeeder writes profiles directly (bypasses registration).

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

    /**
     * Creates the academic profile that matches the account type, attached to
     * the single demo school provisioned by {@link DemoDataSeeder}. Runs only
     * when {@code quizopia.demo.data.enabled=true}. The profile code is derived
     * deterministically from the (globally unique) username, so it is unique
     * within the demo school. Any failure here rolls back the whole
     * user + role + profile transaction.
     */
    private void assignDemoProfile(User user, AccountType accountType) {
        School demoSchool = schoolRepository.findByCodeIgnoreCase(DemoDataSeeder.DEMO_SCHOOL_CODE)
                .orElseThrow(() -> new IllegalStateException(
                        "Demo data not seeded but quizopia.demo.data.enabled=true; "
                                + "ensure DemoDataSeeder ran at startup"));
        Long schoolId = demoSchool.getId();
        if (accountType == AccountType.TEACHER) {
            teacherProfileRepository.saveAndFlush(
                    new TeacherProfile(user.getId(), schoolId, user.getUsername()));
        } else {
            studentProfileRepository.saveAndFlush(
                    new StudentProfile(user.getId(), schoolId, user.getUsername()));
        }
    }
}
