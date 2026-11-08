package com.quizopia.backend.classroom.application;

import com.quizopia.backend.classroom.domain.model.Classroom;
import com.quizopia.backend.classroom.domain.model.ClassroomMember;
import com.quizopia.backend.classroom.domain.model.ClassroomRosterEntry;
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
 * user ownership at the service layer (deny by default). Classrooms belong
 * directly to their owner (a TEACHER user); members are platform users
 * (user-based membership) and gate CLASS_RESTRICTED exam-session visibility.
 */
@Service
public class ClassroomService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SORT_ALLOWLIST = Set.of("addedAt");
    private static final String CODE_CONFLICT_INDEX = "uk_classrooms_owner_user_code_ci";
    private static final String MEMBER_DUPLICATE_CONSTRAINT = "uk_classroom_members_classroom_";

    private final com.quizopia.backend.identity.repository.UserRepository userRepository;
    private final ClassroomRepository classroomRepository;
    private final ClassroomMemberRepository memberRepository;
    private final com.quizopia.backend.classroom.repository.ClassroomRosterEntryRepository rosterEntryRepository;
    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final EntityManager entityManager;
    private final Clock clock;
    private final com.quizopia.backend.notification.application.NotificationService notificationService;

    public ClassroomService(
            com.quizopia.backend.identity.repository.UserRepository userRepository,
            ClassroomRepository classroomRepository,
            ClassroomMemberRepository memberRepository,
            com.quizopia.backend.classroom.repository.ClassroomRosterEntryRepository rosterEntryRepository,
            UserRoleRepository userRoleRepository,
            RolePermissionRepository rolePermissionRepository,
            EntityManager entityManager,
            Clock clock,
            com.quizopia.backend.notification.application.NotificationService notificationService) {
        this.userRepository = userRepository;
        this.classroomRepository = classroomRepository;
        this.memberRepository = memberRepository;
        this.rosterEntryRepository = rosterEntryRepository;
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
        requireActiveTeacherRole(userId);

        if (classroomRepository.existsByOwnerUserIdAndCodeIgnoreCase(userId, request.code())) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_CODE_CONFLICT);
        }

        Classroom classroom = new Classroom(userId, request.code(), request.name());
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

    @SuppressWarnings("null")
    @Transactional(readOnly = true)
    public MyClassroomsResponse listMyClassrooms(Long userId) {
        requirePermission(userId, "CLASSROOM_READ");
        // "My" classrooms = classrooms owned by this USER. Not paginated in Phase 1.
        Page<Classroom> page = classroomRepository.findByOwnerUserIdOrderByCreatedAtDescIdDesc(
                userId, PageRequest.of(0, MAX_PAGE_SIZE));
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

    @SuppressWarnings("null")
    @Transactional(readOnly = true)
    public ClassroomDetailView getClassroom(Long userId, Long classroomId) {
        requirePermission(userId, "CLASSROOM_READ");
        Classroom classroom = requireOwnedClassroom(userId, classroomId);

        List<ClassroomMember> members = memberRepository.findAllByClassroomIdOrderByIdAsc(classroomId);
        Set<Long> memberUserIds = members.stream().map(ClassroomMember::getUserId).collect(Collectors.toSet());
        Map<Long, String> displayNames = batchDisplayNames(memberUserIds);
        Map<Long, Object[]> accounts = batchUserAccounts(memberUserIds);

        List<MemberView> memberViews = members.stream()
                .map(m -> {
                    Object[] account = accounts.get(m.getUserId());
                    return new MemberView(
                            m.getUserId(),
                            displayNames.get(m.getUserId()),
                            account != null ? (String) account[0] : null,
                            account != null ? (String) account[1] : null,
                            m.getAddedAt());
                })
                .toList();
        return new ClassroomDetailView(
                classroom.getId(), classroom.getCode(), classroom.getName(),
                classroom.getDescription(), classroom.getStatus().name(),
                classroom.getOwnerUserId(), classroom.getCreatedAt(),
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
        List<Long> rawIds = request.userIds();
        Set<Long> seen = new LinkedHashSet<>();
        List<Long> duplicatedInRequest = new ArrayList<>();
        for (Long id : rawIds) {
            if (!seen.add(id)) {
                duplicatedInRequest.add(id);
            }
        }
        List<Long> distinctIds = new ArrayList<>(seen);

        // Batch-check existing memberships (user-based).
        Set<Long> existingUsers = memberRepository
                .findAllByClassroomIdAndUserIdIn(classroomId, distinctIds).stream()
                .map(ClassroomMember::getUserId)
                .collect(Collectors.toSet());

        List<Long> toInsert = new ArrayList<>();
        List<Long> duplicated = new ArrayList<>(duplicatedInRequest);
        List<Long> invalid = new ArrayList<>();
        for (Long id : distinctIds) {
            if (!userRepository.existsById(id)) {
                invalid.add(id); // missing user
            } else if (existingUsers.contains(id)) {
                duplicated.add(id); // already a member
            } else {
                toInsert.add(id); // valid
            }
        }

        // Final-M1 gate F1 (issue #50 decision 6): a learner who LEFT this
        // classroom (DECLINED consent record keyed by their canonical email)
        // must not be directly re-added — the teacher sends an invitation
        // and the learner explicitly accepts. The check runs UNDER the
        // candidate user-row locks (sorted order — no bulk-lock cycles),
        // the same serialization point leave/decline/accept/reconcile use,
        // so a concurrent self-leave can never be bypassed by a re-add.
        List<Long> lockOrder = new ArrayList<>(toInsert);
        Collections.sort(lockOrder);
        for (Long candidateId : lockOrder) {
            lockUserRow(candidateId);
        }
        Map<Long, String> canonicalEmails = batchCanonicalEmails(toInsert);
        Set<Long> declinedOptOut = toInsert.stream()
                .filter(id -> {
                    String email = canonicalEmails.get(id);
                    return email != null && rosterEntryRepository
                            .existsByClassroomIdAndEmailNormalizedAndStatus(classroomId, email,
                                    ClassroomRosterEntry.RosterStatus.DECLINED);
                })
                .collect(Collectors.toSet());
        toInsert = toInsert.stream().filter(id -> !declinedOptOut.contains(id)).toList();

        if (!toInsert.isEmpty()) {
            try {
                for (Long memberUserId : toInsert) {
                    memberRepository.saveAndFlush(new ClassroomMember(classroomId, memberUserId));
                }
            } catch (DataIntegrityViolationException ex) {
                if (isMemberDuplicate(ex)) {
                    throw new ClassroomException(ClassroomErrorCode.CLASSROOM_MEMBER_DUPLICATE);
                }
                throw ex;
            }
        }

        List<Long> declined = new ArrayList<>(declinedOptOut);
        Collections.sort(duplicated);
        Collections.sort(invalid);
        Collections.sort(declined);
        // Notify the teacher + each added student.
        if (!toInsert.isEmpty()) {
            notificationService.create(userId,
                    com.quizopia.backend.notification.domain.model.NotificationType.STUDENT_JOINED_CLASS,
                    "Students joined class",
                    toInsert.size() + " student" + (toInsert.size() == 1 ? "" : "s") + " added to "
                            + classroom.getName(),
                    "/classes/" + classroomId);
            // Notify each student they were added to the class.
            for (Long memberUserId : toInsert) {
                notificationService.create(memberUserId,
                        com.quizopia.backend.notification.domain.model.NotificationType.ADDED_TO_CLASS,
                        "Added to class",
                        "You were added to " + classroom.getName(),
                        "/sessions");
            }
        }
        return new AddMembersResponse(toInsert.size(), duplicated, invalid, declined);
    }

    // ============================================================
    // DELETE /api/classrooms/{id}/members/{userId}
    // ============================================================

    @Transactional
    public void removeMember(Long userId, Long classroomId, Long memberUserId) {
        requirePermission(userId, "CLASSROOM_MEMBER_REMOVE");
        Classroom classroom = requireOwnedClassroom(userId, classroomId);
        // User-based removal. Idempotent by design.
        memberRepository.deleteByClassroomIdAndUserId(classroomId, memberUserId);
        // Notify the removed student.
        notificationService.create(memberUserId,
                com.quizopia.backend.notification.domain.model.NotificationType.REMOVED_FROM_CLASS,
                "Removed from class",
                "You were removed from " + classroom.getName(),
                "/sessions");
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

        Map<Long, String> displayNames = batchDisplayNames(
                members.getContent().stream().map(ClassroomMember::getUserId).collect(Collectors.toSet()));

        List<ClassroomMemberResponse> items = members.getContent().stream()
                .map(m -> new ClassroomMemberResponse(
                        m.getId(),
                        m.getUserId(),
                        displayNames.get(m.getUserId()),
                        m.getAddedAt()))
                .toList();
        return new PageResponse<>(items, members.getNumber(), members.getSize(),
                members.getTotalElements(), members.getTotalPages(), pageable.getSort().toString());
    }

    // ============================================================
    // Ownership / roster helpers
    // ============================================================

    /** Ownership is by OWNER USER: 404 when absent, 403 for non-owners. */
    public Classroom requireOwnedClassroom(Long userId, Long classroomId) {
        Classroom classroom = classroomRepository.findById(classroomId)
                .orElseThrow(() -> new ClassroomException(ClassroomErrorCode.CLASSROOM_NOT_FOUND));
        if (!userId.equals(classroom.getOwnerUserId())) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_ACCESS_DENIED);
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

    /** The caller's user if the email is verified, else null. */
    public com.quizopia.backend.identity.domain.model.User findVerifiedUserById(Long userId) {
        return userRepository.findById(userId)
                .filter(u -> u.getEmailVerifiedAt() != null)
                .orElse(null);
    }

    /**
     * Review M3-FINAL-01: ownership + READ baseline for roster revocation.
     * The status-dependent action permission (ADD for PENDING withdrawal,
     * REMOVE for LINKED membership deletion) is evaluated by the caller
     * UNDER the entry lock so a concurrent grant revocation is honored.
     */
    public Classroom requireOwnedClassroomForRevoke(Long userId, Long classroomId) {
        requirePermission(userId, "CLASSROOM_READ");
        return requireOwnedClassroom(userId, classroomId);
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
     * The verified owner of a canonical email, or null. Only a user whose
     * email_verified_at is set AND whose email matches the canonical form
     * counts — an unverified string can never link.
     */
    public com.quizopia.backend.identity.domain.model.User findUserByEmailIfVerified(String canonicalEmail) {
        return userRepository.findByEmailIgnoreCase(canonicalEmail)
                .filter(u -> u.getEmailVerifiedAt() != null)
                .orElse(null);
    }

    /**
     * Inside a reconciliation transaction, re-checks that the user id still
     * maps to a verified owner of the canonical email (defense against
     * concurrent email reassignment).
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

    private void requireActiveTeacherRole(Long userId) {
        List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(userId, Instant.now(clock));
        if (!roles.contains("TEACHER")) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_ACCESS_DENIED);
        }
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
                c.getStatus().name(), memberCount, c.getOwnerUserId(), c.getCreatedAt());
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

    /** Single Hibernate-tracked projection (no N+1). */
    /** Batch username + email per user id (index 0/1). */
    private Map<Long, Object[]> batchUserAccounts(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = entityManager.createQuery(
                "SELECT u.id, u.username, u.email FROM User u WHERE u.id IN :ids", Object[].class)
                .setParameter("ids", userIds)
                .getResultList();
        Map<Long, Object[]> accounts = new HashMap<>();
        for (Object[] row : rows) {
            accounts.put((Long) row[0], new Object[] { row[1], row[2] });
        }
        return accounts;
    }

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

    /** userId → canonical email (trimmed lowercase); absent for missing/blank emails. */
    private Map<Long, String> batchCanonicalEmails(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = entityManager.createQuery(
                "SELECT u.id, u.email FROM User u WHERE u.id IN :ids", Object[].class)
                .setParameter("ids", new java.util.HashSet<>(userIds))
                .getResultList();
        Map<Long, String> emails = new HashMap<>();
        for (Object[] row : rows) {
            String email = (String) row[1];
            if (email != null && !email.isBlank()) {
                emails.put((Long) row[0], email.trim().toLowerCase());
            }
        }
        return emails;
    }
}
