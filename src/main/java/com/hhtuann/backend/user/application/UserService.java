package com.hhtuann.backend.user.application;

import com.hhtuann.backend.academic.application.DemoDataSeeder;
import com.hhtuann.backend.academic.domain.model.School;
import com.hhtuann.backend.academic.domain.model.StudentProfile;
import com.hhtuann.backend.academic.domain.model.TeacherProfile;
import com.hhtuann.backend.academic.repository.SchoolRepository;
import com.hhtuann.backend.academic.repository.StudentProfileRepository;
import com.hhtuann.backend.academic.repository.TeacherProfileRepository;
import com.hhtuann.backend.authentication.dto.AccountType;
import com.hhtuann.backend.identity.domain.model.Role;
import com.hhtuann.backend.identity.domain.model.User;
import com.hhtuann.backend.identity.domain.model.UserRole;
import com.hhtuann.backend.identity.domain.model.UserRoleId;
import com.hhtuann.backend.identity.domain.model.UserStatus;
import com.hhtuann.backend.identity.repository.RoleRepository;
import com.hhtuann.backend.identity.repository.UserRepository;
import com.hhtuann.backend.identity.repository.UserRoleRepository;
import com.hhtuann.backend.question.dto.PageResponse;
import com.hhtuann.backend.security.encryption.SensitiveDataEncryptor;
import com.hhtuann.backend.security.password.PasswordHasher;
import com.hhtuann.backend.user.dto.AssignRoleRequest;
import com.hhtuann.backend.user.dto.CreateUserRequest;
import com.hhtuann.backend.user.dto.RoleListResponse;
import com.hhtuann.backend.user.dto.RoleResponse;
import com.hhtuann.backend.user.dto.UpdateUserRequest;
import com.hhtuann.backend.user.dto.UserListItem;
import com.hhtuann.backend.user.dto.UserResponse;
import com.hhtuann.backend.user.exception.UserErrorCode;
import com.hhtuann.backend.user.exception.UserException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Application service for the user-management API (SYSTEM_ADMIN only). Enforces
 * the active {@code SYSTEM_ADMIN} role at the service layer (deny by default).
 *
 * <p><strong>Security:</strong> response mapping ({@link #toListItem} /
 * {@link #toResponse}) never copies the password hash, encrypted phone/national
 * id, token version or refresh tokens — only the public account metadata.
 *
 * <p>User creation mirrors {@code RegistrationService}: Argon2id password hash,
 * AES-256-GCM encryption of phone/national-id, role assignment, and (in demo
 * mode) the matching academic profile.
 */
@Service
public class UserService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";
    private static final String STUDENT_ROLE_CODE = "STUDENT";
    private static final String TEACHER_ROLE_CODE = "TEACHER";
    private static final long LOCK_MINUTES = 15;

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final PasswordHasher passwordHasher;
    private final SensitiveDataEncryptor encryptor;
    private final SchoolRepository schoolRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final Clock clock;
    private final boolean demoEnabled;

    public UserService(UserRepository userRepository,
                       UserRoleRepository userRoleRepository,
                       RoleRepository roleRepository,
                       PasswordHasher passwordHasher,
                       SensitiveDataEncryptor encryptor,
                       SchoolRepository schoolRepository,
                       TeacherProfileRepository teacherProfileRepository,
                       StudentProfileRepository studentProfileRepository,
                       Clock clock,
                       @Value("${quizopia.demo.data.enabled:false}") boolean demoEnabled) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.passwordHasher = passwordHasher;
        this.encryptor = encryptor;
        this.schoolRepository = schoolRepository;
        this.teacherProfileRepository = teacherProfileRepository;
        this.studentProfileRepository = studentProfileRepository;
        this.clock = clock;
        this.demoEnabled = demoEnabled;
    }

    // ============================================================
    // GET /api/users
    // ============================================================

    @Transactional(readOnly = true)
    public PageResponse<UserListItem> listUsers(Long callerId, String search, UserStatus status, String roleCode, int page, int size) {
        requireSystemAdmin(callerId);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        String query = (search == null || search.isBlank()) ? null : search.trim();
        Page<User> users = userRepository.searchForAdmin(status, query, roleCode, Instant.now(clock), pageable);
        List<UserListItem> items = users.stream().map(this::toListItem).toList();
        return new PageResponse<>(items, users.getNumber(), users.getSize(),
                users.getTotalElements(), users.getTotalPages(), "createdAt: DESC");
    }

    // ============================================================
    // GET /api/users/{id}
    // ============================================================

    @Transactional(readOnly = true)
    public UserResponse getUser(Long callerId, Long targetId) {
        requireSystemAdmin(callerId);
        return toResponse(requireUser(targetId));
    }

    // ============================================================
    // POST /api/users
    // ============================================================

    @Transactional
    public UserResponse createUser(Long callerId, CreateUserRequest request) {
        requireSystemAdmin(callerId);

        String username = request.username().trim();
        String email = request.email().trim();
        if (username.contains("@")) {
            throw new UserException(UserErrorCode.USER_VALIDATION_ERROR, "Username must not contain '@'");
        }
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new UserException(UserErrorCode.USER_USERNAME_ALREADY_EXISTS);
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new UserException(UserErrorCode.USER_EMAIL_ALREADY_EXISTS);
        }

        String roleCode = switch (request.accountType()) {
            case STUDENT -> STUDENT_ROLE_CODE;
            case TEACHER -> TEACHER_ROLE_CODE;
        };

        String passwordHash = passwordHasher.hash(request.password());
        User user = new User(username, email, passwordHash, request.displayName().trim());
        if (request.phone() != null && !request.phone().isBlank()) {
            user.setPhoneEncrypted(encryptor.encrypt(request.phone().trim()));
        }
        if (request.nationalId() != null && !request.nationalId().isBlank()) {
            user.setNationalIdEncrypted(encryptor.encrypt(request.nationalId().trim()));
        }
        userRepository.saveAndFlush(user);

        Role role = roleRepository.findByCode(roleCode)
                .orElseThrow(() -> new IllegalStateException(
                        "Required role '" + roleCode + "' is not seeded; check Flyway V3"));
        userRoleRepository.saveAndFlush(new UserRole(user, role, null, null));

        if (demoEnabled) {
            assignDemoProfile(user, request.accountType());
        }
        return toResponse(user);
    }

    // ============================================================
    // PUT /api/users/{id}
    // ============================================================

    @Transactional
    public UserResponse updateUser(Long callerId, Long targetId, UpdateUserRequest request) {
        requireSystemAdmin(callerId);
        User user = requireUser(targetId);

        if (request.displayName() != null && !request.displayName().isBlank()) {
            user.setDisplayName(request.displayName().trim());
        }
        if (request.email() != null && !request.email().isBlank()) {
            String email = request.email().trim();
            if (!email.equalsIgnoreCase(user.getEmail())
                    && userRepository.existsByEmailIgnoreCase(email)) {
                throw new UserException(UserErrorCode.USER_EMAIL_ALREADY_EXISTS);
            }
            user.setEmail(email);
        }
        userRepository.saveAndFlush(user);
        return toResponse(user);
    }

    // ============================================================
    // Status lifecycle (idempotent)
    // ============================================================

    @Transactional
    public UserResponse activate(Long callerId, Long targetId) {
        requireSystemAdmin(callerId);
        User user = requireUser(targetId);
        user.setStatus(UserStatus.ACTIVE);
        userRepository.saveAndFlush(user);
        return toResponse(user);
    }

    @Transactional
    public UserResponse disable(Long callerId, Long targetId) {
        requireSystemAdmin(callerId);
        User user = requireUser(targetId);
        user.setStatus(UserStatus.DISABLED);
        userRepository.saveAndFlush(user);
        return toResponse(user);
    }

    /** Lock = set {@code lockedUntil} (now + 15 min). Does NOT change UserStatus (mirrors Day-4 lockout). */
    @Transactional
    public UserResponse lock(Long callerId, Long targetId) {
        requireSystemAdmin(callerId);
        User user = requireUser(targetId);
        user.setLockedUntil(Instant.now(clock).plus(LOCK_MINUTES, ChronoUnit.MINUTES));
        userRepository.saveAndFlush(user);
        return toResponse(user);
    }

    /** Unlock = clear {@code lockedUntil} + reset {@code failedLoginAttempts}. */
    @Transactional
    public UserResponse unlock(Long callerId, Long targetId) {
        requireSystemAdmin(callerId);
        User user = requireUser(targetId);
        user.setLockedUntil(null);
        user.setFailedLoginAttempts(0);
        userRepository.saveAndFlush(user);
        return toResponse(user);
    }

    // ============================================================
    // POST /api/users/{id}/roles
    // ============================================================

    @Transactional
    public UserResponse assignRole(Long callerId, Long targetId, AssignRoleRequest request) {
        requireSystemAdmin(callerId);
        User user = requireUser(targetId);
        Role role = roleRepository.findByCode(request.roleCode().trim())
                .orElseThrow(() -> new UserException(UserErrorCode.USER_INVALID_ROLE));
        // Idempotent: a repeat assignment is a no-op (200).
        if (!userRoleRepository.existsById(new UserRoleId(user.getId(), role.getId()))) {
            userRoleRepository.saveAndFlush(new UserRole(user, role, null, null));
        }
        return toResponse(user);
    }

    // ============================================================
    // GET /api/roles
    // ============================================================

    @Transactional(readOnly = true)
    public RoleListResponse listRoles(Long callerId) {
        requireSystemAdmin(callerId);
        List<RoleResponse> items = roleRepository.findAllByOrderByIdAsc().stream()
                .map(r -> new RoleResponse(r.getId(), r.getCode(), r.getName(), r.getDescription()))
                .toList();
        return new RoleListResponse(items);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private User requireUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserException(UserErrorCode.USER_NOT_FOUND));
    }

    /**
     * SYSTEM_ADMIN-only gate. The V3 seed grants the USER_* and ROLE_READ
     * permissions exclusively to SYSTEM_ADMIN, so the role check subsumes the
     * permission check.
     */
    private void requireSystemAdmin(Long userId) {
        List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(userId, Instant.now(clock));
        if (!roles.contains(SYSTEM_ADMIN)) {
            throw new UserException(UserErrorCode.USER_ACCESS_DENIED);
        }
    }

    private List<String> rolesOf(Long userId) {
        return userRoleRepository.findActiveRoleCodesByUserId(userId, Instant.now(clock));
    }

    private UserListItem toListItem(User u) {
        return new UserListItem(u.getId(), u.getUsername(), u.getEmail(), u.getDisplayName(),
                u.getStatus().name(), rolesOf(u.getId()), u.getCreatedAt());
    }

    private UserResponse toResponse(User u) {
        return new UserResponse(u.getId(), u.getUsername(), u.getEmail(), u.getDisplayName(),
                u.getStatus().name(), rolesOf(u.getId()), u.getCreatedAt(),
                u.getLockedUntil(), u.getLastLoginAt());
    }

    private void assignDemoProfile(User user, AccountType accountType) {
        School demoSchool = schoolRepository.findByCodeIgnoreCase(DemoDataSeeder.DEMO_SCHOOL_CODE)
                .orElseThrow(() -> new IllegalStateException(
                        "Demo data not seeded but quizopia.demo.data.enabled=true; ensure DemoDataSeeder ran"));
        Long schoolId = demoSchool.getId();
        if (accountType == AccountType.TEACHER) {
            teacherProfileRepository.saveAndFlush(new TeacherProfile(user.getId(), schoolId, user.getUsername()));
        } else {
            studentProfileRepository.saveAndFlush(new StudentProfile(user.getId(), schoolId, user.getUsername()));
        }
    }
}
