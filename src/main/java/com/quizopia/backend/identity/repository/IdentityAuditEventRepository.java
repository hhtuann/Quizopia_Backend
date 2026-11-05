package com.quizopia.backend.identity.repository;

import com.quizopia.backend.identity.domain.model.IdentityAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Repository for {@link IdentityAuditEvent} (append-only audit trail). */
public interface IdentityAuditEventRepository extends JpaRepository<IdentityAuditEvent, Long> {

    List<IdentityAuditEvent> findByUserIdOrderByCreatedAtDesc(Long userId);
}
