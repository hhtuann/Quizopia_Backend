package com.quizopia.backend.authentication.application;

import com.quizopia.backend.authentication.dto.ClientContext;
import com.quizopia.backend.authentication.dto.LoginRequest;
import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.security.password.PasswordHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/** Password verification and issuance share one fresh user lock; rejected proofs commit counters. */
@Service
public class LoginService {
    private final UserRepository users;
    private final PasswordHasher passwords;
    private final CredentialSecurityService credentials;
    private final SessionIssuanceService issuance;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String dummyHash;

    public LoginService(UserRepository users, PasswordHasher passwords,
            CredentialSecurityService credentials, SessionIssuanceService issuance,
            TransactionTemplate tx, Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.credentials = credentials;
        this.issuance = issuance;
        this.tx = tx;
        this.clock = clock;
        this.dummyHash = passwords.hash("quizopia-login-timing-equalizer");
    }

    public SessionIssuanceService.IssuedSession login(LoginRequest request, ClientContext client) {
        Outcome outcome = tx.execute(status -> {
            Long id = request.identifier().contains("@")
                    ? users.findIdByEmailIgnoreCase(request.identifier()).orElse(null)
                    : users.findIdByUsernameIgnoreCase(request.identifier()).orElse(null);
            if (id == null) {
                passwords.matches(request.password(), dummyHash);
                return new Outcome(AuthErrorCode.AUTH_INVALID_CREDENTIALS, null);
            }
            User user = credentials.lockUser(id);
            boolean identifierMatches = request.identifier().contains("@")
                    ? user.getEmail().equalsIgnoreCase(request.identifier())
                    : user.getUsername().equalsIgnoreCase(request.identifier());
            if (!identifierMatches) {
                passwords.matches(request.password(), dummyHash);
                return new Outcome(AuthErrorCode.AUTH_INVALID_CREDENTIALS, null);
            }
            AuthErrorCode error = credentials.reauthenticateLocked(user, request.password());
            if (error != null) return new Outcome(error, null);
            if (Boolean.TRUE.equals(user.getLoginRequiresEmailVerification()) && user.getEmailVerifiedAt() == null) {
                return new Outcome(AuthErrorCode.AUTH_EMAIL_NOT_VERIFIED, null);
            }
            user.setLastLoginAt(Instant.now(clock));
            users.flush();
            return new Outcome(null, issuance.issue(user, client));
        });
        if (outcome.error() != null) throw new AuthenticationException(outcome.error());
        return outcome.session();
    }

    private record Outcome(AuthErrorCode error, SessionIssuanceService.IssuedSession session) { }
}
