package com.hhtuann.backend.identity.repository;

import com.hhtuann.backend.identity.domain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for the {@link User} entity.
 *
 * <p>Supports case-insensitive lookups and existence checks for username
 * and email, so account creation and authentication can detect duplicates
 * regardless of letter casing.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsernameIgnoreCase(String username);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);
}
