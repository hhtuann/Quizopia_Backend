-- V12 normalized, per-ID evidence; retain output outside the database.
-- Expected differences are normalized explicitly, never by dropping history rows.
-- Per-version metadata mirrors V14: canonical keeps metadata; splits inherit
-- ownership/provenance, use version title/creator and '-V<number>' code, reset
-- JPA version to 0. Split updated_at is generated at migration time, so only
-- that nondeterministic timestamp is normalized to null (created_at is kept).
WITH version_metadata AS (
 SELECT v.*, e.id AS original_exam_id,
        v.id = COALESCE(
          (SELECT p.id FROM exam_versions p WHERE p.exam_id=e.id AND p.status='PUBLISHED' ORDER BY p.version_number LIMIT 1),
          (SELECT d.id FROM exam_versions d WHERE d.exam_id=e.id AND d.status='DRAFT' ORDER BY d.version_number LIMIT 1)) AS canonical,
        to_jsonb(e)-ARRAY['id','current_version_number'] AS original_metadata
 FROM exam_versions v JOIN exams e ON e.id=v.exam_id
), evidence(kind, id, payload) AS (
 SELECT 'exam_metadata', e.id, to_jsonb(e)-ARRAY['status','current_version_number'] FROM exams e
 UNION ALL SELECT 'exam_version_metadata', v.id,
   original_metadata || jsonb_build_object(
     'code', CASE WHEN canonical THEN original_metadata->>'code' ELSE (original_metadata->>'code') || '-V' || version_number END,
     'title', CASE WHEN canonical THEN original_metadata->>'title' ELSE COALESCE(NULLIF(TRIM(title),''),original_metadata->>'title') END,
     'created_by_user_id', CASE WHEN canonical THEN (original_metadata->>'created_by_user_id')::bigint ELSE COALESCE(created_by,(original_metadata->>'created_by_user_id')::bigint) END,
     'version', CASE WHEN canonical THEN (original_metadata->>'version')::integer ELSE 0 END,
     'updated_at', CASE WHEN canonical THEN original_metadata->'updated_at' ELSE 'null'::jsonb END,
     'instructions', instructions, 'duration_minutes', duration_minutes, 'total_points', total_points,
     'tf_matrix_scoring', tf_matrix_scoring, 'published_at', published_at, 'status', status,
     'source_exam_id', NULL, 'composition_revision', 0)
 FROM version_metadata v
 UNION ALL SELECT 'exam_content', v.id, jsonb_build_object('instructions',v.instructions,'duration',v.duration_minutes,'points',v.total_points,'scoring',v.tf_matrix_scoring,'published',v.published_at,'status',v.status) FROM exam_versions v
 UNION ALL SELECT 'exam_versions_audit', v.id, to_jsonb(v) FROM exam_versions v
 UNION ALL SELECT 'sections', s.id, (to_jsonb(s)-'exam_version_id') || jsonb_build_object('legacy_version_id',s.exam_version_id) FROM exam_sections s
 UNION ALL SELECT 'questions', q.id, (to_jsonb(q)-'exam_version_id') || jsonb_build_object('legacy_version_id',q.exam_version_id) FROM exam_questions q
 UNION ALL SELECT 'options', o.id, to_jsonb(o) FROM exam_question_options o
 UNION ALL SELECT 'sessions', s.id, (to_jsonb(s)-ARRAY['exam_version_id','duration_minutes']) || jsonb_build_object('legacy_version_id',s.exam_version_id,'duration_minutes',COALESCE(s.duration_minutes,v.duration_minutes)) FROM exam_sessions s JOIN exam_versions v ON v.id=s.exam_version_id
 UNION ALL SELECT 'attempts', a.id, (to_jsonb(a)-'exam_version_id') || jsonb_build_object('legacy_version_id',a.exam_version_id) FROM attempts a
 UNION ALL SELECT 'attempt_questions', q.id, to_jsonb(q) FROM attempt_questions q
 UNION ALL SELECT 'answers', a.id, to_jsonb(a) FROM attempt_answers a
 UNION ALL SELECT 'grades', g.id, to_jsonb(g) FROM grades g
 UNION ALL SELECT 'grade_items', g.id, to_jsonb(g) FROM grade_items g
)
SELECT kind, id, md5(payload::text) AS checksum, payload::text FROM evidence ORDER BY kind, id;
