package com.hhtuann.backend.classroom.api;

import com.hhtuann.backend.classroom.application.ClassroomService;
import com.hhtuann.backend.classroom.dto.AddMembersRequest;
import com.hhtuann.backend.classroom.dto.AddMembersResponse;
import com.hhtuann.backend.classroom.dto.ClassroomDetailView;
import com.hhtuann.backend.classroom.dto.ClassroomMemberResponse;
import com.hhtuann.backend.classroom.dto.ClassroomResponse;
import com.hhtuann.backend.classroom.dto.CreateClassroomRequest;
import com.hhtuann.backend.classroom.dto.MyClassroomsResponse;
import com.hhtuann.backend.classroom.dto.UpdateClassroomRequest;
import com.hhtuann.backend.question.dto.PageResponse;
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

@RestController
@RequestMapping("/api/classrooms")
public class ClassroomController {

    private final ClassroomService classroomService;

    public ClassroomController(ClassroomService classroomService) {
        this.classroomService = classroomService;
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

    @DeleteMapping("/{id}/members/{studentProfileId}")
    public ResponseEntity<Void> removeMember(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @PathVariable Long studentProfileId) {
        Long userId = Long.valueOf(jwt.getSubject());
        classroomService.removeMember(userId, id, studentProfileId);
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
