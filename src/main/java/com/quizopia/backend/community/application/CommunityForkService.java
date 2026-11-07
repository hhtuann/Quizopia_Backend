package com.quizopia.backend.community.application;

import com.quizopia.backend.community.domain.model.CommunityForkRecord;
import com.quizopia.backend.community.exception.CommunityErrorCode;
import com.quizopia.backend.community.exception.CommunityException;
import com.quizopia.backend.security.config.SecurityProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

/**
 * Fork orchestrator (ADR-006 §7-§8, issue #47):
 * {@code POST /api/community/publications/{id}/fork} — atomically clone
 * a visible ALLOW_FORK publication's published exam snapshot into an
 * independent bank + DRAFT exam owned by the requester.
 *
 * <p>This bean is deliberately <b>non-transactional</b>: it fronts the
 * transactional {@link CommunityForkExecutor} so a concurrent
 * same-key loser's {@code UNIQUE(actor_user_id, idempotency_key_hash)}
 * violation — raised inside the executor's transaction — is caught
 * OUTSIDE that boundary (§8 step 9) and resolved by a fresh read-only
 * winner lookup: same publication → 200 with the winner's target,
 * different publication → 409 {@code COMMUNITY_IDEMPOTENCY_CONFLICT}.
 *
 * <p>§8 order: (1) auth gate ACTIVE + TEACHER + COMMUNITY_FORK +
 * EXAM_CREATE; (2) Idempotency-Key validation (8-128 printable ASCII)
 * + actor-scoped HMAC; (3) read-only pre-check (same publication →
 * 200 original, no locks, no admission; different → 409); (4) the
 * transactional fork (admission first, exam row lock, publication row
 * lock, §4 predicate + ALLOW_FORK recheck, clone, draft, ledger row).
 * The idempotency HMAC key is derived from the configured data
 * encryption key under a domain distinct from the V23 limiter domain
 * (§9 key posture); the plaintext key is never stored.
 */
@Service
public class CommunityForkService {

    static final String PERMISSION_FORK = "COMMUNITY_FORK";
    static final String PERMISSION_EXAM_CREATE = "EXAM_CREATE";

    /** Key-derivation domain (distinct from the limiter/V23 domains). */
    private static final String KEY_DOMAIN = "quizopia.community.fork.hmac.v1";
    /** Message prefix per §3: {@code community-fork:<actor>:<key>}. */
    private static final String MESSAGE_DOMAIN = "community-fork";
    private static final int KEY_MIN_LENGTH = 8;
    private static final int KEY_MAX_LENGTH = 128;
    private static final String IDEMPOTENCY_UNIQUE_CONSTRAINT =
            "uk_community_fork_records_actor_key";

    private final CommunityAuthorizationService auth;
    private final CommunityForkExecutor executor;
    private final byte[] hmacKey;

    public CommunityForkService(CommunityAuthorizationService auth,
            CommunityForkExecutor executor,
            SecurityProperties security) {
        this.auth = auth;
        this.executor = executor;
        byte[] encryptionKey;
        try {
            encryptionKey = Base64.getDecoder().decode(security.getEncryption().getKeyBase64());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Data encryption key is not valid Base64", ex);
        }
        this.hmacKey = hmac(encryptionKey, KEY_DOMAIN.getBytes(StandardCharsets.UTF_8));
    }

    /** Fork result: the §7 response body fields plus the 201-vs-200 flag. */
    public record ForkOutcome(
            Long targetExamId,
            Long sourcePublicationId,
            String sourceTitle,
            String authorAlias,
            Instant forkedAt,
            boolean created) {

        /** The §7 shape of a replayed idempotent outcome. */
        static ForkOutcome replayOf(CommunityForkRecord record) {
            return new ForkOutcome(record.getTargetExamId(), record.getSourcePublicationId(),
                    record.getSourceTitleSnapshot(), record.getSourceAliasSnapshot(),
                    record.getCreatedAt(), false);
        }
    }

    /**
     * Orchestrates one fork request. Throws {@link CommunityException}
     * for every §7 outcome (400/403/404/409/429); returns the outcome
     * for 201/200.
     */
    public ForkOutcome fork(Long userId, Long publicationId, String idempotencyKey) {
        // §8 step 1: deny-by-default DB auth — ACTIVE + TEACHER +
        // COMMUNITY_FORK + EXAM_CREATE (unverified callers cannot
        // reach here: AuthN already rejected them). The ACTIVE check
        // is defense-in-depth — disabled tokens die at the converter.
        auth.requireTeacherWithPermission(userId, PERMISSION_FORK);
        auth.requireActiveUserWithPermission(userId, PERMISSION_EXAM_CREATE);

        String key = requireValidIdempotencyKey(idempotencyKey);
        String keyHash = hashIdempotencyKey(userId, key);

        // §8 step 2: read-only pre-check — no locks, no admission. A
        // replay of the same (actor, key, publication) returns the
        // original target; the same key on a DIFFERENT publication is a
        // client bug → 409.
        CommunityForkRecord existing = executor.findRecord(userId, keyHash).orElse(null);
        if (existing != null) {
            if (existing.getSourcePublicationId().equals(publicationId)) {
                return ForkOutcome.replayOf(existing);
            }
            throw new CommunityException(CommunityErrorCode.COMMUNITY_IDEMPOTENCY_CONFLICT);
        }

        try {
            return executor.forkWithinTransaction(userId, publicationId, keyHash);
        } catch (DataIntegrityViolationException ex) {
            // §8 step 9: a concurrent same-key racer won the ledger
            // insert (our transaction rolled back — nothing partial
            // survives). Resolve against the committed winner with a
            // fresh read-only lookup.
            if (!isIdempotencyConflict(ex)) {
                throw ex;
            }
            CommunityForkRecord winner = executor.findRecord(userId, keyHash)
                    .orElseThrow(() -> ex);
            if (winner.getSourcePublicationId().equals(publicationId)) {
                return ForkOutcome.replayOf(winner);
            }
            throw new CommunityException(CommunityErrorCode.COMMUNITY_IDEMPOTENCY_CONFLICT);
        }
    }

    // ============================================================
    // Idempotency key handling (§3/§7/§8)
    // ============================================================

    /** §7: required header, 8-128 printable ASCII characters (0x20-0x7E). */
    static String requireValidIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.length() < KEY_MIN_LENGTH
                || idempotencyKey.length() > KEY_MAX_LENGTH
                || idempotencyKey.chars().anyMatch(c -> c < 0x20 || c > 0x7E)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_IDEMPOTENCY_KEY_INVALID);
        }
        return idempotencyKey;
    }

    /** §3: server-keyed HMAC of {@code community-fork:<actor>:<key>}. */
    String hashIdempotencyKey(Long actorUserId, String key) {
        return hex(hmac(hmacKey, (MESSAGE_DOMAIN + ":" + actorUserId + ":" + key)
                .getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean isIdempotencyConflict(DataIntegrityViolationException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                if (name != null && name.contains(IDEMPOTENCY_UNIQUE_CONSTRAINT)) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA-256 unavailable", ex);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(2 * digest.length);
        for (byte b : digest) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

}
