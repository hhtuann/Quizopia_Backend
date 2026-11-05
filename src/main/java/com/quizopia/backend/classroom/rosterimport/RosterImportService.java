package com.quizopia.backend.classroom.rosterimport;

import com.quizopia.backend.classroom.application.ClassroomRosterService;
import com.quizopia.backend.classroom.domain.model.Classroom;
import com.quizopia.backend.classroom.domain.model.ClassroomRosterEntry;
import com.quizopia.backend.classroom.dto.CreateRosterEntryRequest;
import com.quizopia.backend.classroom.dto.RosterEntryResponse;
import com.quizopia.backend.classroom.exception.ClassroomErrorCode;
import com.quizopia.backend.classroom.exception.ClassroomException;
import com.quizopia.backend.classroom.repository.ClassroomRosterEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Identity-M4: preview + confirm for roster XLSX imports.
 *
 * <p><b>Preview is read-only</b>: the workbook is parsed and cross-checked
 * against the classroom's current roster state; nothing is persisted.
 *
 * <p><b>Confirm is idempotent with partial success</b>: rows are re-validated
 * against live state inside the transaction (state may have changed since
 * preview). Each row is inserted independently via the M3 roster path
 * (encryption + immediate verified-link reconciliation); rows that became
 * duplicates in the meantime are reported as skipped, not errors. Re-running
 * the same confirm inserts nothing the second time — every row comes back
 * {@code duplicate}.
 */
@Service
public class RosterImportService {

    /** Preview row: either ready-to-import or blocked with reasons. */
    public record PreviewRow(
            int sheetRow,
            String fullName,
            String studentNumber,
            String gender,
            String dob,
            String phone,
            String parentPhone,
            String email,
            String status, // READY | DUPLICATE_IN_FILE | DUPLICATE_IN_CLASS | ERROR
            List<String> issues) {
    }

    public record PreviewResult(int totalRows, int ready, List<PreviewRow> rows) {
    }

    public record ConfirmResult(int imported, int duplicates, int failed,
            List<RosterExcelParser.RowError> errors) {
    }

    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024; // 2 MB

    private final com.quizopia.backend.classroom.application.ClassroomService classroomService;
    private final RosterExcelParser parser;
    private final ClassroomRosterEntryRepository rosterRepository;
    private final ClassroomRosterService rosterService;
    /** Per-row REQUIRES_NEW transactions (partial-success isolation). */
    private final org.springframework.transaction.support.TransactionTemplate rowTx;

