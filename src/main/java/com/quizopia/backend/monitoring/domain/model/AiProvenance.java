package com.quizopia.backend.monitoring.domain.model;

/**
 * Provenance of a stored AI detection / aggregated alert (P4-C, CONTRACT
 * §2/§4, V38 CHECK).
 *
 * <p>{@code CLIENT_MODEL} — inference ran in the student's browser
 * (ORT-Web against the shared model artifact); an <b>observation</b>,
 * never a verified fact (ADR-008 D8). {@code WORKER_VERIFIED} — the
 * internal worker path (server-side inference); still an observation of
 * the model, but attributed to server infrastructure rather than the
 * client.
 *
 * <p>The value is pinned server-side by the ingest path that receives the
 * batch: the student endpoint always stores CLIENT_MODEL, the internal
 * worker endpoint always stores WORKER_VERIFIED — a client can never
 * choose its own provenance.
 */
public enum AiProvenance {
    CLIENT_MODEL,
    WORKER_VERIFIED
}
