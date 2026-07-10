package com.hhtuann.backend.classroom.application;

import com.hhtuann.backend.academic.domain.model.StudentProfile;
import com.hhtuann.backend.academic.domain.model.TeacherProfile;
import com.hhtuann.backend.academic.repository.StudentProfileRepository;
import com.hhtuann.backend.academic.repository.TeacherProfileRepository;
import com.hhtuann.backend.classroom.domain.model.Classroom;
import com.hhtuann.backend.classroom.domain.model.ClassroomMember;
import com.hhtuann.backend.classroom.dto.AddMembersRequest;
import com.hhtuann.backend.classroom.dto.AddMembersResponse;
import com.hhtuann.backend.classroom.dto.ClassroomDetailView;
import com.hhtuann.backend.classroom.dto.ClassroomDetailView.MemberView;
import com.hhtuann.backend.classroom.dto.ClassroomMemberResponse;
import com.hhtuann.backend.classroom.dto.ClassroomResponse;
import com.hhtuann.backend.classroom.dto.CreateClassroomRequest;
import com.hhtuann.backend.classroom.dto.MyClassroomsResponse;
import com.hhtuann.backend.classroom.dto.UpdateClassroomRequest;
import com.hhtuann.backend.classroom.exception.ClassroomErrorCode;
import com.hhtuann.backend.classroom.exception.ClassroomException;
import com.hhtuann.backend.classroom.repository.ClassroomMemberRepository;
import com.hhtuann.backend.classroom.repository.ClassroomRepository;
import com.hhtuann.backend.identity.repository.RolePermissionRepository;
import com.hhtuann.backend.identity.repository.UserRoleRepository;
import com.hhtuann.backend.question.dto.PageResponse;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Application service for the Classroom API. Enforces DB-backed permissions +
 * school scope + ownership at the service layer (deny by default), mirroring
 * {@code AcademicService} (permission/school) and
 * {@code ExamSessionParticipantService} (bulk add-members partial success,
 * paginated list with batch display-name fetch).
 */
