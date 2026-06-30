package com.hhtuann.backend.security.password;

/**
 * Password hashing abstraction.
 *
 * <p>Implementations hash raw passwords with a slow, salted key-derivation
 * function (Argon2id) and verify a raw password against a stored hash. The raw
 * password is never retained: callers pass a {@link CharSequence} and the
 * implementation must not keep a reference to it.
 *
 * <p>Security invariants every implementation must honour:
 * <ul>
 *   <li>Never log the raw password or the password hash.</li>
 *   <li>Two hashes of the same raw password differ because each has its own
 *       random salt.</li>
 *   <li>{@link #needsRehash(String)} reports whether a stored hash should be
 *       re-encoded with stronger parameters.</li>
 * </ul>
 */
public interface PasswordHasher {

    /**
     * Hashes a raw password.
     *
     * @param rawPassword the raw password; must not be {@code null}
     * @return the salted Argon2id hash string suitable for storage
     */
    String hash(CharSequence rawPassword);

    /**
     * Verifies a raw password against a stored hash.
     *
     * <p>Returns {@code false} (never throws) when either argument is
     * {@code null}, so callers can use the result directly without extra
     * null handling.
     *
     * @param rawPassword  the raw password supplied at authentication
     * @param passwordHash the stored Argon2id hash
     * @return {@code true} if the raw password matches the stored hash
     */
    boolean matches(CharSequence rawPassword, String passwordHash);

    /**
     * Indicates whether a stored hash should be re-hashed because the encoding
     * parameters are weaker than the current configuration.
     *
     * @param passwordHash the stored Argon2id hash; must not be {@code null}
     * @return {@code true} if the hash should be re-encoded
     */
    boolean needsRehash(String passwordHash);
}
