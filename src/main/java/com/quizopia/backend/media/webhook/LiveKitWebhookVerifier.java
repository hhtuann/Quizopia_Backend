package com.quizopia.backend.media.webhook;

import com.nimbusds.jwt.JWTClaimsSet;
import com.quizopia.backend.media.config.LiveKitProperties;
import com.quizopia.backend.media.token.LiveKitTokenService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * LiveKit signed-webhook verifier (P4-B4, issue #87) — validated against a
 * real self-hosted v1.9.12 server (captured room_started sample, see
 * docs/phase4 evidence). The wire format is:
 *
 * <ul>
 *   <li>{@code Authorization} header carries the JWT <b>without</b> a
 *       {@code Bearer } prefix (bare token; both forms accepted here);</li>
 *   <li>the JWT (HS256, API secret) carries ONLY {@code iss/nbf/exp} and a
 *       {@code sha256} claim = base64(plain SHA-256(rawBody)) — a plain
 *       digest, not an HMAC;</li>
 *   <li>the raw HTTP body IS the event JSON (protojson): {@code event},
 *       {@code id} (EV_…), {@code room.name}, {@code participant.identity},
 *       {@code track.sid/type/source}, {@code timestamp} (nanoseconds;
 *       protojson may emit int64 as string or number; room lifecycle events
 *       may omit it in favour of {@code room.createdAt} seconds).</li>
 * </ul>
 *
 * Freshness: events carrying {@code timestamp} older than
 * {@code webhookMaxAge} (or in the future) are rejected; events without any
 * timestamp fall back to {@code room.createdAt}; room lifecycle events with
 * neither are accepted (ledger-only routing). Failures throw
 * {@link VerificationException} — callers respond 401 without detail.
 */
@Component
public class LiveKitWebhookVerifier {

    private final LiveKitTokenService tokens;
    private final LiveKitProperties properties;
    private final ObjectMapper objectMapper;

    public LiveKitWebhookVerifier(LiveKitTokenService tokens,
                                  LiveKitProperties properties,
                                  ObjectMapper objectMapper) {
        this.tokens = tokens;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public LiveKitWebhookEvent verify(byte[] rawBody, String authorizationHeader) {
        try {
            String jwt = bearerToken(authorizationHeader);
            JWTClaimsSet claims = tokens.parseAndVerify(jwt);

            String claim = claims.getStringClaim("sha256");
            if (claim == null) {
                throw new VerificationException("missing sha256 claim");
            }
            String bodyDigest = Base64.getEncoder()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(rawBody));
            if (!MessageDigest.isEqual(
                    claim.getBytes(StandardCharsets.UTF_8), bodyDigest.getBytes(StandardCharsets.UTF_8))) {
                throw new VerificationException("body hash mismatch");
            }

            JsonNode event = objectMapper.readTree(rawBody);
            String type = text(event, "event");
            if (type == null || type.isBlank()) {
                throw new VerificationException("missing event");
            }

            JsonNode room = event.get("room");
            String roomName = room == null ? null : text(room, "name");
            JsonNode participant = event.get("participant");
            JsonNode track = event.get("track");

            String eventId = text(event, "id");
            String identity = participant == null ? null : text(participant, "identity");
            String trackSid = track == null ? null : text(track, "sid");

            Instant timestamp = instantNanos(event.get("timestamp"));
            if (timestamp == null && room != null) {
                timestamp = instantSeconds(text(room, "createdAt"));
            }
            if (eventHasTimestampField(event) && timestamp != null) {
                Instant now = Instant.now();
                if (timestamp.isBefore(now.minus(properties.getWebhookMaxAge()))
                        || timestamp.isAfter(now.plusSeconds(60))) {
                    throw new VerificationException("stale or future timestamp");
                }
            }

            String dedupeSource = eventId != null && !eventId.isBlank()
                    ? "id:" + eventId
                    : type + "|" + nullSafe(roomName) + "|" + nullSafe(identity) + "|"
                            + nullSafe(trackSid) + "|" + timestamp;
            String dedupeKey = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(dedupeSource.getBytes(StandardCharsets.UTF_8)));

            return new LiveKitWebhookEvent(
                    type,
                    roomName,
                    identity,
                    participant == null ? null : text(participant, "state"),
                    trackSid,
                    track == null ? null : text(track, "type"),
                    track == null ? null : text(track, "source"),
                    timestamp,
                    dedupeKey);
        } catch (VerificationException e) {
            throw e;
        } catch (Exception e) {
            throw new VerificationException("malformed webhook", e);
        }
    }

    /** LiveKit sends the JWT bare; tolerate an explicit Bearer prefix too. */
    private static String bearerToken(String header) {
        if (header == null || header.isBlank()) {
            throw new VerificationException("missing authorization");
        }
        String value = header.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).trim();
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static boolean eventHasTimestampField(JsonNode event) {
        return event.hasNonNull("timestamp");
    }

    private static Instant instantNanos(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            long nanos = Long.parseLong(value.asString());
            return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L),
                    Math.floorMod(nanos, 1_000_000_000L));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant instantSeconds(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
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
