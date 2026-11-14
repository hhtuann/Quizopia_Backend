package com.quizopia.backend.media.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * LiveKit self-hosted media infrastructure settings (P4-B1, issue #84;
 * ADR-008 D2). Bound from {@code quizopia.media.livekit.*}.
 *
 * <p>{@code enabled=false} (default) keeps the whole camera path dormant:
 * preflight endpoints answer 503 {@code MONITORING_INFRA_UNAVAILABLE},
 * schedulers stay idle and legacy behaviour is bit-for-bit unchanged —
 * the same fail-closed posture P4-A shipped with.</p>
 *
 * <p>Secrets: {@code apiKey}/{@code apiSecret} are validated at startup ONLY
 * when enabled (see {@link LiveKitStartupValidator}) — binding-level
 * @NotBlank would reject every context that runs with media disabled
 * (CI/test), which is exactly the fail-closed default.</p>
 */
@Validated
@ConfigurationProperties(prefix = "quizopia.media.livekit")
public class LiveKitProperties {

    /** Master feature flag for the media infrastructure. */
    private boolean enabled = false;

    /** LiveKit API key name (must match the server's key configuration). */
    private String apiKey = "";

    /** LiveKit API secret (HMAC key for tokens, admin calls and webhooks). */
    private String apiSecret = "";

    /** Server API base URL (Twirp RoomService over HTTP), no trailing slash. */
    @NotBlank
    private String httpUrl = "http://localhost:7880";

    /** Browser-facing WebSocket signaling URL returned to clients. */
    @NotBlank
    private String wsUrl = "ws://localhost:7880";

    /** TTL of browser media tokens (ADR-008: short by design, 60–120s). */
    @NotNull
    private Duration mediaTokenTtl = Duration.ofSeconds(120);

    /** TTL of the server-only admin token minted per RoomService call. */
    @NotNull
    private Duration adminTokenTtl = Duration.ofSeconds(60);

    /** Max age of an accepted webhook event before it is treated as stale. */
    @NotNull
    private Duration webhookMaxAge = Duration.ofMinutes(10);

    /** RoomService HTTP connect/read budget (never inside a DB tx anyway). */
    @NotNull
    private Duration requestTimeout = Duration.ofSeconds(5);

    /** Camera readiness lease TTL handed to verified preflights (P4-B2). */
    @NotNull
    private Duration readinessLeaseTtl = Duration.ofSeconds(90);

    /** Preflight lifetime before verification (client connect+publish window). */
    @NotNull
    private Duration preflightTtl = Duration.ofMinutes(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(String apiSecret) {
        this.apiSecret = apiSecret;
    }

    public String getHttpUrl() {
        return httpUrl;
    }

    public void setHttpUrl(String httpUrl) {
        this.httpUrl = httpUrl;
    }

    public String getWsUrl() {
        return wsUrl;
    }

    public void setWsUrl(String wsUrl) {
        this.wsUrl = wsUrl;
    }

    public Duration getMediaTokenTtl() {
        return mediaTokenTtl;
    }

    public void setMediaTokenTtl(Duration mediaTokenTtl) {
        this.mediaTokenTtl = mediaTokenTtl;
    }

    public Duration getAdminTokenTtl() {
        return adminTokenTtl;
    }

    public void setAdminTokenTtl(Duration adminTokenTtl) {
        this.adminTokenTtl = adminTokenTtl;
    }

    public Duration getWebhookMaxAge() {
        return webhookMaxAge;
    }

    public void setWebhookMaxAge(Duration webhookMaxAge) {
        this.webhookMaxAge = webhookMaxAge;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getReadinessLeaseTtl() {
        return readinessLeaseTtl;
    }

    public void setReadinessLeaseTtl(Duration readinessLeaseTtl) {
        this.readinessLeaseTtl = readinessLeaseTtl;
    }

    public Duration getPreflightTtl() {
        return preflightTtl;
    }

    public void setPreflightTtl(Duration preflightTtl) {
        this.preflightTtl = preflightTtl;
    }
}
