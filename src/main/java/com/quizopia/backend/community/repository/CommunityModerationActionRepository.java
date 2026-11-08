package com.quizopia.backend.community.repository;

import com.quizopia.backend.community.domain.model.CommunityModerationAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * Repository for {@link CommunityModerationAction} — the append-only
 * moderation audit trail (ADR-006 §3 V27 / §5). Deliberately extends the
 * bare {@link Repository} marker (NOT {@code JpaRepository}/{@code
 * CrudRepository}): the declared surface is {@link #save} + reads only.
 * No update, delete, saveAll or flush method exists at all — the audit
 * trail is append-only by repository surface (identity-audit precedent,
 * hardened), asserted by {@code CommunityModerationActionRepositorySurfaceTest}.
 */
public interface CommunityModerationActionRepository
        extends Repository<CommunityModerationAction, Long> {

    /** The ONLY write on the audit trail: append one immutable row. */
    <S extends CommunityModerationAction> S save(S action);

    /** Per-publication history, newest-first (created_at DESC, id DESC). */
    Page<CommunityModerationAction> findByPublicationIdOrderByCreatedAtDescIdDesc(
            Long publicationId, Pageable pageable);

    /** Audit rows written against one report (dismiss/resolve journal). */
    List<CommunityModerationAction> findByReportIdOrderByCreatedAtDescIdDesc(Long reportId);
}
