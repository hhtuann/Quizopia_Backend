package com.quizopia.backend.account.dto;

import com.quizopia.backend.identity.domain.model.LocalCredentialState;
import java.time.Instant;

public record AccountSecurityResponse(LocalCredentialState localCredentialState,
        boolean canChangePassword, boolean googleLinked, Instant passwordChangedAt) { }
