package com.quizopia.backend.community.application;

import com.quizopia.backend.community.domain.model.CommunityForkRecord;
import com.quizopia.backend.community.domain.model.CommunityPublication;
import com.quizopia.backend.community.domain.model.CommunityReusePolicy;
import com.quizopia.backend.community.exception.CommunityErrorCode;
import com.quizopia.backend.community.exception.CommunityException;
import com.quizopia.backend.community.repository.CommunityForkRecordRepository;
import com.quizopia.backend.community.repository.CommunityPublicationRepository;
import com.quizopia.backend.exam.application.ExamCommunityPort;
import com.quizopia.backend.question.application.QuestionCommunityPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Transactional core of the community fork (ADR-006 §8 steps 3-8,
 * issue #47). Invoked only by the non-transactional orchestrator
 * {@link CommunityForkService} — the split is load-bearing: the
 * {@code UNIQUE(actor_user_id, idempotency_key_hash)} violation of a
 * concurrent same-key loser must surface OUTSIDE this transaction so a
 * fresh read-only lookup can resolve the winner (an aborted PostgreSQL
 * transaction cannot serve that lookup).
 *
 * <p>Lock order (§8, deadlock-free by construction): rate bucket
 * (leaf-most, self-committing via the limiter's REQUIRES_NEW) →
 * <b>source exam row</b> (through {@code loadPublishedSnapshot}) →
 * <b>publication row</b>. Community publish/unpublish/hide/restore and
 * PATCH take only the publication row (or no lock); exam archive takes
 * only the exam row; no cycle exists.
 */
@Service
public class CommunityForkExecutor {

    static final String PERMISSION_FORK = "COMMUNITY_FORK";

    private final CommunityPublicationRepository publications;
    private final CommunityForkRecordRepository forkRecords;
    private final CommunityRateLimiter rateLimiter;
    private final CommunityVisibilityStore visibilityStore;
    private final ExamCommunityPort examCommunityPort;
    private final QuestionCommunityPort questionCommunityPort;

    public CommunityForkExecutor(CommunityPublicationRepository publications,
            CommunityForkRecordRepository forkRecords,
            CommunityRateLimiter rateLimiter,
            CommunityVisibilityStore visibilityStore,
            ExamCommunityPort examCommunityPort,
            QuestionCommunityPort questionCommunityPort) {
        this.publications = publications;
        this.forkRecords = forkRecords;
        this.rateLimiter = rateLimiter;
        this.visibilityStore = visibilityStore;
        this.examCommunityPort = examCommunityPort;
        this.questionCommunityPort = questionCommunityPort;
    }

    /**
     * One atomic fork. Runs under the caller-agnostic transaction; the
     * orchestrator has already authenticated the actor, validated and
     * hashed the idempotency key, and missed on the read-only pre-check.
     */
    @Transactional
    public CommunityForkService.ForkOutcome forkWithinTransaction(
            Long actorUserId, Long publicationId, String idempotencyKeyHash) {
        // §8 step 3 / §9: admission is the first statement of the write
        // transaction (bucket lock stays leaf-most; denied requests
        // consume and saturate).
        rateLimiter.admitUser(CommunityRateLimiter.Policy.FORK, actorUserId);

        // exam_id is immutable on the publication row — an unlocked read
        // is sufficient to know WHICH exam row to lock first (§8 order:
        // exam before publication).
        CommunityPublication probe = publications.findById(publicationId)
                .orElseThrow(() -> new CommunityException(CommunityErrorCode.COMMUNITY_NOT_FOUND));

        // §8 step 3: lock the SOURCE EXAM row first (the port locks via
        // findByIdForUpdate and loads the immutable composition inside
        // that lock), then the publication row below.
        ExamCommunityPort.PublishedCompositionSnapshot snapshot = examCommunityPort
                .loadPublishedSnapshot(probe.getExamId())
                .orElseThrow(() -> new CommunityException(CommunityErrorCode.COMMUNITY_NOT_FOUND));

        CommunityPublication publication = publications.findByIdForUpdate(publicationId)
                .orElseThrow(() -> new CommunityException(CommunityErrorCode.COMMUNITY_NOT_FOUND));

        // §8 step 4: recheck under lock — the §4 visibility predicate
        // (shared verbatim via the store) then the reuse policy.
        if (!visibilityStore.isVisible(publicationId)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_NOT_FOUND);
        }
        if (publication.getReusePolicy() != CommunityReusePolicy.ALLOW_FORK) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_FORK_NOT_ALLOWED);
        }

        // §8 step 6: independent bank + questions + version-1 rows owned
        // by the requester (four types; answers included).
        QuestionCommunityPort.QuestionCloneResult clone =
                questionCommunityPort.cloneForFork(snapshot, actorUserId);

        // §8 step 7: the DRAFT target with remapped provenance ids; bank
        // owner == exam owner holds by construction, so the requester's
        // existing edit/publish APIs work on it unchanged.
        Long targetExamId = examCommunityPort.createForkDraft(new ExamCommunityPort.ForkDraftSpec(
                actorUserId, publication.getExamId(), snapshot, clone.bySourceQuestionId()));

        // §8 step 8: explicit saveAndFlush so the idempotency unique
        // violation surfaces INSIDE the transaction (Hibernate flush
        // order is unspecified otherwise).
        CommunityForkRecord record = new CommunityForkRecord(actorUserId, idempotencyKeyHash,
                publicationId, publication.getExamId(), targetExamId,
                publication.getTitle(), publication.getAuthorAlias());
        forkRecords.saveAndFlush(record);

        return new CommunityForkService.ForkOutcome(targetExamId, publicationId,
                publication.getTitle(), publication.getAuthorAlias(), record.getCreatedAt(), true);
    }

    /**
     * The §8 step 9 winner lookup, executed by the orchestrator after a
     * concurrent same-key loss: a plain repository read OUTSIDE any
     * enclosing transaction (the loser's transaction has fully rolled
     * back by the time this runs), i.e. a fresh read-only transaction.
     */
    Optional<CommunityForkRecord> findRecord(Long actorUserId, String idempotencyKeyHash) {
        return forkRecords.findByActorUserIdAndIdempotencyKeyHash(actorUserId, idempotencyKeyHash);
    }

}
