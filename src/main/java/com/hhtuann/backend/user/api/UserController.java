package com.hhtuann.backend.user.api;

import com.hhtuann.backend.identity.domain.model.UserStatus;
import com.hhtuann.backend.question.dto.PageResponse;
import com.hhtuann.backend.user.application.UserService;
import com.hhtuann.backend.user.dto.AssignRoleRequest;
import com.hhtuann.backend.user.dto.CreateUserRequest;
import com.hhtuann.backend.user.dto.RoleListResponse;
import com.hhtuann.backend.user.dto.UpdateUserRequest;
import com.hhtuann.backend.user.dto.UserListItem;
import com.hhtuann.backend.user.dto.UserResponse;
import com.hhtuann.backend.user.exception.UserErrorCode;
import com.hhtuann.backend.user.exception.UserException;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-management REST endpoints (SYSTEM_ADMIN only). No class-level
 * {@code @RequestMapping}: the full path lives on the method (consistent with
 * the {@code /api} prefix used across the app — never {@code /api/v1}).
 *
 * <ul>
 *   <li>{@code GET  /api/users} — paginated list (USER_READ).</li>
 *   <li>{@code GET  /api/users/{id}} — detail.</li>
 *   <li>{@code POST /api/users} — create (USER_CREATE).</li>
 *   <li>{@code PUT  /api/users/{id}} — update displayName/email (USER_UPDATE).</li>
 *   <li>{@code POST /api/users/{id}/{activate|disable|lock|unlock}}</li>
 *   <li>{@code POST   /api/users/{id}/roles} — assign role (USER_ROLE_ASSIGN).</li>
 *   <li>{@code DELETE /api/users/{id}/roles/{roleCode}} — revoke role (USER_ROLE_ASSIGN).</li>
 *   <li>{@code GET  /api/roles} — role catalog (ROLE_READ).</li>
 * </ul>
 * Authorization (active SYSTEM_ADMIN role) is enforced in {@link UserService}.
 */
@RestController
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/api/users")
    public PageResponse<UserListItem> listUsers(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role) {
        Long userId = Long.valueOf(jwt.getSubject());
        UserStatus statusEnum = parseStatus(status);
        return userService.listUsers(userId, search, statusEnum, role, page, size);
    }

    @GetMapping("/api/users/{id}")
    public UserResponse getUser(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return userService.getUser(Long.valueOf(jwt.getSubject()), id);
    }

    @PostMapping("/api/users")
    public ResponseEntity<UserResponse> createUser(
            @Valid @RequestBody CreateUserRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UserResponse response = userService.createUser(Long.valueOf(jwt.getSubject()), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/api/users/{id}")
    public UserResponse updateUser(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return userService.updateUser(Long.valueOf(jwt.getSubject()), id, request);
    }

    @PostMapping("/api/users/{id}/activate")
    public UserResponse activate(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return userService.activate(Long.valueOf(jwt.getSubject()), id);
    }

    @PostMapping("/api/users/{id}/disable")
    public UserResponse disable(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return userService.disable(Long.valueOf(jwt.getSubject()), id);
    }

    @PostMapping("/api/users/{id}/lock")
    public UserResponse lock(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return userService.lock(Long.valueOf(jwt.getSubject()), id);
    }

    @PostMapping("/api/users/{id}/unlock")
    public UserResponse unlock(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return userService.unlock(Long.valueOf(jwt.getSubject()), id);
    }

    @PostMapping("/api/users/{id}/roles")
    public UserResponse assignRole(
            @PathVariable Long id,
            @Valid @RequestBody AssignRoleRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return userService.assignRole(Long.valueOf(jwt.getSubject()), id, request);
    }

    @DeleteMapping("/api/users/{id}/roles/{roleCode}")
    public UserResponse removeRole(
            @PathVariable Long id,
            @PathVariable String roleCode,
            @AuthenticationPrincipal Jwt jwt) {
        return userService.removeRole(Long.valueOf(jwt.getSubject()), id, roleCode);
    }

    @GetMapping("/api/roles")
    public RoleListResponse listRoles(@AuthenticationPrincipal Jwt jwt) {
        return userService.listRoles(Long.valueOf(jwt.getSubject()));
    }

    private static UserStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return UserStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new UserException(UserErrorCode.USER_VALIDATION_ERROR, "Invalid status filter");
        }
    }
}
