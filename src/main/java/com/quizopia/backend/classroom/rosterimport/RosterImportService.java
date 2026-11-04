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

    private final RosterExcelParser parser;
    private final ClassroomRosterEntryRepository rosterRepository;
    private final ClassroomRosterService rosterService;

    public RosterImportService(RosterExcelParser parser,
            ClassroomRosterEntryRepository rosterRepository,
            ClassroomRosterService rosterService) {
        this.parser = parser;
        this.rosterRepository = rosterRepository;
        this.rosterService = rosterService;
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
        for (RosterExcelParser.RowError err : parsed.errors()) {
            rows.add(new PreviewRow(err.sheetRow(), null, null, null, null, null, null, null,
                    "ERROR", List.of(err.column() + ": " + err.message())));
        }
        rows.sort((a, b) -> Integer.compare(a.sheetRow(), b.sheetRow()));
        return new PreviewResult(parsed.validRows().size() + parsed.errors().size(), ready, rows);
    }

    /**
     * Idempotent confirm: imports every READY row, re-checked live. Rows that
     * collide with state created after the preview are counted as duplicates;
     * structural errors can only appear if the workbook changed (re-parsed
     * here from the same upload).
     */
    @Transactional
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
                rosterService.addRosterEntry(userId, classroomId, new CreateRosterEntryRequest(
                        row.fullName(), row.studentNumber(), row.gender(), row.dob(),
                        row.phone(), row.parentPhone(), canonical));
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
        return new ConfirmResult(imported, duplicates, errors.size(), errors);
    }

    // ---------- internals ----------

    private RosterExcelParser.ParseResult parseFile(MultipartFile file) {
        validateFileMetadata(file);
        try (InputStream in = file.getInputStream()) {
            return parser.parse(in);
        } catch (IOException ex) {
            throw new ClassroomException(ClassroomErrorCode.ROSTER_IMPORT_FILE_INVALID);
        }
    }

    /** Metadata validation BEFORE any byte is parsed (fail fast, cheap). */
    private void validateFileMetadata(MultipartFile file) {
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
        // Deterministic OOXML (zip) magic check before handing bytes to POI.
        try (java.io.InputStream probe = file.getInputStream()) {
            byte[] magic = probe.readNBytes(4);
            boolean zip = magic.length == 4
                    && magic[0] == 'P' && magic[1] == 'K'
                    && (magic[2] == 3 || magic[2] == 5 || magic[2] == 7);
            if (!zip) {
                throw new ClassroomException(ClassroomErrorCode.ROSTER_IMPORT_FILE_INVALID);
            }
        } catch (IOException ex) {
            throw new ClassroomException(ClassroomErrorCode.ROSTER_IMPORT_FILE_INVALID);
        }
    }

    private Set<String> activeClassroomEmails(Long classroomId) {
        Set<String> emails = new HashSet<>();
        rosterRepository.findByClassroomIdOrderByCreatedAtDesc(classroomId, org.springframework.data.domain.PageRequest.of(0, 200)).forEach(e -> {
            if (e.getStatus() == ClassroomRosterEntry.RosterStatus.PENDING
                    || e.getStatus() == ClassroomRosterEntry.RosterStatus.LINKED) {
                emails.add(e.getEmailNormalized());
            }
        });
        return emails;
    }
}
