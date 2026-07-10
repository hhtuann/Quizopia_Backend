package com.hhtuann.backend.academic.repository;

import com.hhtuann.backend.academic.domain.model.School;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link School}. Supports case-insensitive lookup by business
 * code (the database unique index is on {@code LOWER(code)}).
 */
public interface SchoolRepository extends JpaRepository<School, Long> {

    Optional<School> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<School> findAllByOrderByCodeAsc();
}
