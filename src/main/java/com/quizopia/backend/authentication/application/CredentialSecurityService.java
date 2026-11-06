package com.quizopia.backend.authentication.application;

import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.identity.domain.model.IdentityAuditEvent;
import com.quizopia.backend.identity.domain.model.LocalCredentialState;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.domain.model.UserStatus;
import com.quizopia.backend.identity.repository.IdentityAuditEventRepository;
import com.quizopia.backend.identity.repository.RefreshSessionRepository;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.security.config.SecurityProperties;
import com.quizopia.backend.security.password.PasswordHasher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/** Shared user-first credential serialization. Expected proof failures are outcomes, not exceptions. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CredentialSecurityService {
    private final UserRepository users;
    private final EntityManager entityManager;
    private final PasswordHasher passwords;
    private final SecurityProperties properties;
    private final Clock clock;
    private final IdentityAuditEventRepository audit;
    private final RefreshSessionRepository sessions;
    private final ApplicationEventPublisher events;

    public CredentialSecurityService(UserRepository users, EntityManager entityManager,
            PasswordHasher passwords, SecurityProperties properties, Clock clock,
            IdentityAuditEventRepository audit, RefreshSessionRepository sessions,
            ApplicationEventPublisher events) {
        this.users = users;
        this.entityManager = entityManager;
        this.passwords = passwords;
        this.properties = properties;
        this.clock = clock;
        this.audit = audit;
        this.sessions = sessions;
        this.events = events;
    }

    public User lockUser(Long userId) {
        User user = users.findForUpdateById(userId)
                .orElseThrow(() -> new AuthenticationException(AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID));
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        return user;
    }

    public void requireActiveUnlocked(User user) {
        AuthErrorCode error = accountError(user, Instant.now(clock));
        if (error != null) throw new AuthenticationException(error);
    }

    /** Caller holds the fresh user lock and commits this outcome before throwing an error. */
    public AuthErrorCode reauthenticateLocked(User user, String password) {
        Instant now = Instant.now(clock);
        AuthErrorCode error = accountError(user, now);
        if (error != null) return error;
        if (user.getLocalCredentialState() == LocalCredentialState.DISABLED) {
            return AuthErrorCode.AUTH_LOCAL_CREDENTIAL_UNSUPPORTED;
        }
        if (user.getLockedUntil() != null && !now.isBefore(user.getLockedUntil())) {
            // Expired lock window: reset before evaluating this proof (matches login).
            user.setLockedUntil(null);
            user.setFailedLoginAttempts(0);
        }
        if (!passwords.matches(password, user.getPasswordHash())) {
            int failures = Math.min(user.getFailedLoginAttempts(), Integer.MAX_VALUE - 1) + 1;
            user.setFailedLoginAttempts(failures);
            if (failures >= properties.getLockout().getThreshold()) {
                user.setLockedUntil(now.plus(properties.getLockout().getDuration()));
            }
            return AuthErrorCode.AUTH_INVALID_CREDENTIALS;
        }
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        if (passwords.needsRehash(user.getPasswordHash())) {
            user.setPasswordHash(passwords.hash(password));
        }
        if (user.getLocalCredentialState() == LocalCredentialState.UNKNOWN) {
            user.setLocalCredentialState(LocalCredentialState.ENABLED);
            audit.save(new IdentityAuditEvent(user, IdentityAuditEvent.CREDENTIAL_PROOF_PROMOTED,
                    null, null, Map.of()));
        }
        return null;
    }

    /** Only called after the owner proof and new-password validation succeed under this user lock. */
    public void mutatePasswordLocked(User user, String newPassword, String eventType) {
        if (!IdentityAuditEvent.PASSWORD_CHANGED.equals(eventType)
                && !IdentityAuditEvent.PASSWORD_RESET.equals(eventType)) {
            throw new IllegalArgumentException("Unsupported credential audit event");
        }
        requireActiveUnlocked(user);
        if (user.getLocalCredentialState() == LocalCredentialState.DISABLED) {

            throw new AuthenticationException(AuthErrorCode.AUTH_LOCAL_CREDENTIAL_UNSUPPORTED);
        }
        Instant now = Instant.now(clock);
        user.setPasswordHash(passwords.hash(newPassword));
        user.setPasswordChangedAt(now);
        user.setLocalCredentialState(LocalCredentialState.ENABLED);
        user.setTokenVersion(Math.addExact(user.getTokenVersion(), 1));
        audit.save(new IdentityAuditEvent(user, eventType, null, null, Map.of()));
        invalidateAuthorizationProofsLocked(user);
        entityManager.flush();
        java.sql.Timestamp latest = sessions.findLatestUnrevokedCreatedAt(user.getId());
        Instant revokedAt = now;
        if (latest != null && latest.toInstant().isAfter(revokedAt)) {
            // The bulk update must satisfy revoked_at >= created_at for EVERY row it
            // touches, so the floor is the LATEST unrevoked creation, not the earliest.
            revokedAt = latest.toInstant();
        }
        sessions.revokeUnrevokedByUserId(user.getId(), revokedAt, eventType);
    }
    /** Synchronous listeners extend invalidation with V23 reset proofs in the same transaction. */
    public void invalidateAuthorizationProofsLocked(User user) {
        entityManager.flush();
        entityManager.createQuery("delete from OauthLoginCode c where c.user.id = :id")
                .setParameter("id", user.getId()).executeUpdate();
        entityManager.createQuery("delete from OauthLinkRequest r where r.user.id = :id")
                .setParameter("id", user.getId()).executeUpdate();
        events.publishEvent(new AuthorizationProofsInvalidated(user.getId()));
    }

    public record AuthorizationProofsInvalidated(Long userId) { }

    private AuthErrorCode accountError(User user, Instant now) {
        if (user.getStatus() == UserStatus.DISABLED) return AuthErrorCode.AUTH_ACCOUNT_DISABLED;
        if (user.getStatus() != UserStatus.ACTIVE) return AuthErrorCode.AUTH_ACCOUNT_PENDING;
        if (user.getLockedUntil() != null && now.isBefore(user.getLockedUntil())) {
            return AuthErrorCode.AUTH_ACCOUNT_LOCKED;
        }
        return null;
    }
}
