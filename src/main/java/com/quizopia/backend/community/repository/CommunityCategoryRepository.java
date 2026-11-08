package com.quizopia.backend.community.repository;

import com.quizopia.backend.community.domain.model.CommunityCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Repository for the migration-managed {@link CommunityCategory} catalog (read-only). */
public interface CommunityCategoryRepository extends JpaRepository<CommunityCategory, Long> {

    List<CommunityCategory> findAllByOrderByPositionAscCodeAsc();
}
