package com.quizopia.backend.classroom.rosterimport;

import com.quizopia.backend.classroom.exception.ClassroomErrorCode;
import com.quizopia.backend.classroom.exception.ClassroomException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Identity-M4: streaming parser/validator for roster-import workbooks.
 *
 * <p>Structural rules (mirroring the Question Bank importer, without its
 * content-specific assumptions):
 * <ul>
 * <li>Only {@code .xlsx} (OOXML) — the OLE2 header of legacy {@code .xls}
 *     fails workbook construction.</li>
 * <li>Sheet 1 must carry the exact versioned header row.</li>
 * <li>Any FORMULA cell in a data column invalidates its row — values are
 *     never evaluated (formula-injection safeguard).</li>
 * <li>Row/column-specific errors. Within-file duplicate emails are NOT a
 *     parse error — the preview/confirm service decides per occurrence.</li>
 * <li>Row cap keeps memory bounded (streaming reader, no full-grid load).</li>
 * </ul>
 */
@Component
public class RosterExcelParser {

    static final int MAX_ROWS = 500;
    private static final String SHEET_NAME = "Roster";
    private static final Pattern PHONE = Pattern.compile("^[+0-9 ().-]{5,20}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern GENDER = Pattern.compile("^(MALE|FEMALE|OTHER)?$");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    /** A single validated data row (index 0 = first data row = sheet row 2). */
    public record ParsedRosterRow(
            int rowIndex,
            String fullName,
            String studentNumber,
            String gender,
            LocalDate dob,
            String phone,
            String parentPhone,
            String email) {
    }

    /** Row-level error bound to a sheet row (1-based, header = row 1). */
    public record RowError(int sheetRow, String column, String message) {
    }

    /** Parse outcome: valid rows + per-row errors; never both for one row. */
    public record ParseResult(List<ParsedRosterRow> validRows, List<RowError> errors) {
    }

    public ParseResult parse(InputStream in) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheet(SHEET_NAME);
            if (sheet == null) {
                throw new ClassroomException(ClassroomErrorCode.ROSTER_IMPORT_TEMPLATE_INVALID);
            }
            validateHeader(sheet);

            List<ParsedRosterRow> valid = new ArrayList<>();
            List<RowError> errors = new ArrayList<>();
            int dataRowCount = 0;

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isBlankRow(row)) {
                    continue;
                }
                dataRowCount++;
                if (dataRowCount > MAX_ROWS) {
                    throw new ClassroomException(ClassroomErrorCode.ROSTER_IMPORT_TOO_MANY_ROWS);
                }
                parseRow(row, valid, errors);
            }
            return new ParseResult(valid, errors);
        } catch (ClassroomException ce) {
            throw ce;
        } catch (Exception ex) {
            // Corrupt zip / wrong format / OOXML malware heuristics — one
            // code; the cause class is logged (never the workbook content).
            org.slf4j.LoggerFactory.getLogger(RosterExcelParser.class)
                    .warn("Roster workbook rejected: {} at {}", ex.getClass().getSimpleName(), ex.getStackTrace().length > 0 ? ex.getStackTrace()[0] : "?");
            throw new ClassroomException(ClassroomErrorCode.ROSTER_IMPORT_FILE_INVALID);
        }
    }

    private void validateHeader(Sheet sheet) {
        Row header = sheet.getRow(0);
        if (header == null) {
            throw new ClassroomException(ClassroomErrorCode.ROSTER_IMPORT_TEMPLATE_INVALID);
        }
        List<String> actual = new ArrayList<>();
        for (int c = 0; c < RosterTemplateService.EXPECTED_HEADERS.size(); c++) {
            Cell cell = header.getCell(c);
            actual.add(cell == null ? null : cellAsStringOrNull(cell));
        }
        for (int c = 0; c < RosterTemplateService.EXPECTED_HEADERS.size(); c++) {
            if (!RosterTemplateService.EXPECTED_HEADERS.get(c).equalsIgnoreCase(
                    actual.get(c) == null ? "" : actual.get(c).trim())) {
                throw new ClassroomException(ClassroomErrorCode.ROSTER_IMPORT_TEMPLATE_INVALID);
            }
        }
    }

    private void parseRow(Row row, List<ParsedRosterRow> valid, List<RowError> errors) {
        int sheetRow = row.getRowNum() + 1; // 1-based human row
        List<RowError> rowErrors = new ArrayList<>();

        // FORMULA safeguard first: any formula cell invalidates the row.
        for (int c = 0; c < RosterTemplateService.EXPECTED_HEADERS.size(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() == CellType.FORMULA) {
                rowErrors.add(new RowError(sheetRow, RosterTemplateService.EXPECTED_HEADERS.get(c),
                        "Formula cells are not allowed"));
            }
        }

        String fullName = text(row, 0, 150, "full_name", rowErrors);
        String studentNumber = optionalText(row, 1, 50, "student_number", rowErrors);
        String gender = optionalChoice(row, 2, GENDER, "gender", rowErrors);
        LocalDate dob = optionalDate(row, 3, "dob", rowErrors);
        String phone = optionalPattern(row, 4, PHONE, "student_phone", rowErrors);
        String parentPhone = optionalPattern(row, 5, PHONE, "parent_phone", rowErrors);
        String email = text(row, 6, 254, "email", rowErrors);
        String canonicalEmail = email == null ? null : email.trim().toLowerCase();

        if (fullName == null) { // blank/missing cell
            rowErrors.add(new RowError(sheetRow, "full_name", "Full name is required"));
        }
        if (canonicalEmail == null) {
            rowErrors.add(new RowError(sheetRow, "email", "Email is required"));
        } else if (!EMAIL.matcher(canonicalEmail).matches()) {
            rowErrors.add(new RowError(sheetRow, "email", "Invalid email format"));
        }

        if (rowErrors.isEmpty()) {
            valid.add(new ParsedRosterRow(sheetRow - 2, fullName.trim(), studentNumber,
                    gender == null || gender.isBlank() ? null : gender.toUpperCase(), dob,
                    phone, parentPhone, canonicalEmail));
        } else {
            errors.addAll(rowErrors);
        }
    }

    // ---------- cell readers ----------

    private String cellAsStringOrNull(Cell cell) {
        try {
            return switch (cell.getCellType()) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                        ? cell.getLocalDateTimeCellValue().toLocalDate().format(ISO)
                        : trimNumeric(cell.getNumericCellValue());
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                default -> null;
            };
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String trimNumeric(double d) {
        if (d == Math.floor(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    /**
     * Review M4-SEC: FORMULA cells count as NON-BLANK — a formula-only row
     * must flow into parseRow (formula rejection + row cap), not be skipped
     * as blank.
     */
    private boolean isBlankRow(Row row) {
        for (int c = 0; c < RosterTemplateService.EXPECTED_HEADERS.size(); c++) {
            Cell cell = row.getCell(c);
            if (cell == null || cell.getCellType() == CellType.BLANK) {
                continue;
            }
            if (cell.getCellType() == CellType.FORMULA) {
                return false;
            }
            String v = cellAsStringOrNull(cell);
            if (v != null && !v.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Review M4-SEC: bound the DECOMPRESSED workbook before POI materializes
     * it. A 2 MiB zip can inflate to gigabytes; these limits (entries,
     * per-entry, total) are enforced while streaming the archive, before any
     * DOM load. POI's ZipSecureFile heuristics remain as defense in depth.
     */
    static final int MAX_ZIP_ENTRIES = 500;
    static final long MAX_ZIP_ENTRY_BYTES = 8L * 1024 * 1024;
    static final long MAX_ZIP_TOTAL_BYTES = 32L * 1024 * 1024;

    static void validateZipLimits(byte[] xlsxBytes) {
        try (java.util.zip.ZipInputStream zip =
                new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(xlsxBytes))) {
            int entries = 0;
            long total = 0;
            java.util.zip.ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) {
                    throw new ClassroomException(ClassroomErrorCode.ROSTER_IMPORT_FILE_INVALID);
                }
                long entryBytes = 0;
                int n;
                while ((n = zip.read(buf)) > 0) {
                    entryBytes += n;
                    total += n;
                    if (entryBytes > MAX_ZIP_ENTRY_BYTES || total > MAX_ZIP_TOTAL_BYTES) {
                        throw new ClassroomException(ClassroomErrorCode.ROSTER_IMPORT_FILE_INVALID);
                    }
                }
            }
        } catch (ClassroomException ce) {
            throw ce;
        } catch (Exception ex) {
            throw new ClassroomException(ClassroomErrorCode.ROSTER_IMPORT_FILE_INVALID);
        }
    }

    private String text(Row row, int col, int maxLen, String column, List<RowError> errors) {
        Cell cell = row.getCell(col);
        String raw = cell == null ? null : cellAsStringOrNull(cell);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.length() > maxLen) {
            errors.add(new RowError(row.getRowNum() + 1, column, "Max " + maxLen + " characters"));
            return null;
        }
        return raw;
    }

    private String optionalText(Row row, int col, int maxLen, String column, List<RowError> errors) {
        String v = text(row, col, maxLen, column, errors);
        return v == null ? null : v.trim();
    }

    private String optionalChoice(Row row, int col, Pattern pattern, String column,
            List<RowError> errors) {
        String v = text(row, col, 20, column, errors);
        if (v == null) {
            return null;
        }
        String upper = v.trim().toUpperCase();
        if (!pattern.matcher(upper).matches()) {
            errors.add(new RowError(row.getRowNum() + 1, column, "Must be MALE, FEMALE or OTHER"));
            return null;
        }
        return upper;
    }

    private String optionalPattern(Row row, int col, Pattern pattern, String column,
            List<RowError> errors) {
        String v = text(row, col, 20, column, errors);
        if (v == null) {
            return null;
        }
        if (!pattern.matcher(v).matches()) {
            errors.add(new RowError(row.getRowNum() + 1, column, "Invalid format"));
            return null;
        }
        return v;
    }

    private LocalDate optionalDate(Row row, int col, String column, List<RowError> errors) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            return null;
        }
        String raw = cellAsStringOrNull(cell);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            LocalDate parsed = LocalDate.parse(trimmed, ISO);
            if (!parsed.isBefore(LocalDate.now())) {
                errors.add(new RowError(row.getRowNum() + 1, column, "Date of birth must be in the past"));
                return null;
            }
            return parsed;
        } catch (RuntimeException ex) {
            errors.add(new RowError(row.getRowNum() + 1, column, "Use ISO date YYYY-MM-DD"));
            return null;
        }
    }
}
