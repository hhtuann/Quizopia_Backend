package com.quizopia.backend.exam.dto;

import java.util.List;

public record AddParticipantsResponse(
        int added,
        List<Long> duplicated,
        List<Long> invalid
) {
}
