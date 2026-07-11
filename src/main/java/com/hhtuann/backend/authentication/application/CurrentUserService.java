package com.hhtuann.backend.authentication.application;

import com.hhtuann.backend.authentication.dto.CurrentUserResponse;
import com.hhtuann.backend.authentication.exception.AuthErrorCode;
import com.hhtuann.backend.authentication.exception.AuthenticationException;
import com.hhtuann.backend.identity.domain.model.User;
import com.hhtuann.backend.identity.repository.RolePermissionRepository;
import com.hhtuann.backend.identity.repository.UserRepository;
import com.hhtuann.backend.identity.repository.UserRoleRepository;
import com.hhtuann.backend.security.encryption.SensitiveDataEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Returns the current user's profile for {@code GET /api/auth/me}. Phone and
 * national identifier are decrypted here and only here, because the caller is
 * the owner; no ciphertext, password hash, token version, lockout or session
 * fields are ever exposed.
 */
@Service
public class CurrentUserService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final SensitiveDataEncryptor encryptor;
    private final Clock clock;

    public CurrentUserService(UserRepository userRepository,
                              UserRoleRepository userRoleRepository,
                              RolePermissionRepository rolePermissionRepository,
                              SensitiveDataEncryptor encryptor,
                              Clock clock) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.encryptor = encryptor;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse currentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthenticationException(AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID));

        Instant now = Instant.now(clock);
        List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(userId, now);
        List<String> permissions = rolePermissionRepository.findEffectivePermissionCodesByUserId(userId, now);

        return new CurrentUserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getDisplayName(),
                decryptOrNull(user.getPhoneEncrypted()),
                user.getStatus(),
                roles,
                permissions);
    }

    private String decryptOrNull(String ciphertext) {
        return ciphertext == null ? null : encryptor.decrypt(ciphertext);
    }
}
