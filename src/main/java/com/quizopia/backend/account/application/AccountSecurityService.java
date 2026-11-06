package com.quizopia.backend.account.application;

import com.quizopia.backend.account.dto.AccountSecurityResponse;
import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.identity.domain.model.ExternalProvider;
import com.quizopia.backend.identity.domain.model.LocalCredentialState;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.domain.model.UserStatus;
import com.quizopia.backend.identity.repository.ExternalIdentityRepository;
import com.quizopia.backend.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountSecurityService {
    private final UserRepository users;
    private final ExternalIdentityRepository identities;

    public AccountSecurityService(UserRepository users, ExternalIdentityRepository identities) {
        this.users = users;
        this.identities = identities;
    }

    @Transactional(readOnly = true)
    public AccountSecurityResponse read(Long ownerId, Number epoch) {
        User user = users.findById(ownerId)
                .orElseThrow(() -> new AuthenticationException(AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID));
        if (epoch == null || epoch.longValue() != user.getTokenVersion().longValue()
                || user.getStatus() != UserStatus.ACTIVE) {
            throw new AuthenticationException(AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID);
        }
        return new AccountSecurityResponse(user.getLocalCredentialState(),
                user.getLocalCredentialState() != LocalCredentialState.DISABLED,
                identities.existsByUserIdAndProvider(ownerId, ExternalProvider.GOOGLE),
                user.getPasswordChangedAt());
    }
}
