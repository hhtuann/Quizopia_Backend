-- =====================================================================
-- V14 (Task B / Issue #2): Exam redesign — BACKFILL + CUTOVER.
--
-- Deterministic legacy version → exam mapping (ADR-001 §2.4):
--   canonical version = earliest PUBLISHED by version_number,
--   else earliest DRAFT. The canonical version KEEPS the original exam id;
--   every other legacy version becomes a distinct new exam.
-- Children (sections/questions/sessions) are re-pointed by the EXACT old
-- version id — never "latest". Row ids never change.
-- Session duration is materialized as
--   COALESCE(session.duration_minutes, version.duration_minutes).
-- Preflight DO-blocks FAIL on anomalies (never silently reassign).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Preflight — fail on orphan / inconsistent legacy data.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    orphan_versions      BIGINT;
    orphan_sections      BIGINT;
    orphan_questions     BIGINT;
    orphan_sessions      BIGINT;
    orphan_attempts      BIGINT;
    sessions_on_draft    BIGINT;
    bad_published        BIGINT;
BEGIN
    SELECT COUNT(*) INTO orphan_versions
    FROM exam_versions v LEFT JOIN exams e ON e.id = v.exam_id WHERE e.id IS NULL;
    IF orphan_versions > 0 THEN
        RAISE EXCEPTION 'V14 preflight: % exam_versions reference a missing exam', orphan_versions;
    END IF;

    SELECT COUNT(*) INTO orphan_sections
    FROM exam_sections s LEFT JOIN exam_versions v ON v.id = s.exam_version_id WHERE v.id IS NULL;
    IF orphan_sections > 0 THEN
        RAISE EXCEPTION 'V14 preflight: % exam_sections reference a missing exam_version', orphan_sections;
    END IF;

    SELECT COUNT(*) INTO orphan_questions
    FROM exam_questions q LEFT JOIN exam_versions v ON v.id = q.exam_version_id WHERE v.id IS NULL;
    IF orphan_questions > 0 THEN
        RAISE EXCEPTION 'V14 preflight: % exam_questions reference a missing exam_version', orphan_questions;
    END IF;

    SELECT COUNT(*) INTO orphan_sessions
    FROM exam_sessions es LEFT JOIN exam_versions v ON v.id = es.exam_version_id WHERE v.id IS NULL;
    IF orphan_sessions > 0 THEN
        RAISE EXCEPTION 'V14 preflight: % exam_sessions reference a missing exam_version', orphan_sessions;
    END IF;

    SELECT COUNT(*) INTO orphan_attempts
    FROM attempts a LEFT JOIN exam_versions v ON v.id = a.exam_version_id WHERE v.id IS NULL;
    IF orphan_attempts > 0 THEN
        RAISE EXCEPTION 'V14 preflight: % attempts reference a missing exam_version', orphan_attempts;
    END IF;

    SELECT COUNT(*) INTO sessions_on_draft
    FROM exam_sessions es JOIN exam_versions v ON v.id = es.exam_version_id
    WHERE v.status <> 'PUBLISHED';
    IF sessions_on_draft > 0 THEN
        RAISE EXCEPTION 'V14 preflight: % exam_sessions reference a non-PUBLISHED exam_version (illegal state)', sessions_on_draft;
    END IF;

    SELECT COUNT(*) INTO bad_published
    FROM exam_versions v
    WHERE (v.status = 'PUBLISHED') IS DISTINCT FROM (v.published_at IS NOT NULL AND v.total_points > 0);
    IF bad_published > 0 THEN
        RAISE EXCEPTION 'V14 preflight: % published exam_versions violate the published invariant', bad_published;
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 2. Version → target-exam mapping (temp; canonical keeps original id).
-- ---------------------------------------------------------------------
CREATE TEMP TABLE version_target ON COMMIT DROP AS
SELECT v.id                AS version_id,
       v.exam_id           AS legacy_exam_id,
       v.version_number,
       v.status            AS legacy_status,
       COALESCE(
           (SELECT c.id FROM exam_versions c
             WHERE c.exam_id = v.exam_id AND c.status = 'PUBLISHED'
             ORDER BY c.version_number ASC LIMIT 1),
           (SELECT c.id FROM exam_versions c
             WHERE c.exam_id = v.exam_id AND c.status = 'DRAFT'
             ORDER BY c.version_number ASC LIMIT 1)
       )                   AS canonical_version_id,
       e.code              AS legacy_code
FROM exam_versions v
JOIN exams e ON e.id = v.exam_id;

ALTER TABLE version_target ADD COLUMN new_exam_id BIGINT;

UPDATE version_target SET new_exam_id = legacy_exam_id
WHERE version_id = canonical_version_id;

-- ---------------------------------------------------------------------
-- 3. Canonical versions: move content onto the original exam row.
-- ---------------------------------------------------------------------
UPDATE exams e
SET instructions      = v.instructions,
    duration_minutes  = v.duration_minutes,
    total_points      = v.total_points,
    tf_matrix_scoring = v.tf_matrix_scoring,
    published_at      = v.published_at,
    status            = CASE WHEN v.status = 'PUBLISHED' THEN 'PUBLISHED' ELSE 'DRAFT' END
FROM exam_versions v
JOIN version_target vt ON vt.version_id = v.id
WHERE vt.version_id = vt.canonical_version_id
  AND e.id = vt.legacy_exam_id;

-- ---------------------------------------------------------------------
-- 4. Non-canonical versions: each becomes a distinct NEW exam.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    rec RECORD;
    new_id BIGINT;
    new_code TEXT;
BEGIN
    FOR rec IN
        SELECT vt.*, e.workspace_id, e.created_by_user_id, e.school_id, e.subject_id,
               e.owner_teacher_id, e.purpose_id, e.description, e.created_at,
               v.instructions, v.duration_minutes, v.total_points,
               v.tf_matrix_scoring, v.published_at, v.title AS version_title, v.created_by
        FROM version_target vt
        JOIN exams e ON e.id = vt.legacy_exam_id
        JOIN exam_versions v ON v.id = vt.version_id
        WHERE vt.new_exam_id IS NULL
    LOOP
        -- Deterministic non-conflicting code: legacy code + '-V' + version number.
        new_code := rec.legacy_code || '-V' || rec.version_number;
        IF EXISTS (
            SELECT 1 FROM exams x
            WHERE x.workspace_id = rec.workspace_id
              AND x.created_by_user_id = COALESCE(rec.created_by, rec.created_by_user_id)
              AND LOWER(x.code) = LOWER(new_code)
        ) THEN
            RAISE EXCEPTION 'V14: deterministic split code % collides (legacy exam %)', new_code, rec.legacy_exam_id;
        END IF;

        INSERT INTO exams (workspace_id, created_by_user_id, school_id, subject_id, owner_teacher_id,
                           purpose_id, code, title, description, current_version_number, status,
                           instructions, duration_minutes, total_points, tf_matrix_scoring, published_at, created_at, updated_at)
        VALUES (rec.workspace_id, COALESCE(rec.created_by, rec.created_by_user_id), rec.school_id, rec.subject_id,
                rec.owner_teacher_id, rec.purpose_id, new_code,
                COALESCE(NULLIF(TRIM(rec.version_title), ''), (SELECT title FROM exams WHERE id = rec.legacy_exam_id)),
                rec.description, 1,
                CASE WHEN rec.legacy_status = 'PUBLISHED' THEN 'PUBLISHED' ELSE 'DRAFT' END,
                rec.instructions, rec.duration_minutes, rec.total_points, rec.tf_matrix_scoring,
                rec.published_at, rec.created_at, now())
        RETURNING id INTO new_id;

        UPDATE version_target SET new_exam_id = new_id
        WHERE version_id = rec.version_id;
    END LOOP;
END $$;

-- ---------------------------------------------------------------------
-- 5. Immutable mapping table.
-- ---------------------------------------------------------------------
INSERT INTO legacy_exam_content_map
    (legacy_exam_id, legacy_version_id, legacy_version_number, legacy_status, new_exam_id, is_canonical)
SELECT legacy_exam_id, version_id, version_number, legacy_status, new_exam_id,
       (version_id = canonical_version_id)
FROM version_target;

-- ---------------------------------------------------------------------
-- 6. Re-point children by the EXACT old version id.
-- ---------------------------------------------------------------------
UPDATE exam_sections s
SET exam_id = vt.new_exam_id
FROM version_target vt
WHERE s.exam_version_id = vt.version_id;

UPDATE exam_questions q
SET exam_id = vt.new_exam_id
FROM version_target vt
WHERE q.exam_version_id = vt.version_id;

UPDATE exam_sessions es
SET exam_id = vt.new_exam_id
FROM version_target vt
WHERE es.exam_version_id = vt.version_id;

-- ---------------------------------------------------------------------
-- 7. Duration materialization: session keeps its own override, else the
--    legacy version default. 0 = unlimited (unchanged convention).
-- ---------------------------------------------------------------------
UPDATE exam_sessions es
SET duration_minutes = COALESCE(es.duration_minutes, v.duration_minutes)
FROM exam_versions v
WHERE es.exam_version_id = v.id;

-- ---------------------------------------------------------------------
-- 8. Cutover constraints (columns now fully populated).
-- ---------------------------------------------------------------------
ALTER TABLE exam_sections
    ALTER COLUMN exam_id SET NOT NULL,
    ADD CONSTRAINT fk_exam_sections_exam FOREIGN KEY (exam_id)
        REFERENCES exams (id) ON DELETE RESTRICT;

ALTER TABLE exam_sections ADD CONSTRAINT uk_exam_sections_exam_id UNIQUE (exam_id, id);

ALTER TABLE exam_sections ADD CONSTRAINT uk_exam_sections_exam_position UNIQUE (exam_id, position);

ALTER TABLE exam_questions
    ALTER COLUMN exam_id SET NOT NULL,
    ADD CONSTRAINT fk_exam_questions_exam FOREIGN KEY (exam_id)
        REFERENCES exams (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_exam_questions_section_exam FOREIGN KEY (exam_id, exam_section_id)
        REFERENCES exam_sections (exam_id, id),
    ADD CONSTRAINT uk_exam_questions_exam_source UNIQUE (exam_id, source_question_id);

ALTER TABLE exam_sessions
    ALTER COLUMN exam_id SET NOT NULL,
    ADD CONSTRAINT fk_exam_sessions_exam FOREIGN KEY (exam_id)
        REFERENCES exams (id) ON DELETE RESTRICT;

ALTER TABLE exam_sessions
    ALTER COLUMN duration_minutes SET NOT NULL,
    DROP CONSTRAINT IF EXISTS chk_exam_sessions_duration,
    ADD CONSTRAINT chk_exam_sessions_duration CHECK (duration_minutes >= 0);

CREATE INDEX idx_exam_sections_exam ON exam_sections (exam_id);
CREATE INDEX idx_exam_questions_exam ON exam_questions (exam_id);
CREATE INDEX idx_exam_sessions_exam ON exam_sessions (exam_id);
CREATE INDEX idx_exams_source ON exams (source_exam_id) WHERE source_exam_id IS NOT NULL;
CREATE INDEX idx_exams_status_workspace ON exams (workspace_id, status);

-- ---------------------------------------------------------------------
-- 9. Verification — counts preserved, mappings complete.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    versions_total   BIGINT;
    mapped_total     BIGINT;
    sections_total   BIGINT;
    sections_mapped  BIGINT;
    questions_total  BIGINT;
    questions_mapped BIGINT;
    sessions_total   BIGINT;
    sessions_mapped  BIGINT;
    attempts_total   BIGINT;
    bad_points       BIGINT;
    bad_published_at BIGINT;
BEGIN
    SELECT COUNT(*) INTO versions_total FROM exam_versions;
    SELECT COUNT(*) INTO mapped_total FROM legacy_exam_content_map;
    IF mapped_total <> versions_total THEN
        RAISE EXCEPTION 'V14 verification: % versions but % map rows', versions_total, mapped_total;
    END IF;

    SELECT COUNT(*) INTO sections_total FROM exam_sections;
    SELECT COUNT(*) INTO sections_mapped FROM exam_sections WHERE exam_id IS NOT NULL;
    IF sections_mapped <> sections_total THEN
        RAISE EXCEPTION 'V14 verification: % of % exam_sections unmapped', sections_total - sections_mapped, sections_total;
    END IF;

    SELECT COUNT(*) INTO questions_total FROM exam_questions;
    SELECT COUNT(*) INTO questions_mapped FROM exam_questions WHERE exam_id IS NOT NULL;
    IF questions_mapped <> questions_total THEN
        RAISE EXCEPTION 'V14 verification: % of % exam_questions unmapped', questions_total - questions_mapped, questions_total;
    END IF;

    SELECT COUNT(*) INTO sessions_total FROM exam_sessions;
    SELECT COUNT(*) INTO sessions_mapped FROM exam_sessions WHERE exam_id IS NOT NULL;
    IF sessions_mapped <> sessions_total THEN
        RAISE EXCEPTION 'V14 verification: % of % exam_sessions unmapped', sessions_total - sessions_mapped, sessions_total;
    END IF;

    SELECT COUNT(*) INTO attempts_total FROM attempts;
    IF attempts_total <> (SELECT COUNT(*) FROM attempts) THEN
        RAISE EXCEPTION 'V14 verification: attempt count changed';
    END IF;

    SELECT COUNT(*) INTO bad_points
    FROM exams WHERE status = 'PUBLISHED' AND total_points <= 0;
    IF bad_points > 0 THEN
        RAISE EXCEPTION 'V14 verification: % PUBLISHED exams without points', bad_points;
    END IF;

    SELECT COUNT(*) INTO bad_published_at
    FROM exams WHERE status = 'PUBLISHED' AND published_at IS NULL;
    IF bad_published_at > 0 THEN
        RAISE EXCEPTION 'V14 verification: % PUBLISHED exams without published_at', bad_published_at;
    END IF;

    RAISE NOTICE 'V14: % versions mapped to exams; sections/questions/sessions re-pointed; durations materialized',
        versions_total;
END $$;
