package com.quizopia.backend.media.webhook;

import com.nimbusds.jwt.JWTClaimsSet;
import com.quizopia.backend.media.config.LiveKitProperties;
import com.quizopia.backend.media.token.LiveKitTokenService;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * LiveKit signed-webhook verifier (P4-B4, issue #87). Mirrors the official
 * {@code WebhookReceiver} algorithm (livekit protocol docs):
 *
 * <ol>
 *   <li>parse the {@code Authorization: Bearer <jwt>} header and verify its
 *       HS256 signature against the API secret;</li>
 *   <li>require a {@code sha256} claim equal to
 *       hex(HMAC-SHA256(rawBody, apiSecret));</li>
 *   <li>reject events older than {@code webhookMaxAge} (replay window);</li>
 *   <li>project the JWT payload's event/room/participant/track fields into
 *       {@link LiveKitWebhookEvent} with a deterministic dedupe key.</li>
 * </ol>
 *
 * Any failure throws {@link VerificationException} — the caller maps it to
 * 401 without detail (never leak which check failed to a forger).
 */
@Component
public class LiveKitWebhookVerifier {

    private final LiveKitTokenService tokens;
    private final LiveKitProperties properties;

    public LiveKitWebhookVerifier(LiveKitTokenService tokens, LiveKitProperties properties) {
        this.tokens = tokens;
        this.properties = properties;
    }

    public LiveKitWebhookEvent verify(byte[] rawBody, String authorizationHeader) {
        try {
            String jwt = bearerToken(authorizationHeader);
            JWTClaimsSet claims = tokens.parseAndVerify(jwt);

            String bodyHash = HexFormat.of().formatHex(
                    hmacSha256(rawBody, properties.getApiSecret().getBytes(StandardCharsets.UTF_8)));
            String claim = claims.getStringClaim("sha256");
            if (claim == null || !MessageDigest.isEqual(
                    claim.getBytes(StandardCharsets.UTF_8), bodyHash.getBytes(StandardCharsets.UTF_8))) {
                throw new VerificationException("body hash mismatch");
            }

            String event = str(claims.getClaim("event"));
            if (event == null || event.isBlank()) {
                throw new VerificationException("missing event");
            }

            Instant timestamp = instant(claims.getClaim("timestamp"));
            if (timestamp == null
                    || timestamp.isBefore(Instant.now().minus(properties.getWebhookMaxAge()))
                    || timestamp.isAfter(Instant.now().plusSeconds(60))) {
                throw new VerificationException("stale or future timestamp");
            }

            String roomName = nested(claims, "room", "name");
            String identity = nested(claims, "participant", "identity");
            String participantState = nested(claims, "participant", "state");
            String trackSid = nested(claims, "track", "sid");
            String trackType = nested(claims, "track", "type");
            String trackSource = nested(claims, "track", "source");

            String dedupeKey = sha256Hex((event + "|" + nullSafe(roomName) + "|"
                    + nullSafe(identity) + "|" + nullSafe(trackSid) + "|" + timestamp)
                    .getBytes(StandardCharsets.UTF_8));

            return new LiveKitWebhookEvent(event, roomName, identity, participantState,
                    trackSid, trackType, trackSource, timestamp, dedupeKey);
        } catch (VerificationException e) {
            throw e;
        } catch (Exception e) {
            throw new VerificationException("malformed webhook", e);
        }
    }

    private static String bearerToken(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new VerificationException("missing bearer token");
        }
        return header.substring(7).trim();
    }

    private static String nested(JWTClaimsSet claims, String object, String field) {
        Object value = claims.getClaim(object);
        if (value instanceof Map<?, ?> map && map.get(field) != null) {
            return String.valueOf(map.get(field));
        }
        return null;
    }

    /** protojson emits int64 as decimal strings; tolerate numbers too. */
    private static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        try {
            long nanos = Long.parseLong(String.valueOf(value));
            return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L),
                    Math.floorMod(nanos, 1_000_000_000L));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static byte[] hmacSha256(byte[] data, byte[] key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Verification failure — caller responds 401 without detail. */
    public static class VerificationException extends RuntimeException {
        public VerificationException(String message) {
            super(message);
        }

        public VerificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
