package com.quizopia.backend.community.repository;

import com.quizopia.backend.community.domain.model.CommunityTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Repository for {@link CommunityTag}. Labels are created on demand via
 * the {@code INSERT … ON CONFLICT DO NOTHING} upsert (roster-membership
 * precedent: a caught constraint violation would abort the enclosing
 * PostgreSQL transaction, so the upsert + re-read pattern is mandatory).
 */
public interface CommunityTagRepository extends JpaRepository<CommunityTag, Long> {

    /**
     * Inserts the (already normalized) label when absent. Returns 1 when
     * this call created the row, 0 when it already existed.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "INSERT INTO community_tags (label) VALUES (:label)"
            + " ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("label") String label);

    List<CommunityTag> findByLabelIn(Collection<String> labels);
}
