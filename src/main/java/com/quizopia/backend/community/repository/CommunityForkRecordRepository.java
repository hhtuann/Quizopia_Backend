package com.quizopia.backend.community.repository;

import com.quizopia.backend.community.domain.model.CommunityForkRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link CommunityForkRecord} — append-only (save +
 * reads; no update/delete surface). The idempotency lookup backs the
 * §8 read-only pre-check and the concurrent-loser winner resolution.
 */
public interface CommunityForkRecordRepository extends JpaRepository<CommunityForkRecord, Long> {

    /** §8 step 2 pre-check + loser recovery: the actor's row for one hashed key. */
    Optional<CommunityForkRecord> findByActorUserIdAndIdempotencyKeyHash(
            Long actorUserId, String idempotencyKeyHash);
}
