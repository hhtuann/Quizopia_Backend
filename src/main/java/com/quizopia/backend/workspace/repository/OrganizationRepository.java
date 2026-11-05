package com.quizopia.backend.workspace.repository;

import com.quizopia.backend.workspace.domain.model.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    Optional<Organization> findByLegacySchoolId(Long legacySchoolId);

    Optional<Organization> findByCodeIgnoreCase(String code);
}
