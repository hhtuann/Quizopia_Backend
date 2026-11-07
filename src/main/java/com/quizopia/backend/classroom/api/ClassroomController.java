package com.quizopia.backend.classroom.api;

import com.quizopia.backend.classroom.application.ClassroomRosterService;
import com.quizopia.backend.classroom.application.ClassroomService;
import com.quizopia.backend.classroom.dto.CreateRosterEntryRequest;
import com.quizopia.backend.classroom.dto.RosterEntryResponse;
import com.quizopia.backend.classroom.dto.AddMembersRequest;
import com.quizopia.backend.classroom.dto.AddMembersResponse;
import com.quizopia.backend.classroom.dto.ClassroomDetailView;
import com.quizopia.backend.classroom.dto.ClassroomMemberResponse;
import com.quizopia.backend.classroom.dto.ClassroomResponse;
import com.quizopia.backend.classroom.dto.CreateClassroomRequest;
import com.quizopia.backend.classroom.dto.MyClassroomsResponse;
import com.quizopia.backend.classroom.dto.UpdateClassroomRequest;
import com.quizopia.backend.question.dto.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/classrooms")
public class ClassroomController {

    private final ClassroomService classroomService;
    private final ClassroomRosterService rosterService;

    public ClassroomController(ClassroomService classroomService, ClassroomRosterService rosterService) {
        this.classroomService = classroomService;
        this.rosterService = rosterService;
    }

    // ============================================================
    // Identity-M3 roster (issue #15)
    // ============================================================

    @PostMapping("/{id}/roster")
    public ResponseEntity<RosterEntryResponse> addRosterEntry(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody CreateRosterEntryRequest request) {
        Long userId = Long.valueOf(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(rosterService.addRosterEntry(userId, id, request));
    }

    @GetMapping("/{id}/roster")
    public PageResponse<RosterEntryResponse> listRoster(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Long userId = Long.valueOf(jwt.getSubject());
        var rosterPage = rosterService.listRoster(userId, id, page, size);
        return new PageResponse<>(rosterPage.getContent(), rosterPage.getNumber(),
                rosterPage.getSize(), rosterPage.getTotalElements(), rosterPage.getTotalPages(),
                "createdAt,desc");
    }

    @DeleteMapping("/{id}/roster/{entryId}")
    public ResponseEntity<Void> revokeRosterEntry(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @PathVariable Long entryId) {
        Long userId = Long.valueOf(jwt.getSubject());
        rosterService.revokeRosterEntry(userId, id, entryId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/roster/my")
    public List<RosterEntryResponse> myRosterEntries(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return rosterService.myLinkedEntries(userId);
    }

    /** Review M3-02: PENDING invitations awaiting the caller's consent. */
    @GetMapping("/roster/invitations")
    public List<RosterEntryResponse> myPendingInvitations(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return rosterService.myPendingInvitations(userId);
    }

    /** Review M3-02: learner-initiated consent — the only relink path for a
     *  classroom the learner previously DECLINED. */
    @PostMapping("/roster/{entryId}/accept")
    public RosterEntryResponse acceptInvitation(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long entryId) {
        Long userId = Long.valueOf(jwt.getSubject());
        return rosterService.acceptEntry(userId, entryId);
    }

    @PostMapping("/roster/{entryId}/decline")
    public ResponseEntity<Void> declineRosterEntry(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long entryId) {
        Long userId = Long.valueOf(jwt.getSubject());
        rosterService.declineEntry(userId, entryId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping
    public ResponseEntity<ClassroomResponse> createClassroom(
            @Valid @RequestBody CreateClassroomRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(classroomService.createClassroom(userId, request));
    }

    @GetMapping("/my")
    public MyClassroomsResponse myClassrooms(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return classroomService.listMyClassrooms(userId);
    }

    @GetMapping("/{id}")
    public ClassroomDetailView getClassroom(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Long userId = Long.valueOf(jwt.getSubject());
        return classroomService.getClassroom(userId, id);
    }

    @PutMapping("/{id}")
    public ClassroomResponse updateClassroom(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody UpdateClassroomRequest request) {
        Long userId = Long.valueOf(jwt.getSubject());
        return classroomService.updateClassroom(userId, id, request);
    }

    @PostMapping("/{id}/members")
    public AddMembersResponse addMembers(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody AddMembersRequest request) {
        Long userId = Long.valueOf(jwt.getSubject());
        return classroomService.addMembers(userId, id, request);
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<Void> removeMember(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @PathVariable Long userId) {
        Long callerId = Long.valueOf(jwt.getSubject());
        classroomService.removeMember(callerId, id, userId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/members")
    public PageResponse<ClassroomMemberResponse> listMembers(@AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Long userId = Long.valueOf(jwt.getSubject());
        return classroomService.listMembers(userId, id, page, size, sort);
    }
}
