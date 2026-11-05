package com.quizopia.backend.classroom.application;

import com.quizopia.backend.classroom.domain.model.Classroom;
import com.quizopia.backend.classroom.domain.model.ClassroomMember;
import com.quizopia.backend.classroom.domain.model.ClassroomRosterEntry;
import com.quizopia.backend.classroom.dto.CreateRosterEntryRequest;
import com.quizopia.backend.classroom.dto.RosterEntryResponse;
import com.quizopia.backend.classroom.exception.ClassroomErrorCode;
import com.quizopia.backend.classroom.exception.ClassroomException;
import com.quizopia.backend.classroom.repository.ClassroomMemberRepository;
import com.quizopia.backend.classroom.repository.ClassroomRepository;
import com.quizopia.backend.classroom.repository.ClassroomRosterEntryRepository;
import com.quizopia.backend.notification.application.NotificationService;
import com.quizopia.backend.security.encryption.SensitiveDataEncryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Identity-M3 roster application service (issue #15).
 *
 * <h2>Model</h2>
 * Roster entries are INVITATIONS keyed by canonical email — never a user
 * identity. Lifecycle: PENDING → LINKED (verified owner reconciled) /
 * REVOKED (teacher withdrew) / DECLINED (learner declined after linking).
 *
 * <h2>Reconciliation (approved automatic link)</h2>
 * When an email becomes VERIFIED (OTP or trusted OIDC), every PENDING entry
 * for that address atomically becomes a classroom MEMBERSHIP of the real
 * user: the entry row is locked, the user's verified-email proof is
 * re-checked inside the transaction (the caller passes the just-committed
 * proof timestamp and the user id), and the membership insert is deduped by
 * the existing {@code uk_classroom_members_classroom_user} unique index.
 * Concurrent reconciliations converge (lock + index); a notification tells
 * the learner, who may decline (membership removed, entry DECLINED).
 *
 * <h2>Privacy</h2>
 * DOB/phones/raw email are stored ONLY as AES-256-GCM ciphertext; the
 * canonical email is stored in the clear solely for matching (it is the
 * matching key). List/detail responses expose only what the owner needs.
 */
@Service
public class ClassroomRosterService {

    private static final Logger log = LoggerFactory.getLogger(ClassroomRosterService.class);
    private static final int MAX_PAGE_SIZE = 200;

    private final ClassroomRepository classroomRepository;
    private final ClassroomRosterEntryRepository rosterRepository;
    private final ClassroomMemberRepository memberRepository;
    private final ClassroomService classroomService;
    private final SensitiveDataEncryptor encryptor;
    private final NotificationService notificationService;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ClassroomRosterService(ClassroomRepository classroomRepository,
            ClassroomRosterEntryRepository rosterRepository,
            ClassroomMemberRepository memberRepository,
            ClassroomService classroomService,
            SensitiveDataEncryptor encryptor,
            NotificationService notificationService,
            Clock clock,
            TransactionTemplate tx) {
        this.classroomRepository = classroomRepository;
        this.rosterRepository = rosterRepository;
        this.memberRepository = memberRepository;
        this.classroomService = classroomService;
        this.encryptor = encryptor;
        this.notificationService = notificationService;
        this.clock = clock;
        this.tx = tx;
    }

    // ============================================================
    // Teacher: add a pending roster entry
    // ============================================================

    @org.springframework.transaction.annotation.Transactional
    public RosterEntryResponse addRosterEntry(Long teacherUserId, Long classroomId,
            CreateRosterEntryRequest request) {
        Classroom classroom = classroomService.requireOwnedClassroomForRoster(teacherUserId, classroomId);
        String canonical = canonicalEmail(request.email());

        // Review M3-FINAL-02: uniqueness covers ALL active states — a second
        // invitation for an already-LINKED address would otherwise auto-link
        // into a duplicate LINKED row (membership dedupes, roster state
        // diverged). Backed by uk_roster_active_classroom_email.
        if (rosterRepository.existsByClassroomIdAndEmailNormalizedAndStatus(
                        classroomId, canonical, ClassroomRosterEntry.RosterStatus.PENDING)
                || rosterRepository.existsByClassroomIdAndEmailNormalizedAndStatus(
                        classroomId, canonical, ClassroomRosterEntry.RosterStatus.LINKED)) {
            throw new ClassroomException(ClassroomErrorCode.ROSTER_DUPLICATE_PENDING);
        }

        ClassroomRosterEntry entry = new ClassroomRosterEntry(
                classroom, canonical, request.fullName().trim(), teacherUserId);
        entry.setStudentNumber(trimToNull(request.studentNumber()));
        entry.setGender(trimToNull(request.gender()));
        entry.setDobEncrypted(encryptDobOrNull(request.dob()));
        entry.setPhoneEncrypted(encryptOrNull(request.phone()));
        entry.setParentPhoneEncrypted(encryptOrNull(request.parentPhone()));
        entry.setEmailEncrypted(encryptOrNull(request.email().trim()));
        try {
            entry = rosterRepository.saveAndFlush(entry);
        } catch (DataIntegrityViolationException race) {
            // Partial unique index: a concurrent add won.
            throw new ClassroomException(ClassroomErrorCode.ROSTER_DUPLICATE_PENDING);
        }

        // If the email's owner ALREADY exists AND is verified, reconcile
        // immediately — EXCEPT when the learner DECLINED this classroom
        // before (review M3-02): their opt-out survives re-invitation; the
        // entry stays PENDING until the learner explicitly accepts it via
        // /roster/{id}/accept (learner-initiated consent).
        if (!hasDeclinedHistory(classroomId, canonical)) {
            com.quizopia.backend.identity.domain.model.User invitee =
                    classroomService.findUserByEmailIfVerified(canonical);
            if (invitee != null) {
                reconcileEntriesForUser(invitee.getId(), canonical);
                entry = rosterRepository.findById(entry.getId()).orElse(entry);
            }
        }
        return toResponse(entry, classroom);
    }

    // ============================================================
    // Teacher: list / revoke
    // ============================================================

    public Page<RosterEntryResponse> listRoster(Long teacherUserId, Long classroomId, int page, int size) {
        classroomService.requireOwnedClassroomForRoster(teacherUserId, classroomId);
        Page<ClassroomRosterEntry> entries = rosterRepository.findByClassroomIdOrderByCreatedAtDesc(
                classroomId, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE)));
        return entries.map(this::toResponse);
    }

    /**
     * Teacher withdrawal (review M3-01 + M3-FINAL-01): PENDING -> REVOKED
     * (invitation withdrawal — requires CLASSROOM_MEMBER_ADD) and LINKED ->
     * REVOKED (membership deletion — requires CLASSROOM_MEMBER_REMOVE). The
     * action permission is evaluated UNDER the entry lock against live
     * DB-backed grants, so a grant revoked while the request waits on the
     * lock is honored (no pre-captured boolean).
     *
     * <p>Review R5 deadlock P1 (STRUCTURAL fix): the removal notification is
     * sent AFTER the transaction commits. Its users-FK check takes a
     * key-share lock on the learner row — the same row decline/accept/
     * reconcile lock FOR UPDATE — so notifying while holding the entry lock
     * could always cycle with a concurrent decline. With the notification
     * outside, the transaction only ever locks the ENTRY row.
     */
    public void revokeRosterEntry(Long teacherUserId, Long classroomId, Long entryId) {
        // Baseline: ownership + READ (action permission is status-dependent —
        // checked under the lock below). REMOVE-only owners may remove LINKED
        // members; ADD-only owners may withdraw PENDING invitations.
        classroomService.requireOwnedClassroomForRevoke(teacherUserId, classroomId);
        // Existence pre-check via SCALAR read; the locking finder below is the
        // entry's FIRST entity load (review R2 P1: no stale managed state).
        rosterRepository.findEmailByIdAndClassroomId(entryId, classroomId)
                .orElseThrow(() -> new ClassroomException(ClassroomErrorCode.ROSTER_NOT_FOUND));

        record RemovedMember(Long userId, Long entryId, Long classroomId) {}
        RemovedMember removed = tx.execute(status -> {
            ClassroomRosterEntry locked = rosterRepository
                    .findByIdAndClassroomIdForUpdate(entryId, classroomId)
                    .orElseThrow(() -> new ClassroomException(ClassroomErrorCode.ROSTER_NOT_FOUND));
            if (locked.getStatus() == ClassroomRosterEntry.RosterStatus.REVOKED
                    || locked.getStatus() == ClassroomRosterEntry.RosterStatus.DECLINED) {
                throw new ClassroomException(ClassroomErrorCode.ROSTER_INVALID_STATE);
            }
            RemovedMember result = null;
            if (locked.getStatus() == ClassroomRosterEntry.RosterStatus.LINKED) {
                // Membership deletion is REMOVE-gated (live check under the
                // lock — review M3-FINAL-01 P2).
                if (!classroomService.hasRosterPermission(
                        teacherUserId, "CLASSROOM_MEMBER_REMOVE")) {
                    throw new ClassroomException(ClassroomErrorCode.CLASSROOM_ACCESS_DENIED);
                }
                memberRepository.deleteByClassroomIdAndUserId(classroomId, locked.getUserId());
                result = new RemovedMember(locked.getUserId(), locked.getId(), classroomId);
            } else if (!classroomService.hasRosterPermission(
                    teacherUserId, "CLASSROOM_MEMBER_ADD")) {
                // PENDING withdrawal is ADD-gated (live check under the lock).
                throw new ClassroomException(ClassroomErrorCode.CLASSROOM_ACCESS_DENIED);
            }
            locked.revoke(Instant.now(clock));
            rosterRepository.saveAndFlush(locked);
            return result;
        });
        // Best-effort notification AFTER commit (see javadoc above).
        if (removed != null) {
            notifyRemovedMember(removed.entryId(), removed.classroomId(), removed.userId());
        }
    }

    /** Post-commit, best-effort; touches only ids (no lazy entities). */
    private void notifyRemovedMember(Long entryId, Long classroomId, Long userId) {
        try {
            String className = classroomRepository.findById(classroomId)
                    .map(Classroom::getName).orElse("your class");
            notificationService.create(userId,
                    com.quizopia.backend.notification.domain.model.NotificationType.USER_STATUS_CHANGED,
                    "Removed from class",
                    "You were removed from " + className,
                    "/my-classes");
        } catch (RuntimeException ex) {
            log.warn("Failed to notify removed member for entry {}", entryId);
        }
    }

    // ============================================================
    // Learner: my pending invitations / decline
    // ============================================================

    public List<RosterEntryResponse> myLinkedEntries(Long userId) {
        // Review M3-FINAL-03: LEARNER-facing projection — sensitive fields
        // (DOB, phones, raw email) are owner-facing only and stay null here.
        return rosterRepository
                .findAllByUserIdAndStatus(userId, ClassroomRosterEntry.RosterStatus.LINKED)
                .stream().map(this::toLearnerResponse).toList();
    }

    /**
     * Learner declines a linked entry: the classroom membership is removed
     * and the entry becomes DECLINED (kept for audit). Learner-owned action:
     * the entry must be LINKED to THIS user.
     */
    @org.springframework.transaction.annotation.Transactional
    public void declineEntry(Long userId, Long entryId) {
        // Review R2 P1: user lock FIRST (shared ordering with accept and
        // reconciliation) so a concurrent auto-link for the same learner
        // cannot interleave with this opt-out.
        classroomService.lockUserRow(userId);
        ClassroomRosterEntry entry = rosterRepository.findByIdAndClassroomIdForUpdate(
                        entryId, rosterRepository.findClassroomIdById(entryId)
                                .orElseThrow(() -> new ClassroomException(
                                        ClassroomErrorCode.ROSTER_NOT_FOUND)))
                .orElseThrow(() -> new ClassroomException(ClassroomErrorCode.ROSTER_NOT_FOUND));
        if (entry.getStatus() != ClassroomRosterEntry.RosterStatus.LINKED
                || !userId.equals(entry.getUserId())) {
            throw new ClassroomException(ClassroomErrorCode.ROSTER_INVALID_STATE);
        }
        // Remove the membership first (the entry keeps user_id for audit).
        memberRepository.deleteByClassroomIdAndUserId(entry.getClassroomId(), userId);
        entry.decline(Instant.now(clock));
        rosterRepository.saveAndFlush(entry);
        log.info("User {} declined roster entry {} (classroom {})", userId, entryId,
                entry.getClassroomId());
    }

    /**
     * Learner-initiated consent (review M3-02): the verified owner of the
     * entry's email ACCEPTS a pending invitation — the only path that can
     * relink a classroom the learner previously DECLINED.
     */
    @org.springframework.transaction.annotation.Transactional
    public RosterEntryResponse acceptEntry(Long userId, Long entryId) {
        // Review R2 P1: COMMON lock ordering (user row FIRST, entry second)
        // shared with decline + reconciliation; the entry is loaded ONLY via
        // the locking query so its state is committed-fresh.
        classroomService.lockUserRow(userId);
        RosterEntryResponse result = tx.execute(status -> {
            Long classroomId = rosterRepository.findClassroomIdById(entryId)
                    .orElseThrow(() -> new ClassroomException(ClassroomErrorCode.ROSTER_NOT_FOUND));
            ClassroomRosterEntry locked = rosterRepository
                    .findByIdAndClassroomIdForUpdate(entryId, classroomId)
                    .orElseThrow(() -> new ClassroomException(ClassroomErrorCode.ROSTER_NOT_FOUND));
            if (locked.getStatus() != ClassroomRosterEntry.RosterStatus.PENDING) {
                throw new ClassroomException(ClassroomErrorCode.ROSTER_INVALID_STATE);
            }
            // Consent proof: the caller's email must be VERIFIED and match
            // the invitation address (same proof as auto-linking).
            String canonical = locked.getEmailNormalized();
            com.quizopia.backend.identity.domain.model.User user =
                    classroomService.findUserByIdWithVerifiedEmail(userId, canonical);
            if (user == null) {
                throw new ClassroomException(ClassroomErrorCode.ROSTER_ACCESS_DENIED);
            }
            memberRepository.insertMembershipIfAbsent(classroomId, userId);
            locked.linkTo(userId, Instant.now(clock));
            rosterRepository.saveAndFlush(locked);
            // Learner-facing response (review M3-FINAL-03 P1): the accept
            // endpoint is learner-reachable — sensitive fields stay redacted.
            return toLearnerResponse(locked);
        });
        return result;
    }

    /**
     * Learner: PENDING invitations addressed to the caller's VERIFIED email
     * (these await explicit consent — they exist only when the learner
     * previously declined this classroom).
     */
    public List<RosterEntryResponse> myPendingInvitations(Long userId) {
        com.quizopia.backend.identity.domain.model.User user = classroomService
                .findVerifiedUserById(userId);
        if (user == null) {
            return List.of();
        }
        String canonical = canonicalEmail(user.getEmail());
        return rosterRepository
                .findAllByEmailNormalizedAndStatus(canonical,
                        ClassroomRosterEntry.RosterStatus.PENDING)
                .stream().map(this::toLearnerResponse).toList();
    }

    /**
     * Learner projection (review M3-FINAL-03): identifiers + lifecycle only.
     * DOB, student phone, parent phone and the raw teacher-entered email are
     * never exposed to the learner through the API.
     */
    private RosterEntryResponse toLearnerResponse(ClassroomRosterEntry entry) {
        return new RosterEntryResponse(
                entry.getId(),
                entry.getClassroomId(),
                entry.getStatus().name(),
                entry.getUserId(),
                entry.getFullName(),
                null, null, null, null, null, null,
                entry.getCreatedAt(),
                entry.getLinkedAt());
    }

    private boolean hasDeclinedHistory(Long classroomId, String canonicalEmail) {
        return rosterRepository.existsByClassroomIdAndEmailNormalizedAndStatus(
                classroomId, canonicalEmail, ClassroomRosterEntry.RosterStatus.DECLINED);
    }

    // ============================================================
    // Reconciliation (called when an email becomes VERIFIED)
    // ============================================================

    /**
     * Links every PENDING entry for the now-verified email to the real user:
     * per entry, under the entry-row lock, re-verifies the user's proof and
     * inserts the classroom membership (deduped by the DB unique index).
     * Idempotent: re-running finds no PENDING entries.
     */
    public void reconcileEntriesForUser(Long userId, String verifiedEmail) {
        String canonical = canonicalEmail(verifiedEmail);
        List<ClassroomRosterEntry> pending = rosterRepository
                .findAllByEmailNormalizedAndStatus(canonical, ClassroomRosterEntry.RosterStatus.PENDING);
        for (ClassroomRosterEntry candidate : pending) {
            // Mirror is null for same-session fresh inserts (insertable=false):
            // fall back to the association while the session is still open.
            Long classroomId = candidate.getClassroomId() != null
                    ? candidate.getClassroomId()
                    : candidate.getClassroom().getId();
            record LinkOutcome(boolean linked, String classroomName) {}
            LinkOutcome outcome = tx.execute(status -> {
                // Review R2 P1: user-row lock FIRST (same ordering as decline/
                // accept) — a concurrent decline of an older entry for this
                // (classroom, email) commits-or-blocks BEFORE this history
                // check, so a completed opt-out can never be raced into a link.
                com.quizopia.backend.identity.domain.model.User proof =
                        classroomService.findUserByIdWithVerifiedEmail(userId, canonical);
                if (proof == null) {
                    return new LinkOutcome(false, null);
                }
                ClassroomRosterEntry locked = rosterRepository
                        .findPendingForUpdate(classroomId, canonical).orElse(null);
                if (locked == null) {
                    return new LinkOutcome(false, null); // concurrently linked/revoked
                }
                Classroom classroom = classroomRepository.findById(classroomId).orElse(null);
                if (classroom == null) {
                    return new LinkOutcome(false, null);
                }
                // Review M3-02: a DECLINED history for this (classroom,
                // email) is a standing opt-out — verification alone must not
                // relink; only the learner's explicit accept can.
                if (hasDeclinedHistory(classroomId, canonical)) {
                    return new LinkOutcome(false, null);
                }
                insertMembershipDeduped(classroomId, userId);
                locked.linkTo(userId, Instant.now(clock));
                rosterRepository.saveAndFlush(locked);
                return new LinkOutcome(true, classroom.getName());
            });
            if (outcome != null && outcome.linked()) {
                notificationService.create(userId,
                        com.quizopia.backend.notification.domain.model.NotificationType.ADDED_TO_CLASS,
                        "Added to class",
                        "You were added to " + outcome.classroomName()
                                + " (email verification). You can decline from the class page.",
                        "/classes/" + classroomId);
            }
        }
    }

    /**
     * Atomic dedupe (review M3-DB P1): a caught constraint violation would
     * abort the enclosing PostgreSQL transaction; ON CONFLICT DO NOTHING
     * stays valid and is a true no-op for existing members.
     */
    private void insertMembershipDeduped(Long classroomId, Long userId) {
        int inserted = memberRepository.insertMembershipIfAbsent(classroomId, userId);
        if (inserted == 0) {
            log.debug("User {} already a member of classroom {}", userId, classroomId);
        }
    }

    // ============================================================
    // Internals
    // ============================================================

    private ClassroomRosterEntry requireEntry(Long classroomId, Long entryId) {
        return rosterRepository.findByIdAndClassroomId(entryId, classroomId)
                .orElseThrow(() -> new ClassroomException(ClassroomErrorCode.ROSTER_NOT_FOUND));
    }

    private RosterEntryResponse toResponse(ClassroomRosterEntry entry) {
        return toResponse(entry, null);
    }

    /** classroom: pre-loaded owner-side classroom (avoids lazy init). */
    private RosterEntryResponse toResponse(ClassroomRosterEntry entry, Classroom classroom) {
        return new RosterEntryResponse(
                entry.getId(),
                classroom != null ? classroom.getId() : entry.getClassroomId(),
                entry.getStatus().name(),
                entry.getUserId(),
                entry.getFullName(),
                entry.getStudentNumber(),
                entry.getGender(),
                decryptDobOrNull(entry.getDobEncrypted()),
                decryptOrNull(entry.getPhoneEncrypted()),
                decryptOrNull(entry.getParentPhoneEncrypted()),
                // Raw email only for the OWNER-facing response (they typed it).
                decryptOrNull(entry.getEmailEncrypted()),
                entry.getCreatedAt(),
                entry.getLinkedAt());
    }

    private String encryptOrNull(String plain) {
        return plain == null || plain.isBlank() ? null : encryptor.encrypt(plain.trim());
    }

    private String encryptDobOrNull(java.time.LocalDate dob) {
        return dob == null ? null : encryptor.encrypt(dob.toString());
    }

    private java.time.LocalDate decryptDobOrNull(String ciphertext) {
        return ciphertext == null ? null : java.time.LocalDate.parse(encryptor.decrypt(ciphertext));
    }

    private String decryptOrNull(String ciphertext) {
        return ciphertext == null ? null : encryptor.decrypt(ciphertext);
    }

    static String canonicalEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
