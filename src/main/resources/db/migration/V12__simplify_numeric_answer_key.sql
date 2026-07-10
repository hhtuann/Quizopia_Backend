-- ============================================================
-- Quizopia Flyway Migration V12
-- Purpose: Simplify NUMERIC_FILL answer_key to {expectedAnswer} only.
--
-- Changes:
--   - requiredInputLength is a constant 4 (frontend input maxLength) — no longer stored.
--   - roundingInstruction moves into the question content (the prompt) — no longer stored.
-- Drops the V7/V8 3-field CHECKs and re-adds a CHECK requiring only expectedAnswer
-- (textual, length 4, numeric regex). Migrates existing rows to the new shape.
-- The presence (answer_key iff NUMERIC_FILL) and object CHECKs are preserved.
--
-- No schema/table/column is added or removed — only the CHECK predicates + jsonb content.
-- ============================================================

-- 1. question_versions: relax the NUMERIC answer_key CHECK to require only expectedAnswer.
ALTER TABLE question_versions DROP CONSTRAINT IF EXISTS chk_question_versions_numeric_answer_key;
ALTER TABLE question_versions ADD CONSTRAINT chk_question_versions_numeric_answer_key
    CHECK (
        question_type <> 'NUMERIC_FILL'
        OR (
            answer_key ? 'expectedAnswer'
            AND jsonb_typeof(answer_key -> 'expectedAnswer') = 'string'
            AND LENGTH(answer_key ->> 'expectedAnswer') = 4
            AND answer_key ->> 'expectedAnswer' ~ '^-?[0-9]+([.][0-9]+)?$'
        )
    );

-- 2. exam_questions: same relaxation on the exam snapshot.
ALTER TABLE exam_questions DROP CONSTRAINT IF EXISTS chk_exam_questions_numeric_answer_key;
ALTER TABLE exam_questions ADD CONSTRAINT chk_exam_questions_numeric_answer_key
    CHECK (
        question_type <> 'NUMERIC_FILL'
        OR (
            answer_key ? 'expectedAnswer'
            AND jsonb_typeof(answer_key -> 'expectedAnswer') = 'string'
            AND LENGTH(answer_key ->> 'expectedAnswer') = 4
            AND answer_key ->> 'expectedAnswer' ~ '^-?[0-9]+([.][0-9]+)?$'
        )
    );

-- 3. Migrate existing NUMERIC answer_keys to {expectedAnswer} only (drop the 2 legacy keys).
UPDATE question_versions
SET answer_key = answer_key - 'requiredInputLength' - 'roundingInstruction'
WHERE question_type = 'NUMERIC_FILL' AND answer_key IS NOT NULL;

UPDATE exam_questions
SET answer_key = answer_key - 'requiredInputLength' - 'roundingInstruction'
WHERE question_type = 'NUMERIC_FILL' AND answer_key IS NOT NULL;
