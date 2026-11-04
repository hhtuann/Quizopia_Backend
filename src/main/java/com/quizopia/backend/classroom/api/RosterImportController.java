package com.quizopia.backend.classroom.api;

import com.quizopia.backend.classroom.rosterimport.RosterImportService;
import com.quizopia.backend.classroom.rosterimport.RosterTemplateService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Identity-M4 (issue #16): XLSX roster template download + upload preview +
 * confirm. Authorization (classroom ownership) runs BEFORE any byte of the
 * workbook is read; preview never mutates state; confirm is idempotent with
 * per-row partial success.
 */
@RestController
@RequestMapping("/api/classrooms")
public class RosterImportController {

    private final RosterTemplateService templateService;
    private final RosterImportService importService;

    public RosterImportController(RosterTemplateService templateService,
            RosterImportService importService) {
        this.templateService = templateService;
        this.importService = importService;
    }

    /** GET /api/classrooms/{id}/roster-import/template — versioned XLSX. */
    @GetMapping("/{id}/roster-import/template")
    public ResponseEntity<byte[]> downloadTemplate(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Long userId = Long.valueOf(jwt.getSubject());
        templateService.checkPermission(userId, id);
        return templateService.downloadResponse(templateService.generateTemplate());
    }

    /** POST /api/classrooms/{id}/roster-import/preview — read-only parse. */
    @PostMapping(value = "/{id}/roster-import/preview",
            consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public RosterImportService.PreviewResult preview(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @RequestPart("file") MultipartFile file) {
        Long userId = Long.valueOf(jwt.getSubject());
        // Preview stays READ-permission + ownership, before any byte is read.
        templateService.checkPermission(userId, id);
        return importService.preview(userId, id, file);
    }

    /**
     * POST /api/classrooms/{id}/roster-import/confirm — idempotent batch.
     * Review M4-02: confirm MUTATES, so it requires the WRITE permission
     * (CLASSROOM_MEMBER_ADD + ownership) BEFORE any byte is parsed — a
     * READ-only owner gets an upfront 403 instead of row failures.
     */
    @PostMapping(value = "/{id}/roster-import/confirm",
            consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public RosterImportService.ConfirmResult confirm(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @RequestPart("file") MultipartFile file) {
        Long userId = Long.valueOf(jwt.getSubject());
        importService.requireConfirmWriteAccess(userId, id);
        return importService.confirm(userId, id, file);
    }
}
