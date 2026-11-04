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

        if (rosterRepository.existsByClassroomIdAndEmailNormalizedAndStatus(
                classroomId, canonical, ClassroomRosterEntry.RosterStatus.PENDING)) {
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
     * Teacher withdrawal (review M3-01): PENDING → REVOKED (invitation
     * withdrawn) and LINKED → REVOKED (member removed + membership deleted,
     * user-based independent memberships have no legacy member endpoint).
     * Takes the row lock so a concurrent reconciliation cannot interleave;
     * the locked state — not a stale read — decides the outcome.
     */
    @org.springframework.transaction.annotation.Transactional
    public void revokeRosterEntry(Long teacherUserId, Long classroomId, Long entryId) {
        classroomService.requireOwnedClassroomForRoster(teacherUserId, classroomId);
        String email = requireEntry(classroomId, entryId).getEmailNormalized();
        ClassroomRosterEntry locked = lockEntry(classroomId, entryId, email);
        if (locked.getStatus() == ClassroomRosterEntry.RosterStatus.LINKED) {
            // Remove the user-based membership first (same transaction).
            memberRepository.deleteByClassroomIdAndUserId(classroomId, locked.getUserId());
            notifyRemovedMember(locked);
        }
        locked.revoke(Instant.now(clock));
        rosterRepository.saveAndFlush(locked);
    }

    /** PESSIMISTIC_WRITE on the entry in its CURRENT (non-terminal) state. */
    private ClassroomRosterEntry lockEntry(Long classroomId, Long entryId, String email) {
        ClassroomRosterEntry entry = tx.execute(status -> rosterRepository
                .findByIdAndClassroomIdForUpdate(entryId, classroomId)
                .orElseThrow(() -> new ClassroomException(ClassroomErrorCode.ROSTER_NOT_FOUND)));
        if (entry.getStatus() == ClassroomRosterEntry.RosterStatus.REVOKED
                || entry.getStatus() == ClassroomRosterEntry.RosterStatus.DECLINED) {
            throw new ClassroomException(ClassroomErrorCode.ROSTER_INVALID_STATE);
        }
        return entry;
    }

    private void notifyRemovedMember(ClassroomRosterEntry entry) {
        try {
            notificationService.create(entry.getUserId(),
                    com.quizopia.backend.notification.domain.model.NotificationType.USER_STATUS_CHANGED,
                    "Removed from class",
                    "You were removed from " + entry.getClassroom().getName(),
                    "/my-classes");
        } catch (RuntimeException ex) {
            log.warn("Failed to notify removed member for entry {}", entry.getId());
        }
    }

    // ============================================================
    // Learner: my pending invitations / decline
    // ============================================================

    public List<RosterEntryResponse> myLinkedEntries(Long userId) {
        return rosterRepository
                .findAllByUserIdAndStatus(userId, ClassroomRosterEntry.RosterStatus.LINKED)
                .stream().map(this::toResponse).toList();
    }

    /**
     * Learner declines a linked entry: the classroom membership is removed
     * and the entry becomes DECLINED (kept for audit). Learner-owned action:
     * the entry must be LINKED to THIS user.
     */
    @org.springframework.transaction.annotation.Transactional
    public void declineEntry(Long userId, Long entryId) {
        ClassroomRosterEntry entry = rosterRepository.findById(entryId)
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
        ClassroomRosterEntry entry = tx.execute(status -> {
            ClassroomRosterEntry locked = rosterRepository
                    .findByIdAndClassroomIdForUpdate(entryId,
                            rosterRepository.findById(entryId)
                                    .orElseThrow(() -> new ClassroomException(
                                            ClassroomErrorCode.ROSTER_NOT_FOUND))
                                    .getClassroomId())
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
            memberRepository.insertMembershipIfAbsent(locked.getClassroomId(), userId);
            locked.linkTo(userId, Instant.now(clock));
            rosterRepository.saveAndFlush(locked);
            return locked;
        });
        return toResponse(entry, null);
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
                .stream().map(e -> toResponse(e, null)).toList();
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
                ClassroomRosterEntry locked = rosterRepository
                        .findPendingForUpdate(classroomId, canonical).orElse(null);
                if (locked == null) {
                    return new LinkOutcome(false, null); // concurrently linked/revoked
                }
                // Defense in depth: re-check the verified proof INSIDE the
                // transaction — an unverified email can never link.
                com.quizopia.backend.identity.domain.model.User user =
                        classroomService.findUserByIdWithVerifiedEmail(userId, canonical);
                if (user == null) {
                    return new LinkOutcome(false, null);
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
