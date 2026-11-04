package com.quizopia.backend.classroom.rosterimport;

import com.quizopia.backend.classroom.domain.model.ClassroomRosterEntry;
import com.quizopia.backend.classroom.repository.ClassroomRosterEntryRepository;
import com.quizopia.backend.classroom.repository.ClassroomRepository;
import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.classroom.domain.model.Classroom;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Identity-M4 (issue #16): XLSX template generation, preview (read-only),
 * confirm (idempotent, partial success), malformed-file handling, duplicate
 * detection, and authorization. Real XLSX bytes roundtrip through POI.
 */
@SpringBootTest
@Import({ com.quizopia.backend.testsupport.PostgresTestContainerConfiguration.class,
        com.quizopia.backend.testsupport.TestClockConfig.class })
@ActiveProfiles("test")
class RosterImportIntegrationTests extends AbstractAuthenticationIntegrationTests {

    @Autowired
    private RosterTemplateService templateService;

    @Autowired
    private RosterImportService importService;

    @Autowired
    private ClassroomRosterEntryRepository rosterRepository;

    @Autowired
    private ClassroomRepository classroomRepository;

    // ---------- helpers ----------

    private static String unique(String base) {
        return base + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    private static String emailFor(String username) {
        return username + "@example.com";
    }

    /** Resolves the classroom's owner user id (confirm needs a real creator). */
    private Long ownerOf(Long classroomId) {
        return classroomRepository.findById(classroomId)
                .map(Classroom::getOwnerUserId).orElseThrow();
    }

    /** Creates a self-upgraded teacher + independent classroom; returns token+id. */
    private record Teacher(String token, Long classroomId) {
    }

    private Teacher teacherWithClassroom(String code) throws Exception {
        String username = unique("m4t");
        registerAndVerify(username, emailFor(username), "Passw0rd!");
        String token = extractAccessToken(login(emailFor(username), "Passw0rd!"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/upgrade-teacher")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        MvcResult created = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/classrooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"code\":\"%s\",\"name\":\"M4 %s\"}".formatted(code, code)))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        Long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        return new Teacher(token, id);
    }

    /** Builds a workbook matching the template with the given data rows. */
    private byte[] workbook(List<List<String>> dataRows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            DataFormat fmt = wb.createDataFormat();
            CellStyle text = wb.createCellStyle();
            text.setDataFormat(fmt.getFormat("@"));
            Sheet sheet = wb.createSheet("Roster");
            Row header = sheet.createRow(0);
            for (int c = 0; c < RosterTemplateService.EXPECTED_HEADERS.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellType(CellType.STRING);
                cell.setCellValue(RosterTemplateService.EXPECTED_HEADERS.get(c));
            }
            for (int r = 0; r < dataRows.size(); r++) {
                Row row = sheet.createRow(1 + r);
                List<String> data = dataRows.get(r);
                for (int c = 0; c < data.size(); c++) {
                    Cell cell = row.createCell(c);
                    cell.setCellType(CellType.STRING);
                    cell.setCellValue(data.get(c));
                    cell.setCellStyle(text);
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private MockMultipartFile xlsx(byte[] bytes) {
        return new MockMultipartFile("file", "roster.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    private List<List<String>> twoValidRows(String emailA, String emailB) {
        return List.of(
                List.of("Nguyen Van A", "HS001", "MALE", "2010-05-01", "+84991234567",
                        "+84987654321", emailA),
                List.of("Tran Thi B", "HS002", "FEMALE", "2009-11-30", "+84991112222",
                        "+84993334444", emailB));
    }

    // ---------- template ----------

    @Test
    void templateDownloadsAndRoundtripsThroughTheParser() throws Exception {
        Teacher t = teacherWithClassroom("TPL1");
        byte[] xlsxBytes = templateService.generateTemplate();

        // The template itself parses cleanly; its sample row is valid.
        RosterImportService.PreviewResult preview = importService.preview(
                ownerOf(t.classroomId()), t.classroomId(), xlsx(xlsxBytes));
        assertThat(preview.totalRows()).isEqualTo(1); // the sample row
        assertThat(preview.rows().get(0).status()).isEqualTo("READY");
        assertThat(preview.rows().get(0).email()).isEqualTo("learner@example.com");
    }

    // ---------- preview: read-only, duplicates, row errors ----------

    @Test
    void previewIsReadOnlyAndReportsDuplicatesWithinFileAndClass() throws Exception {
        Teacher t = teacherWithClassroom("PRE1");
        String dupEmail = emailFor(unique("dup"));

        // Seed an existing PENDING entry in the class.
        importService.confirm(ownerOf(t.classroomId()), t.classroomId(), xlsx(workbook(List.of(
                List.of("Existing Kid", "", "", "", "", "", dupEmail)))));

        RosterImportService.PreviewResult preview = importService.preview(ownerOf(t.classroomId()), t.classroomId(),
                xlsx(workbook(List.of(
                        List.of("Row One", "", "", "", "", "", dupEmail),        // class dup
                        List.of("Row Two", "", "", "", "", "", emailFor(unique("ok"))),
                        List.of("Row Three", "", "", "", "", "", dupEmail),      // file dup
                        List.of("", "", "", "", "", "", emailFor(unique("x"))),  // missing name
                        List.of("Bad Email", "", "", "", "", "", "not-an-email")))));

        assertThat(preview.totalRows()).isEqualTo(5);
        assertThat(preview.ready()).isEqualTo(1); // only Row Two (Row One is a class dup)
        assertThat(preview.rows().stream().filter(r -> "DUPLICATE_IN_CLASS".equals(r.status())).count()).isEqualTo(1);
        assertThat(preview.rows().stream().filter(r -> "DUPLICATE_IN_FILE".equals(r.status())).count()).isEqualTo(1);
        // Missing full_name + invalid email surface as ERROR with column detail.
        List<RosterImportService.PreviewRow> errors =
                preview.rows().stream().filter(r -> "ERROR".equals(r.status())).toList();
        assertThat(errors).hasSize(2);
        assertThat(errors.stream().flatMap(e -> e.issues().stream())
                .anyMatch(i -> i.startsWith("full_name")));
        assertThat(errors.stream().flatMap(e -> e.issues().stream())
                .anyMatch(i -> i.startsWith("email")));
        // Read-only: nothing beyond the seeded entry exists.
        assertThat(rosterRepository.findByClassroomIdOrderByCreatedAtDesc(
                t.classroomId(), org.springframework.data.domain.PageRequest.of(0, 50)))
                .hasSize(1);
    }

    // ---------- confirm: partial success + idempotency ----------

    @Test
    void confirmImportsValidRowsSkipsInvalidAndIsIdempotent() throws Exception {
        Teacher t = teacherWithClassroom("CONF1");
        String goodA = emailFor(unique("ga"));
        String goodB = emailFor(unique("gb"));
        String dupInFile = emailFor(unique("df"));

        byte[] book = workbook(List.of(
                List.of("Good A", "", "", "", "", "", goodA),
                List.of("Good B", "", "", "", "", "", goodB),
                List.of("Dup One", "", "", "", "", "", dupInFile),
                List.of("Dup Two", "", "", "", "", "", dupInFile),
                List.of("Bad Date", "", "", "2050-01-01", "", "", emailFor(unique("bd")))));

        Long ownerUserId = ownerOf(t.classroomId());
        RosterImportService.ConfirmResult first = importService.confirm(
                ownerUserId, t.classroomId(), xlsx(book));
        // goodA + goodB + the FIRST occurrence of dupInFile import; the second
        // occurrence is a within-file duplicate; the future DOB row fails.
        assertThat(first.imported()).isEqualTo(3);
        assertThat(first.duplicates()).isEqualTo(1);
        assertThat(first.failed()).isEqualTo(1);
        assertThat(first.errors().get(0).column()).isEqualTo("dob");

        // Re-run the SAME file: idempotent — everything is now a duplicate.
        RosterImportService.ConfirmResult second = importService.confirm(
                ownerUserId, t.classroomId(), xlsx(book));
        assertThat(second.imported()).isZero();
        // goodA, goodB, dupInFile(first) now exist in class; dupInFile(second)
        // is a within-file duplicate again → all four are duplicates.
        assertThat(second.duplicates()).isEqualTo(4);
        assertThat(second.failed()).isEqualTo(1); // bad-date row re-fails

        // Exactly three entries exist (goodA, goodB, dupInFile-first), PENDING.
        var entries = rosterRepository.findByClassroomIdOrderByCreatedAtDesc(
                t.classroomId(), org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(entries).hasSize(3);
        assertThat(entries).allSatisfy(e ->
                assertThat(e.getStatus()).isEqualTo(ClassroomRosterEntry.RosterStatus.PENDING));
    }

    // ---------- malformed / dangerous files ----------

    @Test
    void malformedFilesAndFormulaCellsAreRejected() throws Exception {
        Teacher t = teacherWithClassroom("MAL1");

        // Not a zip at all.
        MockMultipartFile garbage = new MockMultipartFile("file", "roster.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "this is not a workbook".getBytes());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> importService.preview(ownerOf(t.classroomId()), t.classroomId(), garbage))
                .isInstanceOf(com.quizopia.backend.classroom.exception.ClassroomException.class)
                .hasMessageContaining("Invalid roster import file");

        // Wrong header → TEMPLATE_INVALID (headers built explicitly wrong).
        byte[] wrongHeader;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Roster");
            Row header = sheet.createRow(0);
            for (int c = 0; c < 7; c++) {
                header.createCell(c).setCellValue("wrong_" + c);
            }
            wb.write(out);
            wrongHeader = out.toByteArray();
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> importService.preview(ownerOf(t.classroomId()),
                        t.classroomId(), xlsx(wrongHeader)))
                .hasMessageContaining("template");

        // FORMULA cell in email column → row error, never evaluated.
        byte[] formulaBook;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Roster");
            Row header = sheet.createRow(0);
            for (int c = 0; c < RosterTemplateService.EXPECTED_HEADERS.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(RosterTemplateService.EXPECTED_HEADERS.get(c));
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Formula Kid");
            Cell evil = row.createCell(6);
            evil.setCellFormula("HYPERLINK(\"http://evil\",\"x\")");
            wb.write(out);
            formulaBook = out.toByteArray();
        }
        RosterImportService.PreviewResult preview = importService.preview(ownerOf(t.classroomId()),
                t.classroomId(), xlsx(formulaBook));
        assertThat(preview.ready()).isZero();
        assertThat(preview.rows().stream().flatMap(r -> r.issues().stream())
                .anyMatch(i -> i.contains("Formula")));

        // Oversized upload.
        MockMultipartFile tooBig = new MockMultipartFile("file", "roster.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[3 * 1024 * 1024]);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> importService.preview(ownerOf(t.classroomId()), t.classroomId(), tooBig))
                .hasMessageContaining("exceeds");
    }

    // ---------- HTTP contract ----------

    @Test
    void endpointsEnforceOwnershipAndReturnStructuredResults() throws Exception {
        Teacher owner = teacherWithClassroom("HTTP1");
        Teacher stranger = teacherWithClassroom("HTTP2");
        byte[] book = workbook(List.of(List.of("Http Kid", "", "", "", "", "",
                emailFor(unique("http")))));

        // Template download: owner 200 + xlsx magic bytes; stranger 403.
        MvcResult tpl = mockMvc.perform(get("/api/classrooms/" + owner.classroomId()
                        + "/roster-import/template")
                        .header("Authorization", "Bearer " + owner.token()))
                .andReturn();
        assertThat(tpl.getResponse().getStatus()).isEqualTo(200);
        assertThat(tpl.getResponse().getContentAsByteArray().length).isGreaterThan(3);
        assertThat(tpl.getResponse().getContentAsByteArray()[0]).isEqualTo((byte) 'P'); // PK zip

        MvcResult tplDenied = mockMvc.perform(get("/api/classrooms/" + owner.classroomId()
                        + "/roster-import/template")
                        .header("Authorization", "Bearer " + stranger.token()))
                .andReturn();
        assertThat(tplDenied.getResponse().getStatus()).isEqualTo(403);

        // Preview: stranger 403, unauthenticated 401.
        MvcResult previewDenied = mockMvc.perform(multipart(
                        "/api/classrooms/" + owner.classroomId() + "/roster-import/preview")
                        .file(xlsx(book))
                        .header("Authorization", "Bearer " + stranger.token()))
                .andReturn();
        assertThat(previewDenied.getResponse().getStatus()).isEqualTo(403);
        MvcResult anon = mockMvc.perform(multipart(
                        "/api/classrooms/" + owner.classroomId() + "/roster-import/preview")
                        .file(xlsx(book)))
                .andReturn();
        assertThat(anon.getResponse().getStatus()).isEqualTo(401);

        // Owner preview 200 with JSON shape.
        MvcResult ok = mockMvc.perform(multipart(
                        "/api/classrooms/" + owner.classroomId() + "/roster-import/preview")
                        .file(xlsx(book))
                        .header("Authorization", "Bearer " + owner.token()))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(ok.getResponse().getContentAsString());
        assertThat(body.has("totalRows")).isTrue();
        assertThat(body.get("ready").asInt()).isEqualTo(1);

        // Confirm: owner 200, imported 1.
        MvcResult confirmed = mockMvc.perform(multipart(
                        "/api/classrooms/" + owner.classroomId() + "/roster-import/confirm")
                        .file(xlsx(book))
                        .header("Authorization", "Bearer " + owner.token()))
                .andReturn();
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(confirmed.getResponse().getContentAsString())
                .get("imported").asInt()).isEqualTo(1);
    }
}
