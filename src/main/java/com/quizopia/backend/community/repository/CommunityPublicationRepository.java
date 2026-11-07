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

    Page<CommunityPublication> findByPublisherUserId(Long publisherUserId, Pageable pageable);
}
