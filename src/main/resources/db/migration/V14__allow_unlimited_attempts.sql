-- V14: Allow max_attempts = 0 (unlimited attempts).
-- Was: CHECK (max_attempts > 0) — rejected 0.
-- Now: CHECK (max_attempts >= 0) — 0 means unlimited.

ALTER TABLE exam_sessions DROP CONSTRAINT IF EXISTS chk_exam_sessions_max_attempts;
ALTER TABLE exam_sessions ADD CONSTRAINT chk_exam_sessions_max_attempts CHECK (max_attempts >= 0);
