package com.quizopia.backend.community.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * JPA entity mapping {@code community_publication_tags} (ADR-006 §3):
 * the publication↔tag link table (composite PK, cascades from both
 * parents). Rows are replaced wholesale on owner PATCH (replace-set
 * semantics, ≤ 5 distinct tags).
 */
@Entity
@Table(name = "community_publication_tags")
@IdClass(CommunityPublicationTag.TagLinkId.class)
public class CommunityPublicationTag {

    @Id
    @Column(name = "publication_id", nullable = false)
    private Long publicationId;

    @Id
    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    protected CommunityPublicationTag() {
    }

    public CommunityPublicationTag(Long publicationId, Long tagId) {
        this.publicationId = Objects.requireNonNull(publicationId, "publicationId must not be null");
        this.tagId = Objects.requireNonNull(tagId, "tagId must not be null");
    }

    public Long getPublicationId() {
        return publicationId;
    }

    public Long getTagId() {
        return tagId;
    }

    /** Composite key ({@code publication_id, tag_id}). */
    public static class TagLinkId implements Serializable {

        private Long publicationId;
        private Long tagId;

        public TagLinkId() {
        }

        public TagLinkId(Long publicationId, Long tagId) {
            this.publicationId = publicationId;
            this.tagId = tagId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TagLinkId other)) {
                return false;
            }
            return Objects.equals(publicationId, other.publicationId)
                    && Objects.equals(tagId, other.tagId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(publicationId, tagId);
        }
    }
}
