package com.hhtuann.backend.exam.repository;

import com.hhtuann.backend.exam.domain.model.ExamVersion;
import com.hhtuann.backend.exam.domain.model.ExamVersionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExamVersionRepository extends JpaRepository<ExamVersion, Long> {

    Optional<ExamVersion> findByExamIdAndVersionNumber(Long examId, Integer versionNumber);

    Optional<ExamVersion> findFirstByExamIdAndStatus(Long examId, ExamVersionStatus status);

    Optional<ExamVersion> findFirstByExamIdAndStatusOrderByVersionNumberDesc(Long examId, ExamVersionStatus status);

    List<ExamVersion> findAllByExamIdOrderByVersionNumberDesc(Long examId);

    boolean existsByExamIdAndStatus(Long examId, ExamVersionStatus status);

    /**
     * Batch query: returns lightweight (examId, status) projections for the
     * given exam IDs. A single query replaces 2N per-exam {@code exists} calls.
     * Both DRAFT and PUBLISHED statuses are returned in one pass.
     */
    @Query("SELECT ev.examId AS examId, ev.status AS status "
            + "FROM ExamVersion ev WHERE ev.examId IN :examIds")
    List<ExamVersionStatusProjection> findStatusByExamIdsIn(@Param("examIds") List<Long> examIds);
}