@Service
public class ClassroomService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SORT_ALLOWLIST = Set.of("addedAt");
    private static final String CODE_CONFLICT_INDEX = "uk_classrooms_owner_code_ci";
    private static final String MEMBER_DUPLICATE_CONSTRAINT = "uk_classroom_members_classroom_student";

    private final TeacherProfileRepository teacherProfileRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final ClassroomRepository classroomRepository;
    private final ClassroomMemberRepository memberRepository;
    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final EntityManager entityManager;
    private final Clock clock;
    private final com.hhtuann.backend.notification.application.NotificationService notificationService;

    public ClassroomService(TeacherProfileRepository teacherProfileRepository,
            StudentProfileRepository studentProfileRepository,
            ClassroomRepository classroomRepository,
            ClassroomMemberRepository memberRepository,
            UserRoleRepository userRoleRepository,
            RolePermissionRepository rolePermissionRepository,
            EntityManager entityManager,
            Clock clock,
            com.hhtuann.backend.notification.application.NotificationService notificationService) {
        this.teacherProfileRepository = teacherProfileRepository;
        this.studentProfileRepository = studentProfileRepository;
        this.classroomRepository = classroomRepository;
        this.memberRepository = memberRepository;
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.entityManager = entityManager;
        this.clock = clock;
        this.notificationService = notificationService;
    }

    // ============================================================
    // POST /api/classrooms
    // ============================================================

    @Transactional
    public ClassroomResponse createClassroom(Long userId, CreateClassroomRequest request) {
        requirePermission(userId, "CLASSROOM_CREATE");
        TeacherProfile profile = resolveTeacherProfile(userId);

        if (classroomRepository.existsByOwnerTeacherIdAndCodeIgnoreCase(profile.getId(), request.code())) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_CODE_CONFLICT);
        }

        Classroom classroom = new Classroom(profile.getSchoolId(), profile.getId(), request.code(), request.name());
        classroom.setDescription(request.description());
        try {
            classroomRepository.saveAndFlush(classroom);
        } catch (DataIntegrityViolationException ex) {
            if (isCodeConflict(ex)) {
                throw new ClassroomException(ClassroomErrorCode.CLASSROOM_CODE_CONFLICT);
            }
            throw ex;
        }
        return toResponse(classroom, 0L);
    }

    // ============================================================
    // GET /api/classrooms/my
    // ============================================================

    @Transactional(readOnly = true)
    public MyClassroomsResponse listMyClassrooms(Long userId) {
        requirePermission(userId, "CLASSROOM_READ");
        TeacherProfile profile = resolveTeacherProfile(userId);
        // "My" classrooms: a teacher's own classrooms. Not paginated in Phase 1
        // (small N per teacher); reuse the page query at a large size.
        Page<Classroom> page = classroomRepository.findByOwnerTeacherIdOrderByCreatedAtDescIdDesc(
                profile.getId(), PageRequest.of(0, MAX_PAGE_SIZE));
        Map<Long, Long> counts = batchMemberCounts(
                page.getContent().stream().map(Classroom::getId).toList());
        List<ClassroomResponse> items = page.getContent().stream()
                .map(c -> toResponse(c, counts.getOrDefault(c.getId(), 0L)))
                .toList();
        return new MyClassroomsResponse(items);
    }

    // ============================================================
    // GET /api/classrooms/{id}
    // ============================================================

    @Transactional(readOnly = true)
    public ClassroomDetailView getClassroom(Long userId, Long classroomId) {
        requirePermission(userId, "CLASSROOM_READ");
        TeacherProfile profile = resolveTeacherProfile(userId);
        Classroom classroom = requireOwnedClassroom(profile, classroomId);

        List<ClassroomMember> members = memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId);
        Map<Long, StudentProfile> profiles = batchStudentProfiles(
                members.stream().map(ClassroomMember::getStudentProfileId).toList());
        Map<Long, String> displayNames = batchDisplayNames(
                profiles.values().stream().map(StudentProfile::getUserId).collect(Collectors.toSet()));

        List<MemberView> memberViews = members.stream()
                .map(m -> {
                    StudentProfile sp = profiles.get(m.getStudentProfileId());
                    return new MemberView(
                            m.getStudentProfileId(),
                            sp != null ? sp.getStudentCode() : null,
                            sp != null ? displayNames.get(sp.getUserId()) : null,
                            m.getAddedAt());
                })
                .toList();
        return new ClassroomDetailView(
                classroom.getId(), classroom.getCode(), classroom.getName(),
                classroom.getDescription(), classroom.getStatus().name(),
                classroom.getOwnerTeacherId(), classroom.getCreatedAt(),
                members.size(), memberViews);
    }

    // ============================================================
    // PUT /api/classrooms/{id}
    // ============================================================

    @Transactional
    public ClassroomResponse updateClassroom(Long userId, Long classroomId, UpdateClassroomRequest request) {
        requirePermission(userId, "CLASSROOM_UPDATE");
        TeacherProfile profile = resolveTeacherProfile(userId);
        Classroom classroom = requireOwnedClassroom(profile, classroomId);
        classroom.update(request.name(), request.description());
        classroomRepository.saveAndFlush(classroom);
        return toResponse(classroom, memberRepository.countByClassroomId(classroomId));
    }

    // ============================================================
    // POST /api/classrooms/{id}/members — bulk add (partial success)
    // ============================================================

    @SuppressWarnings("null")
    @Transactional
    public AddMembersResponse addMembers(Long userId, Long classroomId, AddMembersRequest request) {
        requirePermission(userId, "CLASSROOM_MEMBER_ADD");
        TeacherProfile profile = resolveTeacherProfile(userId);
        Classroom classroom = requireOwnedClassroom(profile, classroomId);

        // De-duplicate request (preserve first occurrence).
        List<Long> rawIds = request.studentProfileIds();
        Set<Long> seen = new LinkedHashSet<>();
        List<Long> duplicatedInRequest = new ArrayList<>();
        for (Long id : rawIds) {
            if (!seen.add(id)) {
                duplicatedInRequest.add(id);
            }
        }
        List<Long> distinctIds = new ArrayList<>(seen);

        // Batch-check existing memberships.
        Set<Long> existing = memberRepository
                .findAllByClassroomIdAndStudentProfileIdIn(classroomId, distinctIds).stream()
                .map(ClassroomMember::getStudentProfileId)
                .collect(Collectors.toSet());

        // Batch-load student profiles + validate same school.
        Map<Long, StudentProfile> profiles = studentProfileRepository.findAllById(distinctIds).stream()
                .collect(Collectors.toMap(StudentProfile::getId, p -> p));

        List<Long> toInsert = new ArrayList<>();
        List<Long> duplicated = new ArrayList<>(duplicatedInRequest);
        List<Long> invalid = new ArrayList<>();
        for (Long id : distinctIds) {
            StudentProfile sp = profiles.get(id);
            if (sp == null) {
                invalid.add(id); // missing
            } else if (!sp.getSchoolId().equals(classroom.getSchoolId())) {
                invalid.add(id); // cross-school
            } else if (existing.contains(id)) {
                duplicated.add(id); // already a member
            } else {
                toInsert.add(id); // valid
            }
        }

        if (!toInsert.isEmpty()) {
            try {
                for (Long spId : toInsert) {
                    memberRepository.saveAndFlush(
                            new ClassroomMember(classroomId, spId, classroom.getSchoolId()));
                }
            } catch (DataIntegrityViolationException ex) {
                if (isMemberDuplicate(ex)) {
                    throw new ClassroomException(ClassroomErrorCode.CLASSROOM_MEMBER_DUPLICATE);
                }
                throw ex;
            }
        }

        Collections.sort(duplicated);
        Collections.sort(invalid);
        // Notify the teacher + each added student.
        if (!toInsert.isEmpty()) {
            notificationService.create(userId,
                    com.hhtuann.backend.notification.domain.model.NotificationType.STUDENT_JOINED_CLASS,
                    "Students joined class",
                    toInsert.size() + " student" + (toInsert.size() == 1 ? "" : "s") + " added to " + classroom.getName(),
                    "/classes/" + classroomId);
            // Notify each student they were added to the class.
            for (Long spId : toInsert) {
                StudentProfile sp = profiles.get(spId);
                if (sp != null) {
                    notificationService.create(sp.getUserId(),
                            com.hhtuann.backend.notification.domain.model.NotificationType.ADDED_TO_CLASS,
                            "Added to class",
                            "You were added to " + classroom.getName(),
                            "/sessions");
                }
            }
        }
        return new AddMembersResponse(toInsert.size(), duplicated, invalid);
    }

    // ============================================================
    // DELETE /api/classrooms/{id}/members/{studentProfileId}
    // ============================================================

    @Transactional
    public void removeMember(Long userId, Long classroomId, Long studentProfileId) {
        requirePermission(userId, "CLASSROOM_MEMBER_REMOVE");
        TeacherProfile profile = resolveTeacherProfile(userId);
        Classroom classroom = requireOwnedClassroom(profile, classroomId);
        // Idempotent: deleteBy returns rows affected; missing member → 200 (no-op).
        memberRepository.deleteByClassroomIdAndStudentProfileId(classroomId, studentProfileId);
        // Notify the removed student.
        studentProfileRepository.findById(studentProfileId).ifPresent(sp -> {
            notificationService.create(sp.getUserId(),
                    com.hhtuann.backend.notification.domain.model.NotificationType.USER_STATUS_CHANGED,
                    "Removed from class",
                    "You were removed from " + classroom.getName(),
                    "/sessions");
        });
    }

    // ============================================================
    // GET /api/classrooms/{id}/members — paginated
    // ============================================================

    @SuppressWarnings("null")
    @Transactional(readOnly = true)
    public PageResponse<ClassroomMemberResponse> listMembers(
            Long userId, Long classroomId, int page, int size, String sort) {
        requirePermission(userId, "CLASSROOM_MEMBER_READ");
        TeacherProfile profile = resolveTeacherProfile(userId);
        requireOwnedClassroom(profile, classroomId);

        PageRequest pageable = safePageable(page, size, sort);
        Page<ClassroomMember> members = memberRepository.findByClassroomIdOrderByIdAsc(classroomId, pageable);

        List<Long> spIds = members.getContent().stream().map(ClassroomMember::getStudentProfileId).toList();
        Map<Long, StudentProfile> profiles = batchStudentProfiles(spIds);
        Map<Long, String> displayNames = batchDisplayNames(
                profiles.values().stream().map(StudentProfile::getUserId).collect(Collectors.toSet()));

        List<ClassroomMemberResponse> items = members.getContent().stream()
                .map(m -> {
                    StudentProfile sp = profiles.get(m.getStudentProfileId());
                    return new ClassroomMemberResponse(
                            m.getId(),
                            m.getStudentProfileId(),
                            sp != null ? sp.getStudentCode() : null,
                            sp != null ? displayNames.get(sp.getUserId()) : null,
                            m.getAddedAt());
                })
                .toList();
        return new PageResponse<>(items, members.getNumber(), members.getSize(),
                members.getTotalElements(), members.getTotalPages(), pageable.getSort().toString());
    }

    // ============================================================
    // Helpers
    // ============================================================

    private Classroom requireOwnedClassroom(TeacherProfile profile, Long classroomId) {
        Classroom classroom = classroomRepository.findById(classroomId)
                .orElseThrow(() -> new ClassroomException(ClassroomErrorCode.CLASSROOM_NOT_FOUND));
        if (!classroom.getOwnerTeacherId().equals(profile.getId())
                || !classroom.getSchoolId().equals(profile.getSchoolId())) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_ACCESS_DENIED);
        }
        return classroom;
    }

    private TeacherProfile resolveTeacherProfile(Long userId) {
        return teacherProfileRepository.findByUserId(userId)
                .orElseThrow(() -> {
                    List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(userId, Instant.now(clock));
                    if (roles.contains("TEACHER")) {
                        return new ClassroomException(ClassroomErrorCode.CLASSROOM_TEACHER_PROFILE_NOT_FOUND);
                    }
                    return new ClassroomException(ClassroomErrorCode.CLASSROOM_ACCESS_DENIED);
                });
    }

    private void requirePermission(Long userId, String permissionCode) {
        List<String> permissions = rolePermissionRepository
                .findEffectivePermissionCodesByUserId(userId, Instant.now(clock));
        if (!permissions.contains(permissionCode)) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_ACCESS_DENIED);
        }
    }

    private ClassroomResponse toResponse(Classroom c, long memberCount) {
        return new ClassroomResponse(
                c.getId(), c.getCode(), c.getName(), c.getDescription(),
                c.getStatus().name(), memberCount, c.getOwnerTeacherId(), c.getCreatedAt());
    }

    private Map<Long, Long> batchMemberCounts(List<Long> classroomIds) {
        if (classroomIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        for (Long id : classroomIds) {
            counts.put(id, memberRepository.countByClassroomId(id));
        }
        return counts;
    }

    private Map<Long, StudentProfile> batchStudentProfiles(List<Long> studentProfileIds) {
        if (studentProfileIds.isEmpty()) {
            return Map.of();
        }
        return studentProfileRepository.findAllById(studentProfileIds).stream()
                .collect(Collectors.toMap(StudentProfile::getId, p -> p));
    }

    /** Single Hibernate-tracked projection (no N+1). */
    private Map<Long, String> batchDisplayNames(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = entityManager.createQuery(
                "SELECT u.id, u.displayName FROM User u WHERE u.id IN :ids", Object[].class)
                .setParameter("ids", userIds)
                .getResultList();
        Map<Long, String> names = new HashMap<>();
        for (Object[] row : rows) {
            names.put((Long) row[0], (String) row[1]);
        }
        return names;
    }

    private PageRequest safePageable(int page, int size, String sort) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Sort safeSort = Sort.by(Sort.Direction.DESC, "addedAt");
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            String prop = parts[0].trim();
            Sort.Direction dir = (parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc"))
                    ? Sort.Direction.ASC
                    : Sort.Direction.DESC;
            if (SORT_ALLOWLIST.contains(prop)) {
                safeSort = Sort.by(dir, prop);
            } else {
                throw new ClassroomException(ClassroomErrorCode.CLASSROOM_VALIDATION_ERROR);
            }
        }
        return PageRequest.of(safePage, safeSize, safeSort);
    }

    private static boolean isCodeConflict(DataIntegrityViolationException ex) {
        return constraintNameContains(ex, CODE_CONFLICT_INDEX);
    }

    private static boolean isMemberDuplicate(DataIntegrityViolationException ex) {
        return constraintNameContains(ex, MEMBER_DUPLICATE_CONSTRAINT);
    }

    private static boolean constraintNameContains(Throwable ex, String fragment) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                if (name != null && name.contains(fragment)) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }
}
