package com.quizopia.backend.classroom.application;

import com.quizopia.backend.classroom.domain.model.Classroom;
import com.quizopia.backend.classroom.domain.model.ClassroomRosterEntry;
import com.quizopia.backend.classroom.dto.JoinedClassroomResponse;
import com.quizopia.backend.classroom.exception.ClassroomErrorCode;
import com.quizopia.backend.classroom.exception.ClassroomException;
import com.quizopia.backend.classroom.repository.ClassroomMemberRepository;
import com.quizopia.backend.classroom.repository.ClassroomRepository;
import com.quizopia.backend.classroom.repository.ClassroomRosterEntryRepository;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.question.dto.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * CLASS-M1 (issue #50): learner membership self-service.
 *
 * <h2>Source of truth</h2>
 * {@code classroom_members} is the authoritative list of a learner's JOINED
 * classrooms, regardless of the enrollment source (teacher direct add,
 * verified-email reconciliation, Excel import or accepted invitation).
 * {@code classroom_roster_entries} tracks invitation/roster consent and
 * metadata — never the joined-class list.
 *
 * <h2>Self-leave policy (product decision 6)</h2>
 * {@code POST /api/classrooms/{id}/leave} removes the CALLER's membership
 * for any enrollment source and records a standing DECLINED opt-out:
 * <ul>
 *   <li>LINKED roster rows transition to DECLINED (existing decline
 *       semantics — audit kept, no stale LINKED records);</li>
 *   <li>a direct-add membership with NO roster row gets a synthetic
 *       DECLINED opt-out row keyed by the learner's canonical email, so a
 *       later teacher invitation of that email can never auto-relink —
 *       only the learner's explicit accept can rejoin;</li>
 *   <li>teacher DIRECT re-add stays an explicit owner action
 *       ({@code addMembers} is unchanged) — the learner can leave again
 *       at any time;</li>
 *   <li>historical attempts, grades, results and exam-session-class
 *       associations are never touched; new CLASS_RESTRICTED eligibility
 *       follows the existing membership EXISTS check.</li>
 * </ul>
 *
 * <h2>Race safety</h2>
 * Leave follows the COMMON lock ordering (user row FIRST, roster entry
 * second) shared with decline/accept/reconciliation, so a committed
 * opt-out can never be raced by an automatic link; membership inserts
 * are deduped by {@code uk_classroom_members_classroom_user}.
 */
@Service
public class ClassroomMembershipService {

    private static final Logger log = LoggerFactory.getLogger(ClassroomMembershipService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final ClassroomRepository classroomRepository;
    private final ClassroomMemberRepository memberRepository;
    private final ClassroomRosterEntryRepository rosterRepository;
    private final ClassroomService classroomService;
    private final UserRepository userRepository;
    private final Clock clock;

    public ClassroomMembershipService(ClassroomRepository classroomRepository,
            ClassroomMemberRepository memberRepository,
            ClassroomRosterEntryRepository rosterRepository,
            ClassroomService classroomService,
            UserRepository userRepository,
            Clock clock) {
        this.classroomRepository = classroomRepository;
        this.memberRepository = memberRepository;
        this.rosterRepository = rosterRepository;
        this.classroomService = classroomService;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    // ============================================================
    // GET /api/classrooms/joined — the learner's joined classes
    // ============================================================

    /**
     * Projects the caller's CURRENT memberships with non-sensitive class
     * metadata and the owner teacher's display name. SQL-level paging and
     * count; stable order (joinedAt desc, membership id desc). Any
     * authenticated caller may read their own list (self-scoped data, same
     * policy as {@code GET /api/classrooms/roster/my}).
     */
    @Transactional(readOnly = true)
    public PageResponse<JoinedClassroomResponse> listJoinedClassrooms(Long userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<JoinedClassroomResponse> result = memberRepository.findJoinedClassrooms(
                userId, PageRequest.of(safePage, safeSize));
        return new PageResponse<>(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages(), "joinedAt,desc");
    }

    // ============================================================
    // POST /api/classrooms/{id}/leave — learner self-exit
    // ============================================================

    /**
     * Removes the caller's membership (any enrollment source) and records
     * the consent opt-out. Defined repeat behavior: leaving a classroom
     * the caller is not a member of (never joined, already left, or
     * concurrently removed by the teacher) is 404 CLASSROOM_NOT_FOUND.
     */
    @Transactional
    public void leaveClassroom(Long userId, Long classroomId) {
        Classroom classroom = classroomRepository.findById(classroomId)
                .orElseThrow(() -> new ClassroomException(ClassroomErrorCode.CLASSROOM_NOT_FOUND));
        // Fast path: no membership → the defined repeat-leave response.
        if (!memberRepository.existsByClassroomIdAndUserId(classroomId, userId)) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_NOT_FOUND);
        }
        // COMMON lock ordering user → entry → member (same as declineEntry):
        // the user row FIRST serializes self-leave against decline/accept/
        // reconciliation for the same learner; the ENTRY locks must precede
        // the member-row delete because revokeRosterEntry holds the entry
        // lock while deleting the member row — member-delete first would
        // create an ABBA deadlock with a concurrent teacher revocation.
        classroomService.lockUserRow(userId);
        List<ClassroomRosterEntry> lockedLinked = lockLinkedEntries(classroom, userId);
        // Re-check under the locks: a concurrent teacher removal may have won.
        if (memberRepository.deleteByClassroomIdAndUserId(classroomId, userId) == 0) {
            throw new ClassroomException(ClassroomErrorCode.CLASSROOM_NOT_FOUND);
        }
        resolveRosterOnLeave(classroom, userId, lockedLinked);
        log.info("User {} left classroom {}", userId, classroomId);
    }

    /** Locks (FOR UPDATE) every LINKED entry of this (user, classroom). */
    private List<ClassroomRosterEntry> lockLinkedEntries(Classroom classroom, Long userId) {
        List<ClassroomRosterEntry> linked = rosterRepository
                .findAllByUserIdAndStatus(userId, ClassroomRosterEntry.RosterStatus.LINKED)
                .stream()
                // Null-safe classroom id: the insertable=false mirror is
                // unset on same-persistence-context cached instances.
                .filter(e -> classroom.getId().equals(classroomIdOf(e)))
                .toList();
        List<ClassroomRosterEntry> lockedLinked = new java.util.ArrayList<>();
        for (ClassroomRosterEntry candidate : linked) {
            rosterRepository.findByIdAndClassroomIdForUpdate(candidate.getId(), classroom.getId())
                    .ifPresent(locked -> {
                        if (locked.getStatus() == ClassroomRosterEntry.RosterStatus.LINKED) {
                            lockedLinked.add(locked);
                        }
                    });
        }
        return lockedLinked;
    }

    /**
     * Reconciles roster consent for the just-removed membership (entry locks
     * already held): every still-LINKED entry of this (user, classroom)
     * becomes DECLINED; a direct-add membership without any roster row
     * records a synthetic DECLINED opt-out keyed by the learner's canonical
     * email.
     */
    private void resolveRosterOnLeave(Classroom classroom, Long userId,
            List<ClassroomRosterEntry> lockedLinked) {
        Instant now = Instant.now(clock);
        for (ClassroomRosterEntry locked : lockedLinked) {
            locked.decline(now);
            rosterRepository.saveAndFlush(locked);
        }
        if (!lockedLinked.isEmpty()) {
            return; // consent history already recorded by a roster row
        }
        // Direct-add path: no roster row exists. Record the opt-out so a
        // future invitation of the learner's email cannot auto-relink.
        // Guard 1: no email → no auto-link path to record (email is the
        // matching key). Guard 2: a standing DECLINED row for this
        // (classroom, email) already carries the opt-out — repeated
        // direct-add/leave cycles must not stack duplicate audit rows.
        userRepository.findById(userId).ifPresent(user -> {
            String email = user.getEmail();
            if (email == null || email.isBlank()) {
                log.debug("User {} left classroom {} without an email; no opt-out row recorded",
                        userId, classroom.getId());
                return;
            }
            String canonical = email.trim().toLowerCase();
            boolean alreadyOptedOut = rosterRepository.existsByClassroomIdAndEmailNormalizedAndStatus(
                    classroom.getId(), canonical, ClassroomRosterEntry.RosterStatus.DECLINED);
            if (alreadyOptedOut) {
                return;
            }
            ClassroomRosterEntry optOut = ClassroomRosterEntry.declinedOptOut(
                    classroom, canonical, user.getDisplayName(), userId, now);
            // No conflict handling needed: DECLINED rows sit OUTSIDE the
            // partial unique index uk_roster_active_classroom_email
            // (PENDING/LINKED only, V20), so this insert cannot race an
            // ACTIVE row; any other constraint failure should abort the
            // leave (honest failure beats a silently doomed transaction).
            rosterRepository.saveAndFlush(optOut);
        });
    }

    /**
     * The classroom_id mirror is insertable=false — same-persistence-context
     * instances cached from a fresh insert carry a null mirror; fall back to
     * the association (same pattern as roster reconciliation).
     */
    private Long classroomIdOf(ClassroomRosterEntry entry) {
        return entry.getClassroomId() != null ? entry.getClassroomId() : entry.getClassroom().getId();
    }
}
