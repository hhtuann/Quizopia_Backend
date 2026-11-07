package com.quizopia.backend.community.repository;

import com.quizopia.backend.community.domain.model.CommunityPublication;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Repository for {@link CommunityPublication}. Publish/unpublish take the
 * pessimistic row lock ({@link #findByIdForUpdate}) — the ONLY lock
 * community state transitions acquire (ADR-006 §4/§8 lock order; the exam
 * row is never locked here). PATCH relies on the JPA {@code @Version}
 * token instead.
 */
public interface CommunityPublicationRepository extends JpaRepository<CommunityPublication, Long> {

    boolean existsByExamId(Long examId);

    Optional<CommunityPublication> findByExamId(Long examId);

    /** Publication row lock for publish/unpublish state transitions. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM CommunityPublication p WHERE p.id = :id")
    Optional<CommunityPublication> findByIdForUpdate(@Param("id") Long id);

    /**
     * Guarded optimistic bump for PATCH (ADR-006 §4): atomically advances
     * the {@code @Version} token only while the row still carries
     * {@code :expected}. Returns 0 when a concurrent writer moved the row
     * first — the authoritative CAS for tag-only PATCHes, whose entities
     * are never dirty and therefore never trigger the JPA version check.
     * {@code updated_at} moves in the same statement (no entity flush
     * follows on that path).
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE CommunityPublication p SET p.version = p.version + 1, p.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE p.id = :id AND p.version = :expected")
    int bumpVersionGuarded(@Param("id") Long id, @Param("expected") Integer expected);

    Page<CommunityPublication> findByPublisherUserId(Long publisherUserId, Pageable pageable);
}
