package com.quizopia.backend.classroom.rosterimport;

import com.quizopia.backend.classroom.application.ClassroomService;
import com.quizopia.backend.classroom.exception.ClassroomErrorCode;
import com.quizopia.backend.classroom.exception.ClassroomException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Identity-M4: generates the versioned classroom-roster XLSX template.
 *
 * <p>Headers mirror the manual "Thêm học sinh" form exactly (issue #16) and
 * {@code RosterExcelParser#EXPECTED_HEADERS}. All data cells are TEXT-formatted
 * ({@code @}) so Excel never coerces values, and the generator only ever writes
 * strings — no formulas, no evaluated content. A sample row shows the expected
 * shape; an Instructions sheet documents requiredness.
 */
@Service
public class RosterTemplateService {

    /** Version marker embedded in the Instructions sheet (schema evolution). */
    static final String TEMPLATE_VERSION = "v1";

    static final List<String> EXPECTED_HEADERS = List.of(
            "full_name", "student_number", "gender", "dob",
            "student_phone", "parent_phone", "email");

    private static final String TEXT_FORMAT = "@";
    private static final MediaType XLSX_MEDIA_TYPE =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ClassroomService classroomService;

    public RosterTemplateService(ClassroomService classroomService) {
        this.classroomService = classroomService;
    }

    /**
     * Authorization = classroom ownership (same deny-by-default path as the
     * roster API) — an outsider gets 403/404 before any byte is generated.
     */
    public void checkPermission(Long userId, Long classroomId) {
        classroomService.requireOwnedClassroomForRosterRead(userId, classroomId);
    }

    public byte[] generateTemplate() {
        try (Workbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            DataFormat dataFormat = workbook.createDataFormat();
            CellStyle textCellStyle = workbook.createCellStyle();
            textCellStyle.setDataFormat(dataFormat.getFormat(TEXT_FORMAT));

            Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(boldFont);

            Sheet roster = workbook.createSheet("Roster");
            Sheet instructions = workbook.createSheet("Instructions");

            buildRosterSheet(roster, headerStyle, textCellStyle);
            buildInstructionsSheet(instructions, headerStyle, textCellStyle);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new ClassroomException(ClassroomErrorCode.ROSTER_IMPORT_FILE_INVALID);
        }
    }

    private void buildRosterSheet(Sheet sheet, CellStyle headerStyle, CellStyle textStyle) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < EXPECTED_HEADERS.size(); i++) {
            Cell cell = header.createCell(i);
            cell.setCellType(CellType.STRING);
            cell.setCellValue(EXPECTED_HEADERS.get(i));
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(i, 22 * 256);
        }
        // Sample row (row 1) — strings only.
        Row sample = sheet.createRow(1);
        List<String> sampleValues = List.of(
                "Nguyen Van A", "HS001", "MALE", "2010-05-01",
                "+84991234567", "+84987654321", "learner@example.com");
        for (int i = 0; i < sampleValues.size(); i++) {
            Cell cell = sample.createCell(i);
            cell.setCellType(CellType.STRING);
            cell.setCellValue(sampleValues.get(i));
            cell.setCellStyle(textStyle);
        }
    }

    private void buildInstructionsSheet(Sheet sheet, CellStyle headerStyle, CellStyle textStyle) {
        Row title = sheet.createRow(0);
        Cell titleCell = title.createCell(0);
        titleCell.setCellType(CellType.STRING);
        titleCell.setCellValue("Classroom roster import — template " + TEMPLATE_VERSION);
        titleCell.setCellStyle(headerStyle);

        List<String> lines = List.of(
                "1. Do NOT rename, reorder, or delete the header row on the Roster sheet.",
                "2. full_name (required, max 150 chars) and email (required, max 254) drive the invitation.",
                "3. gender: MALE, FEMALE, OTHER or empty.",
                "4. dob: ISO date YYYY-MM-DD, in the past, or empty.",
                "5. student_phone / parent_phone: digits, spaces, + ( ) . - (5-20 chars) or empty.",
                "6. Duplicate emails within the file or already pending/linked in the class are rejected row-wise.",
                "7. No Quizopia account is created — learners join by verifying the invited email.",
                "8. FORMULA cells are rejected (leading = + - @ is treated as text, never evaluated).");
        for (int i = 0; i < lines.size(); i++) {
            Row row = sheet.createRow(2 + i);
            Cell cell = row.createCell(0);
            cell.setCellType(CellType.STRING);
            cell.setCellValue(lines.get(i));
            cell.setCellStyle(textStyle);
        }
        sheet.setColumnWidth(0, 110 * 256);
    }

    /** Builds the download response (caller has already authorized). */
    public ResponseEntity<byte[]> downloadResponse(byte[] xlsx) {
        return ResponseEntity.ok()
                .header("Content-Type", XLSX_MEDIA_TYPE.toString())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=quizopia-roster-template.xlsx")
                .body(xlsx);
    }
}
