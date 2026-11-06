package com.quizopia.backend.identity.repository;

import com.quizopia.backend.identity.domain.model.OauthLinkRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link OauthLinkRequest} (review M1-06). Lookup by the
 * SHA-256 of the state parameter only; the pessimistic lock makes callback
 * consumption exactly-once under concurrency.
 */
public interface OauthLinkRequestRepository extends JpaRepository<OauthLinkRequest, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from OauthLinkRequest r where r.stateHash = :stateHash")
    Optional<OauthLinkRequest> findForUpdateByStateHash(@Param("stateHash") String stateHash);

    @Query("select r.user.id from OauthLinkRequest r where r.stateHash = :stateHash")
    Optional<Long> findUserIdByStateHash(@Param("stateHash") String stateHash);
}
