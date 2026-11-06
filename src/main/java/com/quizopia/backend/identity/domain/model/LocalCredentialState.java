package com.quizopia.backend.identity.domain.model;

/** Provenance of the local password, independent of external identity links. */
public enum LocalCredentialState {
    UNKNOWN, ENABLED, DISABLED
}
