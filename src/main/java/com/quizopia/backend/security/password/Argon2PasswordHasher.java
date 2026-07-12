package com.quizopia.backend.security.password;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Argon2id implementation of {@link PasswordHasher} backed by Spring Security's
 * {@link Argon2PasswordEncoder}.
 *
 * <p>The encoder is created with Spring Security's recommended current defaults
 * via {@link Argon2PasswordEncoder#defaultsForSpringSecurity_v5_8()}, which
 * selects the Argon2id variant ({@code ARGON2_id}). No custom Argon2 parameters
 * are written here; if stronger parameters are needed later they are configured
 * through Spring Security and {@link #needsRehash(String)} will flag hashes that
 * should be re-encoded.
 *
 * <p>This class holds no reference to any raw password. The raw password is
 * converted to a {@code char[]}/{@code String} only for the duration of a single
 * {@link PasswordEncoder} call and is never stored in a field or logged.
 */
@Component
public class Argon2PasswordHasher implements PasswordHasher {

    private final Argon2PasswordEncoder delegate;

    /**
     * Creates a hasher using Spring Security's secure Argon2id defaults.
     */
    public Argon2PasswordHasher() {
        this.delegate = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Override
    public String hash(CharSequence rawPassword) {
        Objects.requireNonNull(rawPassword, "rawPassword must not be null");
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String passwordHash) {
        if (rawPassword == null || passwordHash == null) {
            return false;
        }
        return delegate.matches(rawPassword, passwordHash);
    }

    @Override
    public boolean needsRehash(String passwordHash) {
        Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        return delegate.upgradeEncoding(passwordHash);
    }
}
