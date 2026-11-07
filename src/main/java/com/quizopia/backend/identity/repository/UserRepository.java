package com.quizopia.backend.identity.repository;

import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.domain.model.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * Repository for the {@link User} entity.
 *
 * <p>Supports case-insensitive lookups and existence checks for username
 * and email, so account creation and authentication can detect duplicates
 * regardless of letter casing.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** Pessimistic row lock on one user — serializes per-account operations. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.id = :id")
    Optional<User> findForUpdateById(@Param("id") Long id);

    @Query("select u.id from User u where lower(u.email) = lower(:email)")
    Optional<Long> findIdByEmailIgnoreCase(@Param("email") String email);

    @Query("select u.id from User u where lower(u.username) = lower(:username)")
    Optional<Long> findIdByUsernameIgnoreCase(@Param("username") String username);

    Optional<User> findByUsernameIgnoreCase(String username);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    /**
     * Identity-M3 reconciliation: fresh, row-locked user read. A locking
     * query re-reads committed state (defeats a stale managed instance) and
     * serializes against concurrent email changes through the update path.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
