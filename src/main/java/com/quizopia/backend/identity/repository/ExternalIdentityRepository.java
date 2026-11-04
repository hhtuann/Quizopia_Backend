package com.quizopia.backend.identity.repository;

import com.quizopia.backend.identity.domain.model.ExternalIdentity;
import com.quizopia.backend.identity.domain.model.ExternalProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link ExternalIdentity}. Lookups always key on
 * (provider, issuer, subject) — never on email (ADR-002 §3).
 */
public interface ExternalIdentityRepository extends JpaRepository<ExternalIdentity, Long> {

    Optional<ExternalIdentity> findByProviderAndIssuerAndSubject(
            ExternalProvider provider, String issuer, String subject);
    boolean existsByUserIdAndProvider(Long userId, ExternalProvider provider);
}
