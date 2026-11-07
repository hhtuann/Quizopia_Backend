-- ============================================================
-- Identity M5 (ADR-004 §"Reset persistence, intake and delivery"):
-- password-reset challenges and the synchronous abuse buckets that
-- admit public forgot-password intake.
--
-- V1-V22 are untouched; this migration only ADDS tables.
-- ============================================================

-- ============================================================
-- 1. PASSWORD_RESET_CHALLENGES
-- Separate from OTP verification challenges (no purpose reuse).
-- Only a domain-separated HMAC-SHA-256 digest of the 32-byte
-- random token is stored (64 lowercase hex chars); the raw token
-- never persists anywhere.
-- Bound at issuance to the normalized email, the
-- database-precision emailVerifiedAt and the credential epoch
-- (token_version), so email reassignment, proof invalidation or
-- any credential mutation invalidates the challenge.
-- Expiry is a predicate on expires_at, not a fourth status.
-- Cleanup policy: rows whose expires_at is more than seven days
-- in the past are deleted by PasswordResetCleanupScheduler.
-- ============================================================
CREATE TABLE password_reset_challenges (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,

    email_binding VARCHAR(254) NOT NULL,
    email_verified_at TIMESTAMPTZ NOT NULL,
    credential_epoch INTEGER NOT NULL,

    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    superseded_at TIMESTAMPTZ,

    CONSTRAINT uk_password_reset_challenges_hash
        UNIQUE (token_hash),

    CONSTRAINT fk_password_reset_challenges_user
        FOREIGN KEY (user_id)
        REFERENCES users (id)
        ON DELETE CASCADE,

    CONSTRAINT chk_password_reset_challenges_hash
        CHECK (token_hash ~ '^[0-9a-f]{64}$'),

    CONSTRAINT chk_password_reset_challenges_epoch
        CHECK (credential_epoch >= 0),

    CONSTRAINT chk_password_reset_challenges_status
        CHECK (status IN ('PENDING', 'CONSUMED', 'SUPERSEDED')),

    CONSTRAINT chk_password_reset_challenges_window
        CHECK (expires_at > created_at),

    CONSTRAINT chk_password_reset_challenges_consumed
        CHECK ((status = 'CONSUMED') = (consumed_at IS NOT NULL)
            AND (status <> 'CONSUMED' OR consumed_at >= created_at)),

    CONSTRAINT chk_password_reset_challenges_superseded
        CHECK ((status = 'SUPERSEDED') = (superseded_at IS NOT NULL)
            AND (status <> 'SUPERSEDED' OR superseded_at >= created_at))
);

-- One PENDING challenge per user: replacement terminalizes the
-- predecessor under the user lock before inserting the successor.
CREATE UNIQUE INDEX uq_password_reset_challenges_pending_user
    ON password_reset_challenges (user_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_password_reset_challenges_user_history
    ON password_reset_challenges (user_id, created_at);

CREATE INDEX idx_password_reset_challenges_expires_at
    ON password_reset_challenges (expires_at);

-- ============================================================
-- 2. PASSWORD_RESET_ABUSE_BUCKETS
-- Synchronous, account-independent abuse admission for the public
-- forgot endpoint (fixed order: IP bucket, then email bucket, one
-- short transaction for every syntactically valid request, before
-- any account lookup or SMTP). bucket_key is a domain-separated
-- HMAC-SHA-256 of the email / requester IP (64 lowercase hex) so
-- the table never stores plaintext addresses or IPs.
-- The row is upserted and locked: an elapsed one-hour window is
-- reset (last_admitted_at preserved) and attempts are
-- saturating-incremented even when the request is denied; denied
-- attempts never extend the cooldown. Expired buckets are cleaned
-- up two hours after expiry.
-- ============================================================
CREATE TABLE password_reset_abuse_buckets (
    bucket_key VARCHAR(64) PRIMARY KEY,

    window_start TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL,
    last_admitted_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT chk_password_reset_abuse_buckets_key
        CHECK (bucket_key ~ '^[0-9a-f]{64}$'),

    CONSTRAINT chk_password_reset_abuse_buckets_attempts
        CHECK (attempts BETWEEN 0 AND 2147483647),

    CONSTRAINT chk_password_reset_abuse_buckets_window
        CHECK (expires_at > window_start)
);

CREATE INDEX idx_password_reset_abuse_buckets_expires_at
    ON password_reset_abuse_buckets (expires_at);
