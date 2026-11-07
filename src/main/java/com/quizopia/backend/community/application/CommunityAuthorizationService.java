package com.quizopia.backend.community.application;

import com.quizopia.backend.community.exception.CommunityErrorCode;
import com.quizopia.backend.community.exception.CommunityException;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.domain.model.UserStatus;
import com.quizopia.backend.identity.repository.RolePermissionRepository;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.identity.repository.UserRoleRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Shared authorization component for the community module — pattern-copy
 * of {@code ExamAuthorizationService} (deny-by-default: active DB role →
 * effective DB permission, no JWT-claim authority, no admin bypass)
 * extended with the §4 publisher eligibility reads (user status +
 * verified email) from the identity {@code UserRepository}.
 *
 * <p>Unverified-email and disabled callers are already rejected at AuthN
 * (they cannot hold a valid token); the service-layer checks here are
 * defense-in-depth and the source of truth for the publish-time
 * eligibility recheck, which runs under the publication row lock.
 */
@Component
public class CommunityAuthorizationService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final Clock clock;

    public CommunityAuthorizationService(UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RolePermissionRepository rolePermissionRepository,
            Clock clock) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.clock = clock;
    }

    /** Identity snapshot used for the alias-equality check (§7). */
    public record PublisherAccount(Long userId, String username, String email) {
    }

    /**
     * Create-gate (§4): ACTIVE + email verified + active TEACHER role +
     * effective permission — all read live from the DB. Failure → 403
     * COMMUNITY_ACCESS_DENIED.
     */
    public PublisherAccount requireEligiblePublisher(Long userId, String permissionCode) {
        PublisherAccount account = account(userId);
        if (!isEligible(userId, permissionCode)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_ACCESS_DENIED);
        }
        return account;
    }

    /** Owner-route gate: active TEACHER role + effective permission (403 on failure). */
    public void requireTeacherWithPermission(Long userId, String permissionCode) {
        if (!hasActiveRole(userId, "TEACHER") || !hasEffectivePermission(userId, permissionCode)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_ACCESS_DENIED);
        }
    }

    /**
     * Publish-time recheck (§4): the caller must STILL be ACTIVE +
     * verified + TEACHER + permission. Returns false instead of throwing
     * so the caller maps the failure to 409 COMMUNITY_PUBLISH_INELIGIBLE.
     */
    public boolean isEligiblePublisher(Long userId, String permissionCode) {
        return isEligible(userId, permissionCode);
    }

    private boolean isEligible(Long userId, String permissionCode) {
        return userRepository.findById(userId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .filter(user -> user.getEmailVerifiedAt() != null)
                .isPresent()
                && hasActiveRole(userId, "TEACHER")
                && hasEffectivePermission(userId, permissionCode);
    }

    private boolean hasActiveRole(Long userId, String roleCode) {
        Instant now = Instant.now(clock);
        List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(userId, now);
        return roles.contains(roleCode);
    }

    private boolean hasEffectivePermission(Long userId, String permissionCode) {
        Instant now = Instant.now(clock);
        List<String> perms = rolePermissionRepository.findEffectivePermissionCodesByUserId(userId, now);
        return perms.contains(permissionCode);
    }

    /**
     * Identity snapshot for the §7 alias-equality check. Throws
     * COMMUNITY_ACCESS_DENIED when the account row is missing (callers
     * invoke this only after an authorization gate passed).
     */
    public PublisherAccount account(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CommunityException(CommunityErrorCode.COMMUNITY_ACCESS_DENIED));
        return new PublisherAccount(user.getId(), user.getUsername(), user.getEmail());
    }
}
