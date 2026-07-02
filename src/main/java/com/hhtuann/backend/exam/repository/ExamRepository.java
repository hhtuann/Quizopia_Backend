package com.hhtuann.backend.exam.repository;

import com.hhtuann.backend.exam.domain.model.Exam;
import com.hhtuann.backend.exam.domain.model.ExamStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExamRepository extends JpaRepository<Exam, Long> {

    Optional<Exam> findByIdAndOwnerTeacherId(Long id, Long ownerTeacherId);

    boolean existsByOwnerTeacherIdAndCodeIgnoreCase(Long ownerTeacherId, String code);

    List<Exam> findAllByOwnerTeacherId(Long ownerTeacherId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Exam e WHERE e.id = :id")
    Optional<Exam> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT e FROM Exam e WHERE e.ownerTeacherId = :ownerId "
            + "AND (COALESCE(:search, '') = '' "
            + "  OR LOWER(e.code) LIKE LOWER(CONCAT('%', :search, '%')) "
            + "  OR LOWER(e.title) LIKE LOWER(CONCAT('%', :search, '%'))) "
            + "AND (:subjectId IS NULL OR e.subjectId = :subjectId) "
            + "AND (:status IS NULL OR e.status = :status)")
    Page<Exam> findOwnedByTeacher(
            @Param("ownerId") Long ownerId,
            @Param("search") String search,
            @Param("subjectId") Long subjectId,
            @Param("status") ExamStatus status,
            Pageable pageable);
}
