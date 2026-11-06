package com.quizopia.backend.account.application;

import com.quizopia.backend.account.dto.ChangePasswordRequest;
import com.quizopia.backend.authentication.application.CredentialSecurityService;
import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.identity.domain.model.IdentityAuditEvent;
import com.quizopia.backend.security.password.PasswordHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChangePasswordService {
    private final CredentialSecurityService credentials;
    private final PasswordHasher passwords;
    private final TransactionTemplate transactions;

    public ChangePasswordService(CredentialSecurityService credentials, PasswordHasher passwords,
                                 TransactionTemplate transactions) {
        this.credentials = credentials;
        this.passwords = passwords;
        this.transactions = transactions;
    }

    public void change(Long userId, Number epoch, ChangePasswordRequest request) {
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new AuthenticationException(AuthErrorCode.AUTH_VALIDATION_ERROR);
        }
        AuthErrorCode outcome = transactions.execute(status -> {
            var user = credentials.lockUser(userId);
            if (epoch == null || epoch.longValue() != user.getTokenVersion().longValue()) {
                return AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID;
            }
            AuthErrorCode proof = credentials.reauthenticateLocked(user, request.currentPassword());
            if (proof != null) return proof;
            if (passwords.matches(request.newPassword(), user.getPasswordHash())) {
                return AuthErrorCode.AUTH_VALIDATION_ERROR;
            }
            credentials.mutatePasswordLocked(user, request.newPassword(), IdentityAuditEvent.PASSWORD_CHANGED);
            return null;
        });
        // Wrong-password counters must commit before the HTTP error is raised.
        if (outcome != null) throw new AuthenticationException(outcome);
    }
}
