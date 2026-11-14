package com.quizopia.backend.media.token;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.quizopia.backend.media.config.LiveKitProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mints and verifies LiveKit access tokens (HS256 JWT with a {@code video}
 * grant object) using nimbus-jose-jwt — already on the classpath via Spring
 * Security — instead of pulling the Kotlin LiveKit server SDK into a Java
 * codebase. Claim layout matches the official token spec
 * (docs.livekit.io/frontends/reference/tokens-grants): {@code iss} = API key,
 * {@code sub} = participant identity, {@code video} = grant object.
 *
 * <p>Grant shapes are closed (least privilege, ADR-008 invariant 13):</p>
 * <ul>
 *   <li><b>student</b> — join one fixed room, publish camera video only,
 *       no subscribe, no data, no mic/screen sources.</li>
 *   <li><b>teacher</b> — join the same room subscribe-only.</li>
 *   <li><b>admin</b> — server-only RoomService identity; never leaves the
 *       backend process.</li>
 * </ul>
 */
@Service
public class LiveKitTokenService {

    private final LiveKitProperties properties;

    public LiveKitTokenService(LiveKitProperties properties) {
        this.properties = properties;
    }

    /** Student publisher token: camera-only publish in {@code room}. */
    public String mintStudentToken(String mediaIdentity, String room, Instant now) {
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("roomJoin", true);
        video.put("room", room);
        video.put("canPublish", true);
        video.put("canPublishSources", java.util.List.of("camera"));
        video.put("canSubscribe", false);
        video.put("canPublishData", false);
        return mint(mediaIdentity, video, properties.getMediaTokenTtl(), now);
    }

    /** Teacher viewer token: subscribe-only in {@code room}. */
    public String mintTeacherToken(Long teacherUserId, String room, Instant now) {
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("roomJoin", true);
        video.put("room", room);
        video.put("canPublish", false);
        video.put("canSubscribe", true);
        video.put("canPublishData", false);
        return mint("teacher-" + teacherUserId, video, properties.getMediaTokenTtl(), now);
    }

    /** Server-only admin token for RoomService calls (room management). */
    public String mintAdminToken(Instant now) {
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("roomAdmin", true);
        video.put("roomList", true);
        video.put("roomCreate", true);
        video.put("roomDelete", true);
        return mint("quizopia-backend", video, properties.getAdminTokenTtl(), now);
    }

    /**
     * Verifies a LiveKit webhook JWT's signature and returns its claims
     * (used by {@code LiveKitWebhookVerifier}, which additionally checks
     * the body sha256 claim).
     */
    public JWTClaimsSet parseAndVerify(String serializedJwt) throws Exception {
        SignedJWT jwt = SignedJWT.parse(serializedJwt);
        if (!jwt.verify(new MACVerifier(properties.getApiSecret()))) {
            throw new IllegalArgumentException("invalid signature");
        }
        return jwt.getJWTClaimsSet();
    }

    private String mint(String identity, Map<String, Object> videoGrant, Duration ttl, Instant now) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).build(),
                    new JWTClaimsSet.Builder()
                            .issuer(properties.getApiKey())
                            .subject(identity)
                            .jwtID(UUID.randomUUID().toString())
                            .issueTime(Date.from(now))
                            .notBeforeTime(Date.from(now.minusSeconds(5)))
                            .expirationTime(Date.from(now.plus(ttl)))
                            .claim("video", videoGrant)
                            .build());
            jwt.sign(new MACSigner(properties.getApiSecret()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to mint LiveKit token", e);
        }
    }
}
