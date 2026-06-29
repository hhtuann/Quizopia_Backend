-- ============================================================
-- Quizopia Flyway Migration V4
-- Purpose: Enforce that a username never contains '@'.
--
-- Login identifier convention:
--   - contains '@' -> treated as email
--   - no '@'        -> treated as username
-- Keeping '@' out of username makes the two identifiers
-- unambiguous at login time.
--
-- This constraint validates existing rows immediately; it does
-- not use NOT VALID.
--
-- Do not modify this file after it has been applied.
-- ============================================================

ALTER TABLE users
    ADD CONSTRAINT chk_users_username_no_at_sign
        CHECK (POSITION('@' IN username) = 0);
