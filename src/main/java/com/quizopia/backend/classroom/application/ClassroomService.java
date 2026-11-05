package com.quizopia.backend.classroom.application;

import com.quizopia.backend.academic.domain.model.StudentProfile;
import com.quizopia.backend.academic.domain.model.TeacherProfile;
import com.quizopia.backend.academic.repository.StudentProfileRepository;
import com.quizopia.backend.academic.repository.TeacherProfileRepository;
import com.quizopia.backend.classroom.domain.model.Classroom;
import com.quizopia.backend.classroom.domain.model.ClassroomMember;
import com.quizopia.backend.classroom.dto.AddMembersRequest;
import com.quizopia.backend.classroom.dto.AddMembersResponse;
import com.quizopia.backend.classroom.dto.ClassroomDetailView;
import com.quizopia.backend.classroom.dto.ClassroomDetailView.MemberView;
import com.quizopia.backend.classroom.dto.ClassroomMemberResponse;
import com.quizopia.backend.classroom.dto.ClassroomResponse;
import com.quizopia.backend.classroom.dto.CreateClassroomRequest;
import com.quizopia.backend.classroom.dto.MyClassroomsResponse;
import com.quizopia.backend.classroom.dto.UpdateClassroomRequest;
import com.quizopia.backend.classroom.exception.ClassroomErrorCode;
import com.quizopia.backend.classroom.exception.ClassroomException;
import com.quizopia.backend.classroom.repository.ClassroomMemberRepository;
import com.quizopia.backend.classroom.repository.ClassroomRepository;
import com.quizopia.backend.identity.repository.RolePermissionRepository;
import com.quizopia.backend.identity.repository.UserRoleRepository;
import com.quizopia.backend.question.dto.PageResponse;
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
    private static final String CODE_CONFLICT_INDEX = "uk_classrooms_owner_user_code_ci";
    private static final String MEMBER_DUPLICATE_CONSTRAINT = "uk_classroom_members_classroom_";

    private final TeacherProfileRepository teacherProfileRepository;
    private final com.quizopia.backend.identity.repository.UserRepository userRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final ClassroomRepository classroomRepository;
    private final ClassroomMemberRepository memberRepository;
    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final EntityManager entityManager;
    private final Clock clock;
    private final com.quizopia.backend.notification.application.NotificationService notificationService;
    private final com.quizopia.backend.workspace.application.OrganizationWorkspaceResolver organizationWorkspaceResolver;
    private final com.quizopia.backend.workspace.application.WorkspaceAccessService workspaceAccessService;

    public ClassroomService(TeacherProfileRepository teacherProfileRepository,
            com.quizopia.backend.identity.repository.UserRepository userRepository,
            StudentProfileRepository studentProfileRepository,
            ClassroomRepository classroomRepository,
            ClassroomMemberRepository memberRepository,
            UserRoleRepository userRoleRepository,
            RolePermissionRepository rolePermissionRepository,
            EntityManager entityManager,
            Clock clock,
            com.quizopia.backend.notification.application.NotificationService notificationService,
            com.quizopia.backend.workspace.application.OrganizationWorkspaceResolver organizationWorkspaceResolver,
            com.quizopia.backend.workspace.application.WorkspaceAccessService workspaceAccessService) {
        this.teacherProfileRepository = teacherProfileRepository;
        this.userRepository = userRepository;
        this.studentProfileRepository = studentProfileRepository;
        this.classroomRepository = classroomRepository;
        this.memberRepository = memberRepository;
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.entityManager = entityManager;
        this.clock = clock;
        this.notificationService = notificationService;
        this.organizationWorkspaceResolver = organizationWorkspaceResolver;
        this.workspaceAccessService = workspaceAccessService;
    }

    // ============================================================
    // POST /api/classrooms
    // ============================================================

    @Transactional
    public ClassroomResponse createClassroom(Long userId, CreateClassroomRequest request) {
        requirePermission(userId, "CLASSROOM_CREATE");
        ClassroomOwnership ownership = resolveOwnership(userId);

        if (classroomRepository.existsByOwnerUserIdAndCodeIgnoreCase(userId, request.code())) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_CODE_CONFLICT);
        }

        Classroom classroom;
        if (ownership.profile() != null) {
            // Legacy: school-scoped, org workspace, editor membership required.
            Long workspaceId = requireOrganizationWorkspaceId(ownership.profile().getSchoolId());
            workspaceAccessService.requireContentEditor(userId, workspaceId);
            classroom = Classroom.legacy(workspaceId, ownership.profile().getSchoolId(),
                    ownership.profile().getId(), userId, request.code(), request.name());
        } else {
            // Identity-M3: independent teacher — PERSONAL workspace ownership.
            Long workspaceId = workspaceAccessService.requirePersonalWorkspaceId(userId);
            classroom = Classroom.independent(workspaceId, userId, request.code(), request.name());
        }
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

    /**
     * Identity-M3 dual-mode ownership resolution: a TEACHER with a
     * TeacherProfile keeps the legacy school/org semantics; a TEACHER
     * without one (self-upgraded, independent) gets personal-workspace
     * ownership. Non-teachers are denied.
     */
    private record ClassroomOwnership(TeacherProfile profile) {
    }

    private ClassroomOwnership resolveOwnership(Long userId) {
        TeacherProfile profile = teacherProfileRepository.findByUserId(userId).orElse(null);
        if (profile != null) {
            return new ClassroomOwnership(profile);
        }
        List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(userId, Instant.now(clock));
        if (roles.contains("TEACHER")) {
            return new ClassroomOwnership(null);
        }
        throw new ClassroomException(ClassroomErrorCode.CLASSROOM_ACCESS_DENIED);
    }

    // ============================================================
    // GET /api/classrooms/my
    // ============================================================

    @SuppressWarnings("null")
    @Transactional(readOnly = true)
    public MyClassroomsResponse listMyClassrooms(Long userId) {
        requirePermission(userId, "CLASSROOM_READ");
        // Identity-M3: "my" classrooms = classrooms owned by this USER
        // (both legacy and independent). Not paginated in Phase 1.
        Page<Classroom> page = classroomRepository.findByOwnerUserIdOrderByCreatedAtDescIdDesc(
                userId, PageRequest.of(0, MAX_PAGE_SIZE));
        // P0-2 (issue #4): hide classrooms in workspaces the user may no
        // longer access (revoked organization membership).
        java.util.Set<Long> accessible = new java.util.HashSet<>(
                workspaceAccessService.accessibleWorkspaceIds(userId));
        List<Classroom> visible = page.getContent().stream()
                .filter(c -> accessible.contains(c.getWorkspaceId()))
                .toList();
        Map<Long, Long> counts = batchMemberCounts(
                visible.stream().map(Classroom::getId).toList());
        List<ClassroomResponse> items = visible.stream()
                .map(c -> toResponse(c, counts.getOrDefault(c.getId(), 0L)))
                .toList();
        return new MyClassroomsResponse(items);
    }

    // ============================================================
    // GET /api/classrooms/{id}
    // ============================================================

    @SuppressWarnings("null")
    @Transactional(readOnly = true)
    public ClassroomDetailView getClassroom(Long userId, Long classroomId) {
        requirePermission(userId, "CLASSROOM_READ");
        Classroom classroom = requireOwnedClassroom(userId, classroomId);

        List<ClassroomMember> members = memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId);
        Map<Long, StudentProfile> profiles = batchStudentProfiles(
                members.stream().map(ClassroomMember::getStudentProfileId)
                        .filter(Objects::nonNull).toList());
        Map<Long, String> displayNames = batchDisplayNames(
                members.stream().map(ClassroomMember::getUserId).collect(Collectors.toSet()));

        List<MemberView> memberViews = members.stream()
                .map(m -> {
                    StudentProfile sp = m.getStudentProfileId() != null ? profiles.get(m.getStudentProfileId()) : null;
                    return new MemberView(
                            m.getStudentProfileId(),
                            sp != null ? sp.getStudentCode() : null,
                            displayNames.get(m.getUserId()),
                            m.getAddedAt());
                })
                .toList();
        return new ClassroomDetailView(
                classroom.getId(), classroom.getCode(), classroom.getName(),
                classroom.getDescription(), classroom.getStatus().name(),
                classroom.getOwnerTeacherId(), classroom.getOwnerUserId(),
                classroom.isIndependent(), classroom.getCreatedAt(),
                members.size(), memberViews);
    }

    // ============================================================
    // PUT /api/classrooms/{id}
    // ============================================================

    @Transactional
    public ClassroomResponse updateClassroom(Long userId, Long classroomId, UpdateClassroomRequest request) {
        requirePermission(userId, "CLASSROOM_UPDATE");
        Classroom classroom = requireOwnedClassroom(userId, classroomId);
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
        Classroom classroom = requireOwnedClassroom(userId, classroomId);

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

        // Batch-load profiles + batch-check existing memberships (user-based, V12).
        Map<Long, StudentProfile> profiles = studentProfileRepository.findAllById(distinctIds).stream()
                .collect(Collectors.toMap(StudentProfile::getId, p -> p));
        Set<Long> existingUsers = memberRepository
                .findAllByClassroomIdAndUserIdIn(classroomId,
                        profiles.values().stream().map(StudentProfile::getUserId).toList()).stream()
                .map(ClassroomMember::getUserId)
                .collect(Collectors.toSet());

        List<Long> toInsert = new ArrayList<>();
        List<Long> duplicated = new ArrayList<>(duplicatedInRequest);
        List<Long> invalid = new ArrayList<>();
        for (Long id : distinctIds) {
            StudentProfile sp = profiles.get(id);
            if (sp == null) {
                invalid.add(id); // missing
            } else if (!sp.getSchoolId().equals(classroom.getSchoolId())) {
                invalid.add(id); // cross-school
            } else if (existingUsers.contains(sp.getUserId())) {
                duplicated.add(id); // already a member (user-based)
            } else {
                toInsert.add(id); // valid
            }
        }

        if (!toInsert.isEmpty()) {
            try {
                for (Long spId : toInsert) {
                    StudentProfile sp = profiles.get(spId);
                    memberRepository.saveAndFlush(new ClassroomMember(
                            classroomId, sp.getUserId(), spId, classroom.getSchoolId()));
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
                    com.quizopia.backend.notification.domain.model.NotificationType.STUDENT_JOINED_CLASS,
                    "Students joined class",
                    toInsert.size() + " student" + (toInsert.size() == 1 ? "" : "s") + " added to "
                            + classroom.getName(),
                    "/classes/" + classroomId);
            // Notify each student they were added to the class.
            for (Long spId : toInsert) {
                StudentProfile sp = profiles.get(spId);
                if (sp != null) {
                    notificationService.create(sp.getUserId(),
                            com.quizopia.backend.notification.domain.model.NotificationType.ADDED_TO_CLASS,
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
        Classroom classroom = requireOwnedClassroom(userId, classroomId);
        // User-based removal (V12): resolve the profile's user, then remove the
        // membership row by user. Falls back to the legacy profile-scoped delete
        // for rows without a resolvable user. Idempotent by design.
        Long targetUserId = studentProfileRepository.findById(studentProfileId)
                .map(StudentProfile::getUserId)
                .orElse(null);
        long removed = 0;
        if (targetUserId != null) {
            removed = memberRepository.deleteByClassroomIdAndUserId(classroomId, targetUserId);
        }
        if (removed == 0) {
            memberRepository.deleteByClassroomIdAndStudentProfileId(classroomId, studentProfileId);
        }
        // Notify the removed student.
        studentProfileRepository.findById(studentProfileId).ifPresent(sp -> {
            notificationService.create(sp.getUserId(),
                    com.quizopia.backend.notification.domain.model.NotificationType.USER_STATUS_CHANGED,
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
        requireOwnedClassroom(userId, classroomId);

        PageRequest pageable = safePageable(page, size, sort);
        Page<ClassroomMember> members = memberRepository.findByClassroomIdOrderByIdAsc(classroomId, pageable);

        List<Long> spIds = members.getContent().stream().map(ClassroomMember::getStudentProfileId)
                .filter(Objects::nonNull).toList();
        Map<Long, StudentProfile> profiles = batchStudentProfiles(spIds);
        Map<Long, String> displayNames = batchDisplayNames(
                members.getContent().stream().map(ClassroomMember::getUserId).collect(Collectors.toSet()));

        List<ClassroomMemberResponse> items = members.getContent().stream()
                .map(m -> {
                    StudentProfile sp = m.getStudentProfileId() != null ? profiles.get(m.getStudentProfileId()) : null;
                    return new ClassroomMemberResponse(
                            m.getId(),
                            m.getStudentProfileId(),
                            sp != null ? sp.getStudentCode() : null,
                            displayNames.get(m.getUserId()),
                            m.getAddedAt());
                })
                .toList();
        return new PageResponse<>(items, members.getNumber(), members.getSize(),
                members.getTotalElements(), members.getTotalPages(), pageable.getSort().toString());
    }

    // ============================================================
    // Helpers
    // ============================================================

    /**
     * Resolves the organization workspace for a school (classrooms stay
     * org-scoped in Phase 1, ADR-001 §2.2). Lazily provisions the
     * organization + workspace pair on first use.
     */
    private Long requireOrganizationWorkspaceId(Long schoolId) {
        return organizationWorkspaceResolver.requireOrganizationWorkspaceId(schoolId);
    }

    /**
     * Identity-M3: ownership is by OWNER USER. Legacy classrooms keep their
     * school scoping implicitly (owner_user_id was backfilled from the
     * teacher profile); independent classrooms are personal. Both modes
     * additionally require read access to the owning workspace.
     */
    private Classroom requireOwnedClassroom(Long userId, Long classroomId) {
        Classroom classroom = classroomRepository.findById(classroomId)
                .orElseThrow(() -> new ClassroomException(ClassroomErrorCode.CLASSROOM_NOT_FOUND));
        if (!userId.equals(classroom.getOwnerUserId())) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_ACCESS_DENIED);
        }
        // P0-2 (issue #4): owning the classroom is not enough when the
        // organization membership has been revoked (legacy mode only —
        // personal workspaces cannot be "revoked").
        if (classroom.getSchoolId() != null) {
            try {
                workspaceAccessService.requireResourceRead(userId, classroom.getWorkspaceId());
            } catch (com.quizopia.backend.workspace.exception.WorkspaceException ex) {
                throw new ClassroomException(ClassroomErrorCode.CLASSROOM_ACCESS_DENIED);
            }
        }
        return classroom;
    }

    /** Roster flows ride the CLASSROOM_MEMBER_* permission set. */
    public Classroom requireOwnedClassroomForRoster(Long userId, Long classroomId) {
        requirePermission(userId, "CLASSROOM_MEMBER_ADD");
        return requireOwnedClassroom(userId, classroomId);
    }

    /**
     * Review R2 P1 serialization: PESSIMISTIC_WRITE on the user row — the
     * COMMON lock ordering point (user → entry) shared by decline, accept and
     * reconciliation so a learner's committed opt-out can never be raced by
     * an automatic link.
     */
    public void lockUserRow(Long userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ClassroomException(ClassroomErrorCode.CLASSROOM_ACCESS_DENIED));
    }

    /** Identity-M3: the caller's user if the email is verified, else null. */
    public com.quizopia.backend.identity.domain.model.User findVerifiedUserById(Long userId) {
        return userRepository.findById(userId)
                .filter(u -> u.getEmailVerifiedAt() != null)
                .orElse(null);
    }

    /**
     * Review M3-FINAL-01: permission predicate over the same DB-backed
     * permission store as requirePermission (deny by default).
     */
    public boolean hasRosterPermission(Long userId, String permissionCode) {
        return rolePermissionRepository
                .findEffectivePermissionCodesByUserId(userId, Instant.now(clock))
                .contains(permissionCode);
    }

    /** Roster listing rides CLASSROOM_MEMBER_READ. */
    public Classroom requireOwnedClassroomForRosterRead(Long userId, Long classroomId) {
        requirePermission(userId, "CLASSROOM_MEMBER_READ");
        return requireOwnedClassroom(userId, classroomId);
    }

    /**
     * Identity-M3: the verified owner of a canonical email, or null. Only a
     * user whose email_verified_at is set AND whose email matches the
     * canonical form counts — an unverified string can never link.
     */
    public com.quizopia.backend.identity.domain.model.User findUserByEmailIfVerified(String canonicalEmail) {
        return userRepository.findByEmailIgnoreCase(canonicalEmail)
                .filter(u -> u.getEmailVerifiedAt() != null)
                .orElse(null);
    }

    /**
     * Identity-M3: inside a reconciliation transaction, re-checks that the
     * user id still maps to a verified owner of the canonical email
     * (defense against concurrent email reassignment).
     */
    public com.quizopia.backend.identity.domain.model.User findUserByIdWithVerifiedEmail(
            Long userId, String canonicalEmail) {
        // Review M3-SEC: locking read — fresh committed state (a stale managed
        // instance from the same transaction must not satisfy the check) and
        // serialized against concurrent email reassignment.
        return userRepository.findByIdForUpdate(userId)
                .filter(u -> u.getEmailVerifiedAt() != null)
                .filter(u -> canonicalEmail.equals(u.getEmail() == null ? "" : u.getEmail().trim().toLowerCase()))
                .orElse(null);
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
                c.getStatus().name(), memberCount, c.getOwnerTeacherId(),
                c.getOwnerUserId(), c.getCreatedAt());
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

    @SuppressWarnings("null")
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
