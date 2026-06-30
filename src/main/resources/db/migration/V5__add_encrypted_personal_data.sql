-- ============================================================
-- Quizopia Flyway Migration V5
-- Purpose: Add columns for sensitive personal data (phone and
--          national identifier) stored ONLY as AES-256-GCM
--          ciphertext.
--
-- Design rules:
--   - Columns are nullable; existing rows keep NULL.
--   - No plaintext phone / national_id column is introduced.
--   - No index is created on ciphertext (it is not searchable).
--   - No default ciphertext value.
--   - A CHECK constraint enforces the ciphertext version prefix
--     ("v1:") so only correctly versioned ciphertext may be
--     persisted; NULL is allowed.
--   - Existing data and migrations V1-V4 are not modified.
--
-- Do not modify this file after it has been applied.
-- ============================================================

ALTER TABLE users
    ADD COLUMN phone_encrypted TEXT,
    ADD COLUMN national_id_encrypted TEXT,
    ADD CONSTRAINT chk_users_phone_encrypted_format
        CHECK (phone_encrypted IS NULL OR phone_encrypted LIKE 'v1:%'),
    ADD CONSTRAINT chk_users_national_id_encrypted_format
        CHECK (national_id_encrypted IS NULL OR national_id_encrypted LIKE 'v1:%');
