package com.quizopia.backend.workspace.repository;

import com.quizopia.backend.workspace.domain.model.WorkspaceMembership;
import com.quizopia.backend.workspace.domain.model.WorkspaceMembershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WorkspaceMembershipRepository extends JpaRepository<WorkspaceMembership, Long> {

    Optional<WorkspaceMembership> findByWorkspaceIdAndUserId(Long workspaceId, Long userId);

    List<WorkspaceMembership> findByUserIdAndStatusOrderByCreatedAtAsc(Long userId, WorkspaceMembershipStatus status);

    List<WorkspaceMembership> findByWorkspaceIdAndStatusOrderByCreatedAtAsc(Long workspaceId, WorkspaceMembershipStatus status);

    boolean existsByWorkspaceIdAndUserIdAndStatus(Long workspaceId, Long userId, WorkspaceMembershipStatus status);

    @Query("""
            SELECT m FROM WorkspaceMembership m
            WHERE m.userId = :userId
              AND m.workspaceId IN :workspaceIds
              AND m.status = com.quizopia.backend.workspace.domain.model.WorkspaceMembershipStatus.ACTIVE
            """)
    List<WorkspaceMembership> findActiveByUserIdAndWorkspaceIds(
            @Param("userId") Long userId, @Param("workspaceIds") Collection<Long> workspaceIds);
}
