package com.hhtuann.backend.exam.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record AddParticipantsRequest(
        @NotNull @NotEmpty List<@Positive Long> studentProfileIds
) {
}
