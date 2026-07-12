package com.quizopia.backend.security.authentication;

import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Derives the highest-privilege supported role from the effective
 * {@link GrantedAuthority} collection loaded
 * by {@link QuizopiaJwtAuthenticationConverter} (which queries the DB for
 * currently-active roles). This is
 * the ONLY correct source of role truth for authorization — the JWT
 * {@code roles} claim is stale and
 * cannot be trusted (a role may have been revoked or expired since the token
 * was issued).
 *
 * <p>
 * Precedence: SYSTEM_ADMIN > ACADEMIC_ADMIN > TEACHER > STUDENT. If no
 * supported active role is found,
 * returns {@code null} — the caller MUST deny (fail-closed).
 */
public final class EffectiveRoleResolver {

    public static final String ROLE_PREFIX = "ROLE_";

    private EffectiveRoleResolver() {
    }

    /**
     * @return the highest-privilege role code (e.g. "TEACHER"), or {@code null} if
     *         no supported role.
     */
    @SuppressWarnings("null")
    public static String resolve(Collection<? extends GrantedAuthority> authorities) {
        if (authorities == null)
            return null;
        Set<String> roles = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && a.startsWith(ROLE_PREFIX))
                .map(a -> a.substring(ROLE_PREFIX.length()))
                .collect(Collectors.toSet());
        if (roles.contains("SYSTEM_ADMIN"))
            return "SYSTEM_ADMIN";
        if (roles.contains("ACADEMIC_ADMIN"))
            return "ACADEMIC_ADMIN";
        if (roles.contains("TEACHER"))
            return "TEACHER";
        if (roles.contains("STUDENT"))
            return "STUDENT";
        return null; // unsupported/no active role
    }
}
