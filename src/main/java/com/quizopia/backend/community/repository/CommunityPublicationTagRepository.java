package com.quizopia.backend.community.repository;

import com.quizopia.backend.community.domain.model.CommunityPublicationTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Repository for the publication↔tag link table. Tag sets are replaced
 * wholesale on PATCH ({@link #deleteByPublicationId} + inserts).
 */
public interface CommunityPublicationTagRepository extends JpaRepository<CommunityPublicationTag, Long> {

    @Modifying
    @Query("DELETE FROM CommunityPublicationTag t WHERE t.publicationId = :publicationId")
    int deleteByPublicationId(@Param("publicationId") Long publicationId);

    /**
     * Labels per publication for owner list/detail rendering, ordered
     * deterministically (single query, no N+1).
     */
    @Query(value = "SELECT pt.publication_id, t.label FROM community_publication_tags pt"
            + " JOIN community_tags t ON t.id = pt.tag_id"
            + " WHERE pt.publication_id IN (:publicationIds) ORDER BY t.label", nativeQuery = true)
    List<Object[]> findLabelsByPublicationIds(@Param("publicationIds") List<Long> publicationIds);
}
