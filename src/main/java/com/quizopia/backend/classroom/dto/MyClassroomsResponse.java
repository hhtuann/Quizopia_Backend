package com.quizopia.backend.classroom.dto;

import java.util.List;

/** GET /api/classrooms/my response shape: { items: [...] }. */
public record MyClassroomsResponse(List<ClassroomResponse> items) {
}
