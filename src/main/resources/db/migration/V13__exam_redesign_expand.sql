-- =====================================================================
-- V13 (Task B / Issue #2): Exam redesign — EXPAND phase.
--
-- One Exam = one immutable published content set (ADR-001 §2.4).
-- Additive only: new exam-level content columns, exam_id columns on
-- sections/questions/sessions, and the immutable legacy mapping table.
-- No NOT NULLs, no FK drops — cutover happens in V14 after backfill.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Exam-level content columns (moved from exam_versions in V14).
-- ---------------------------------------------------------------------
ALTER TABLE exams
    ADD COLUMN instructions      TEXT,
    ADD COLUMN duration_minutes  INTEGER,
    ADD COLUMN total_points      NUMERIC(10,2) NOT NULL DEFAULT 0,
    ADD COLUMN tf_matrix_scoring JSONB,
    ADD COLUMN published_at      TIMESTAMPTZ,
    ADD COLUMN source_exam_id    BIGINT,
    ADD COLUMN composition_revision INTEGER NOT NULL DEFAULT 0;

ALTER TABLE exams
    ADD CONSTRAINT fk_exams_source_exam FOREIGN KEY (source_exam_id)
        REFERENCES exams (id) ON DELETE SET NULL;

-- Transitional status superset: legacy DRAFT/READY + target
-- PUBLISHED/ARCHIVED (READY rows are mapped to PUBLISHED in V14; the
-- final constraint keeps only the target values).
ALTER TABLE exams DROP CONSTRAINT chk_exams_status;
ALTER TABLE exams ADD CONSTRAINT chk_exams_status
    CHECK (status IN ('DRAFT','READY','PUBLISHED','ARCHIVED'));

-- total_points invariant (mirrors legacy exam_versions rule; DRAFT = 0).
ALTER TABLE exams ADD CONSTRAINT chk_exams_published_points CHECK (
    (status IN ('DRAFT','READY')) OR (total_points > 0 AND published_at IS NOT NULL)
);

-- ---------------------------------------------------------------------
-- 2. Direct exam_id columns on sections/questions/sessions (nullable
--    until the V14 backfill).
-- ---------------------------------------------------------------------
ALTER TABLE exam_sections ADD COLUMN exam_id BIGINT;
ALTER TABLE exam_questions ADD COLUMN exam_id BIGINT;
ALTER TABLE exam_sessions ADD COLUMN exam_id BIGINT;

-- Personal-workspace exams have no school/teacher provenance.
ALTER TABLE exam_sessions ALTER COLUMN school_id        DROP NOT NULL;
ALTER TABLE exam_sessions ALTER COLUMN owner_teacher_id DROP NOT NULL;

-- ---------------------------------------------------------------------
-- 3. Immutable legacy mapping (auditable migration provenance).
-- ---------------------------------------------------------------------
CREATE TABLE legacy_exam_content_map (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    legacy_exam_id        BIGINT      NOT NULL,
    legacy_version_id     BIGINT      NOT NULL,
    legacy_version_number INTEGER     NOT NULL,
    legacy_status         VARCHAR(20) NOT NULL,
    new_exam_id           BIGINT      NOT NULL,
    is_canonical          BOOLEAN     NOT NULL,
    migrated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_legacy_exam_content_map_version UNIQUE (legacy_version_id),
    CONSTRAINT chk_legacy_exam_content_map_status CHECK (legacy_status IN ('DRAFT','PUBLISHED')),
    CONSTRAINT fk_lecm_new_exam FOREIGN KEY (new_exam_id) REFERENCES exams (id) ON DELETE RESTRICT
);

COMMENT ON TABLE legacy_exam_content_map IS
    'Immutable audit of the legacy exam-version → exam migration (ADR-001 §2.4). One row per legacy exam_versions row; is_canonical marks the version that kept the original exam id.';
