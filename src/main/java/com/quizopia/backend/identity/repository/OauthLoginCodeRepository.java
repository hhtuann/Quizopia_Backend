package com.quizopia.backend.identity.repository;

import com.quizopia.backend.identity.domain.model.OauthLoginCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Repository for {@link OauthLoginCode}. Lookup by hash only; the pessimistic
 * lock makes code consumption exactly-once under concurrency.
 */
public interface OauthLoginCodeRepository extends JpaRepository<OauthLoginCode, java.util.UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from OauthLoginCode c where c.codeHash = :codeHash")
    Optional<OauthLoginCode> findForUpdateByCodeHash(@Param("codeHash") String codeHash);

    @Query("select c.user.id from OauthLoginCode c where c.codeHash = :codeHash")
    Optional<Long> findUserIdByCodeHash(@Param("codeHash") String codeHash);
}
