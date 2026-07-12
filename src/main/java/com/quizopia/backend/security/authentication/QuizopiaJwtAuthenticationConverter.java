package com.quizopia.backend.security.authentication;

import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.domain.model.UserStatus;
import com.quizopia.backend.identity.repository.RolePermissionRepository;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.identity.repository.UserRoleRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Converts a validated {@link Jwt} into an authenticated token carrying the
 * principal's authorities.
 *
 * <p>
 * For every authenticated request it:
 * <ol>
 * <li>loads the {@link User} by the {@code sub} claim,</li>
 * <li>rejects when the user is missing, not {@link UserStatus#ACTIVE}, or the
 * {@code token_version} claim differs from {@code users.token_version},
 * and</li>
 * <li>loads the currently effective role and permission codes (expired
 * assignments are excluded) and maps them to authorities.</li>
 * </ol>
 * Authorities use one stable convention: roles become {@code ROLE_<CODE>}
 * (e.g. {@code ROLE_STUDENT}); permissions become their raw code (e.g.
 * {@code EXAM_READ}). SYSTEM_ADMIN is never implicitly granted academic
 * permissions.
 *
 * <p>
 * All controlled failures throw {@link OAuth2AuthenticationException} so the
 * resource-server filter routes them to the authentication entry point as a 401
 * ({@code AUTH_ACCESS_TOKEN_INVALID}), never a 500.
 */
@Component
public class QuizopiaJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    static final String TOKEN_VERSION_CLAIM = "token_version";

    private static final String ROLE_AUTHORITY_PREFIX = "ROLE_";

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final Clock clock;

    public QuizopiaJwtAuthenticationConverter(UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RolePermissionRepository rolePermissionRepository,
            Clock clock) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Long userId = parseSubject(jwt);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> invalidToken("subject does not identify a user"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw invalidToken("account is not active");
        }
        if (!tokenVersionMatches(jwt, user.getTokenVersion())) {
            throw invalidToken("token version mismatch");
        }

        Instant now = Instant.now(clock);
        List<String> roleCodes = userRoleRepository.findActiveRoleCodesByUserId(userId, now);
        List<String> permissionCodes = rolePermissionRepository.findEffectivePermissionCodesByUserId(userId, now);

        List<GrantedAuthority> authorities = new ArrayList<>(roleCodes.size() + permissionCodes.size());
        roleCodes.forEach(code -> authorities.add(new SimpleGrantedAuthority(ROLE_AUTHORITY_PREFIX + code)));
        permissionCodes.forEach(code -> authorities.add(new SimpleGrantedAuthority(code)));

        return new JwtAuthenticationToken(jwt, authorities);
    }

    private static Long parseSubject(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw invalidToken("missing subject");
        }
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException ex) {
            throw invalidToken("subject is not a valid user id");
        }
    }

    private static boolean tokenVersionMatches(Jwt jwt, Integer userTokenVersion) {
        Object claim = jwt.getClaim(TOKEN_VERSION_CLAIM);
        if (!(claim instanceof Number number)) {
            return false;
        }
        return userTokenVersion != null && number.intValue() == userTokenVersion;
    }

    private static OAuth2AuthenticationException invalidToken(String message) {
        return new OAuth2AuthenticationException(
                new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, message, null));
    }
}