    public RosterImportService(
            com.quizopia.backend.classroom.application.ClassroomService classroomService,
            RosterExcelParser parser,
            ClassroomRosterEntryRepository rosterRepository,
            ClassroomRosterService rosterService,
            org.springframework.transaction.PlatformTransactionManager txManager) {
        this.classroomService = classroomService;
        this.parser = parser;
        this.rosterRepository = rosterRepository;
        this.rosterService = rosterService;
        this.rowTx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.rowTx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Review M4-02: confirm's upfront write check — CLASSROOM_MEMBER_ADD +
     * classroom ownership, BEFORE the workbook is read (template/preview keep
     * their READ-permission semantics).
     */
    public void requireConfirmWriteAccess(Long userId, Long classroomId) {
        classroomService.requireOwnedClassroomForRoster(userId, classroomId);
    }

    /** Read-only preview (authorization happens in the controller first). */
    @Transactional(readOnly = true)
    public PreviewResult preview(Long userId, Long classroomId, MultipartFile file) {
        RosterExcelParser.ParseResult parsed = parseFile(file);
        Set<String> activeEmails = activeClassroomEmails(classroomId);

        List<PreviewRow> rows = new ArrayList<>();
        Set<String> fileEmails = new HashSet<>();
        int ready = 0;
        for (RosterExcelParser.ParsedRosterRow row : parsed.validRows()) {
            List<String> issues = new ArrayList<>();
            String status = "READY";
            String canonical = row.email().trim().toLowerCase();
            if (!fileEmails.add(canonical)) {
                issues.add("Duplicate email within the file");
                status = "DUPLICATE_IN_FILE";
            } else if (activeEmails.contains(canonical)) {
                issues.add("Email already pending or linked in this class");
                status = "DUPLICATE_IN_CLASS";
            }
            if (issues.isEmpty()) {
                ready++;
            }
            rows.add(new PreviewRow(row.rowIndex() + 2, row.fullName(), row.studentNumber(),
                    row.gender(), row.dob() == null ? null : row.dob().toString(),
                    row.phone(), row.parentPhone(), canonical, status, issues));
        }
        // Review M4-QA: ONE preview row per sheet row — multiple column errors
        // on one row are merged into that row's issues (not separate rows).
        Map<Integer, List<String>> errorsByRow = new java.util.LinkedHashMap<>();
        for (RosterExcelParser.RowError err : parsed.errors()) {
            errorsByRow.computeIfAbsent(err.sheetRow(), k -> new ArrayList<>())
                    .add(err.column() + ": " + err.message());
        }
        for (Map.Entry<Integer, List<String>> e : errorsByRow.entrySet()) {
            rows.add(new PreviewRow(e.getKey(), null, null, null, null, null, null, null,
                    "ERROR", e.getValue()));
        }
        rows.sort((a, b) -> Integer.compare(a.sheetRow(), b.sheetRow()));
        return new PreviewResult(rows.size(), ready, rows);
    }

    /**
     * Idempotent confirm: imports every READY row, re-checked live. Rows that
     * collide with state created after the preview are counted as duplicates;
     * structural errors can only appear if the workbook changed (re-parsed
     * here from the same upload).
     *
     * <p>Review M4-SEC: NO enclosing batch transaction — each row imports in
     * its own REQUIRES_NEW transaction so a failed row (e.g. a concurrent
     * duplicate insert, which aborts the PostgreSQL transaction) is counted
     * and skipped WITHOUT poisoning the other rows' commits. This is what
     * makes the partial-success policy real under concurrency.
     */
    public ConfirmResult confirm(Long userId, Long classroomId, MultipartFile file) {
        RosterExcelParser.ParseResult parsed = parseFile(file);

        int imported = 0;
        int duplicates = 0;
        List<RosterExcelParser.RowError> errors = new ArrayList<>(parsed.errors());

        Set<String> seen = new HashSet<>();
        for (RosterExcelParser.ParsedRosterRow row : parsed.validRows()) {
            String canonical = row.email().trim().toLowerCase();
            if (!seen.add(canonical)) {
                duplicates++;
                continue;
            }
            if (rosterRepository.existsByClassroomIdAndEmailNormalizedAndStatus(
                    classroomId, canonical, ClassroomRosterEntry.RosterStatus.PENDING)
                    || rosterRepository.existsByClassroomIdAndEmailNormalizedAndStatus(
                            classroomId, canonical, ClassroomRosterEntry.RosterStatus.LINKED)) {
                duplicates++;
                continue;
            }
            try {
                rowTx.executeWithoutResult(tx -> rosterService.addRosterEntry(
                        userId, classroomId, new CreateRosterEntryRequest(
                                row.fullName(), row.studentNumber(), row.gender(), row.dob(),
                                row.phone(), row.parentPhone(), canonical)));
                imported++;
            } catch (ClassroomException dupe) {
                if (dupe.getErrorCode() == ClassroomErrorCode.ROSTER_DUPLICATE_PENDING) {
                    duplicates++;
                } else {
                    errors.add(new RosterExcelParser.RowError(row.rowIndex() + 2, "email",
                            dupe.getMessage()));
                }
            }
        }
        // Review M4-QA: failed counts DISTINCT rows (one row may carry several
        // column errors).
        long failed = errors.stream().map(RosterExcelParser.RowError::sheetRow).distinct().count();
        return new ConfirmResult(imported, duplicates, (int) failed, errors);
    }

    // ---------- internals ----------

    private RosterExcelParser.ParseResult parseFile(MultipartFile file) {
        byte[] bytes = validateFileMetadata(file);
        // Review M4-SEC: decompression bounds BEFORE POI materializes the DOM.
        RosterExcelParser.validateZipLimits(bytes);
        return parser.parse(new java.io.ByteArrayInputStream(bytes));
    }

    /** Metadata validation BEFORE parsing; returns the bounded upload bytes. */
    private byte[] validateFileMetadata(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ClassroomException(ClassroomErrorCode.ROSTER_IMPORT_FILE_INVALID);
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ClassroomException(ClassroomErrorCode.ROSTER_IMPORT_FILE_TOO_LARGE);
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".xlsx")) {
            throw new ClassroomException(ClassroomErrorCode.ROSTER_IMPORT_FILE_INVALID);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new ClassroomException(ClassroomErrorCode.ROSTER_IMPORT_FILE_INVALID);
        }
        // Deterministic OOXML (zip) magic check before handing bytes to POI.
        boolean zip = bytes.length >= 4
                && bytes[0] == 'P' && bytes[1] == 'K'
                && (bytes[2] == 3 || bytes[2] == 5 || bytes[2] == 7);
        if (!zip) {
            throw new ClassroomException(ClassroomErrorCode.ROSTER_IMPORT_FILE_INVALID);
        }
        return bytes;
    }

    /** Unpaged (review M4-QA): must see ALL active invitations, not page 1. */
    private Set<String> activeClassroomEmails(Long classroomId) {
        return new HashSet<>(rosterRepository.findActiveEmailsByClassroomId(classroomId));
    }
}
