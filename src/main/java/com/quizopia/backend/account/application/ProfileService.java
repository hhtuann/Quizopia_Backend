package com.quizopia.backend.account.application;

import com.quizopia.backend.account.dto.UpdateProfileRequest;
import com.quizopia.backend.authentication.application.CurrentUserService;
import com.quizopia.backend.authentication.dto.CurrentUserResponse;
import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.identity.domain.model.IdentityAuditEvent;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.domain.model.UserStatus;
import com.quizopia.backend.identity.repository.IdentityAuditEventRepository;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.security.encryption.SensitiveDataEncryptor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class ProfileService {
    private final UserRepository users;
    private final EntityManager entityManager;
    private final SensitiveDataEncryptor encryptor;
    private final IdentityAuditEventRepository audit;
    private final CurrentUserService currentUser;
    private final Clock clock;

    public ProfileService(UserRepository users, EntityManager entityManager,
                          SensitiveDataEncryptor encryptor, IdentityAuditEventRepository audit,
                          CurrentUserService currentUser, Clock clock) {
        this.users = users;
        this.entityManager = entityManager;
        this.encryptor = encryptor;
        this.audit = audit;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional
    public CurrentUserResponse update(Long ownerId, Number credentialEpoch, UpdateProfileRequest request) {
        User user = users.findForUpdateById(ownerId)
                .orElseThrow(() -> new AuthenticationException(AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID));
        // A filter/outer persistence context may already contain this user. A locking
        // query alone does not refresh that instance: authorize only refreshed state.
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (credentialEpoch == null || user.getTokenVersion() == null
                || credentialEpoch.longValue() != user.getTokenVersion().longValue()) {
            throw new AuthenticationException(AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID);
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new AuthenticationException(AuthErrorCode.AUTH_ACCOUNT_DISABLED);
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AuthenticationException(AuthErrorCode.AUTH_ACCOUNT_PENDING);
        }
        Instant now = Instant.now(clock);
        if (user.getLockedUntil() != null && now.isBefore(user.getLockedUntil())) {
            throw new AuthenticationException(AuthErrorCode.AUTH_ACCOUNT_LOCKED);
        }

        user.setDisplayName(request.displayName());
        user.setPhoneEncrypted(request.phone() == null ? null : encryptor.encrypt(request.phone()));
        audit.save(new IdentityAuditEvent(user, IdentityAuditEvent.PROFILE_UPDATED, null, null,
                Map.of("fields", List.of("displayName", "phone"))));
        return currentUser.currentUser(ownerId);
    }
}
