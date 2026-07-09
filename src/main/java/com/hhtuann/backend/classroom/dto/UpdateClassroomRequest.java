package com.hhtuann.backend.classroom.dto;

import jakarta.validation.constraints.Size;

public record UpdateClassroomRequest(
        @Size(max = 100) String name,
        @Size(max = 500) String description
) {
}
