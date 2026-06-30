package com.hhtuann.backend.security.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Security configuration bound from the {@code quizopia.security.*} properties.
 *
 * <p>Secrets (JWT signing key, data-encryption key, teacher invite code) are
 * read from environment variables referenced by {@code application.properties}.
 * They must never be committed. Validation enforces presence ({@code @NotBlank})
 * so the application fails fast with a clear message when a secret is missing or
 * empty; cryptographic length checks are performed where the bytes are consumed
 * (see {@link JwtConfig} and
 * {@code AesGcmSensitiveDataEncryptor}).
 *
 * <p>All non-secret values use the locked defaults agreed for the MVP.
 */
@Validated
@ConfigurationProperties(prefix = "quizopia.security")
public class SecurityProperties {

    /**
     * Set to {@code true} in production. Influences cookie {@code Secure} and
     * other environment-aware behaviour (used from Batch 2 onward).
     */
    private boolean production = false;

    @Valid
    @NotNull
    private final Jwt jwt = new Jwt();

    @Valid
    @NotNull
    private final Encryption encryption = new Encryption();

    @Valid
    @NotNull
    private final RefreshToken refreshToken = new RefreshToken();

    @Valid
    @NotNull
    private final Lockout lockout = new Lockout();

    @Valid
    @NotNull
    private final Cookie cookie = new Cookie();

    @Valid
    @NotNull
    private final Cors cors = new Cors();

    @Valid
    @NotNull
    private final TeacherInvite teacherInvite = new TeacherInvite();

    public boolean isProduction() {
        return production;
    }

    public void setProduction(boolean production) {
        this.production = production;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public Encryption getEncryption() {
        return encryption;
    }

    public RefreshToken getRefreshToken() {
        return refreshToken;
    }

    public Lockout getLockout() {
        return lockout;
    }

    public Cookie getCookie() {
        return cookie;
    }

    public Cors getCors() {
        return cors;
    }

    public TeacherInvite getTeacherInvite() {
        return teacherInvite;
    }

    /**
     * JWT access-token settings.
     */
    public static class Jwt {

        /**
         * Base64-encoded HS256 signing key. Must decode to at least 32 bytes.
         * Read from {@code QUIZOPIA_JWT_SECRET_BASE64}.
         */
        @NotBlank
        private String secretBase64;

        /**
         * Access-token lifetime. Locked at 15 minutes.
         */
        @NotNull
        private Duration accessTokenLifetime = Duration.ofMinutes(15);

        @NotBlank
        private String issuer = "quizopia-backend";

        @NotBlank
        private String audience = "quizopia-web";

        public String getSecretBase64() {
            return secretBase64;
        }

        public void setSecretBase64(String secretBase64) {
            this.secretBase64 = secretBase64;
        }

        public Duration getAccessTokenLifetime() {
            return accessTokenLifetime;
        }

        public void setAccessTokenLifetime(Duration accessTokenLifetime) {
            this.accessTokenLifetime = accessTokenLifetime;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }
    }

    /**
     * Sensitive-data encryption settings.
     */
    public static class Encryption {

        /**
         * Base64-encoded 256-bit AES key. Must decode to exactly 32 bytes.
         * Read from {@code QUIZOPIA_DATA_ENCRYPTION_KEY_BASE64}.
         */
        @NotBlank
        private String keyBase64;

        public String getKeyBase64() {
            return keyBase64;
        }

        public void setKeyBase64(String keyBase64) {
            this.keyBase64 = keyBase64;
        }
    }

    /**
     * Refresh-token settings.
     */
    public static class RefreshToken {

        /**
         * Fixed lifetime of a refresh-token family. Locked at 7 days.
         */
        @NotNull
        private Duration familyLifetime = Duration.ofDays(7);

        public Duration getFamilyLifetime() {
            return familyLifetime;
        }

        public void setFamilyLifetime(Duration familyLifetime) {
            this.familyLifetime = familyLifetime;
        }
    }

    /**
     * Account-lockout settings.
     */
    public static class Lockout {

        /**
         * Failed-login attempts before the account is locked. Locked at 5.
         */
        @Min(1)
        private int threshold = 5;

        /**
         * How long an account stays locked once the threshold is reached.
         * Locked at 15 minutes.
         */
        @NotNull
        private Duration duration = Duration.ofMinutes(15);

        public int getThreshold() {
            return threshold;
        }

        public void setThreshold(int threshold) {
            this.threshold = threshold;
        }

        public Duration getDuration() {
            return duration;
        }

        public void setDuration(Duration duration) {
            this.duration = duration;
        }
    }

    /**
     * Refresh-cookie settings. Refresh-token delivery (Batch 2) honours these.
     */
    public static class Cookie {

        @NotBlank
        private String name = "quizopia_refresh";

        private boolean httpOnly = true;

        @NotBlank
        private String sameSite = "Lax";

        @NotBlank
        private String path = "/api/auth";

        private boolean secure = false;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isHttpOnly() {
            return httpOnly;
        }

        public void setHttpOnly(boolean httpOnly) {
            this.httpOnly = httpOnly;
        }

        public String getSameSite() {
            return sameSite;
        }

        public void setSameSite(String sameSite) {
            this.sameSite = sameSite;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public boolean isSecure() {
            return secure;
        }

        public void setSecure(boolean secure) {
            this.secure = secure;
        }
    }

    /**
     * CORS settings. Full CORS enforcement arrives in Batch 2.
     */
    public static class Cors {

        @NotNull
        private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:3000"));

        /**
         * When {@code false} (default), cookie-based refresh and logout require a
         * non-empty {@code Origin} header that matches {@link #allowedOrigins}.
         * Set to {@code true} only for server-to-server tests where no browser
         * {@code Origin} header is present; in production a browser must send a
         * valid origin.
         */
        private boolean allowMissingOrigin = false;

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public boolean isAllowMissingOrigin() {
            return allowMissingOrigin;
        }

        public void setAllowMissingOrigin(boolean allowMissingOrigin) {
            this.allowMissingOrigin = allowMissingOrigin;
        }
    }

    /**
     * Teacher-invite settings. Governs TEACHER account provisioning (Batch 2).
     */
    public static class TeacherInvite {

        /**
         * Invite code required to create a TEACHER account via public
         * registration. Read from {@code QUIZOPIA_TEACHER_INVITE_CODE}.
         */
        @NotBlank
        private String code;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }
    }
}
