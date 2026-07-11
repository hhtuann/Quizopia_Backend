-- V15: Add optional session-level duration_minutes override.
-- NULL = inherit from the exam version's duration_minutes.
-- 0 = unlimited (no time limit per attempt).
-- Positive = exact minutes per attempt (overrides exam version).

ALTER TABLE exam_sessions ADD COLUMN duration_minutes INTEGER;
