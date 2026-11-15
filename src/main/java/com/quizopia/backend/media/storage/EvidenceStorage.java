package com.quizopia.backend.media.storage;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Private object storage port for monitoring evidence (P4-D, ADR-009 §2.3).
 *
 * <p>One private bucket; the backend is the only credential holder.
 * Implementations MUST NOT be called while a database transaction is active
 * (ADR-008 invariant 6) — {@code MinioEvidenceStorage} enforces this at
 * runtime exactly like the media gateways.
 */
public interface EvidenceStorage {

    /** SHA-256 (hex) + size of a stored object. */
    record StoredObject(String sha256Hex, long bytes) {
    }

    /** Size-only metadata as returned by HEAD (digests are not stored for
     *  egress-written files; ADR-009 §1 accepts size>0 + provenance). */
    record ObjectHead(long bytes) {
    }

    /** Stores bytes at {@code key}; returns digest + size. */
    StoredObject put(String key, byte[] content, String contentType);

    /** HEAD the object; empty when absent. */
    Optional<ObjectHead> head(String key);

    /** Short-lived presigned GET URL (teacher playback; audited by callers). */
    String presignedGet(String key, Duration ttl);

    /** Deletes the object; false when it was already gone. */
    boolean delete(String key);

    /** Lists object keys under a prefix (retention / orphan reconciliation). */
    List<String> list(String prefix);
}
