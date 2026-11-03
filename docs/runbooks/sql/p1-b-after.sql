-- V17 counterpart of p1-b-before.sql. Compare every row, ID and checksum.
-- All mapped exams have full per-version metadata evidence, including splits.
-- Only generated split updated_at and generated target id are normalized;
-- target identity/canonical lineage is checked by postflight and mapping tests.
WITH evidence(kind, id, payload) AS (
 SELECT 'exam_metadata', e.id, to_jsonb(e)-ARRAY['status','instructions','duration_minutes','total_points','tf_matrix_scoring','published_at','source_exam_id','composition_revision'] FROM exams e WHERE NOT EXISTS (SELECT 1 FROM legacy_exam_content_map m WHERE m.new_exam_id=e.id AND NOT m.is_canonical)
 UNION ALL SELECT 'exam_version_metadata', m.legacy_version_id,
   (to_jsonb(e)-'id') || CASE WHEN m.is_canonical THEN '{}'::jsonb ELSE '{"updated_at":null}'::jsonb END
 FROM legacy_exam_content_map m JOIN exams e ON e.id=m.new_exam_id
 UNION ALL SELECT 'exam_content', m.legacy_version_id, jsonb_build_object('instructions',e.instructions,'duration',e.duration_minutes,'points',e.total_points,'scoring',e.tf_matrix_scoring,'published',e.published_at,'status',e.status) FROM legacy_exam_content_map m JOIN exams e ON e.id=m.new_exam_id
 UNION ALL SELECT 'exam_versions_audit', v.id, to_jsonb(v) FROM exam_versions v
 UNION ALL SELECT 'sections', s.id, (to_jsonb(s)-'exam_id') || jsonb_build_object('legacy_version_id',m.legacy_version_id) FROM exam_sections s JOIN legacy_exam_content_map m ON m.new_exam_id=s.exam_id
 UNION ALL SELECT 'questions', q.id, (to_jsonb(q)-'exam_id') || jsonb_build_object('legacy_version_id',m.legacy_version_id) FROM exam_questions q JOIN legacy_exam_content_map m ON m.new_exam_id=q.exam_id
 UNION ALL SELECT 'options', o.id, to_jsonb(o) FROM exam_question_options o
 UNION ALL SELECT 'sessions', s.id, (to_jsonb(s)-'exam_id') || jsonb_build_object('legacy_version_id',m.legacy_version_id) FROM exam_sessions s JOIN legacy_exam_content_map m ON m.new_exam_id=s.exam_id
 UNION ALL SELECT 'attempts', a.id, to_jsonb(a) || jsonb_build_object('legacy_version_id',m.legacy_version_id) FROM attempts a JOIN exam_sessions s ON s.id=a.exam_session_id JOIN legacy_exam_content_map m ON m.new_exam_id=s.exam_id
 UNION ALL SELECT 'attempt_questions', q.id, to_jsonb(q) FROM attempt_questions q
 UNION ALL SELECT 'answers', a.id, to_jsonb(a) FROM attempt_answers a
 UNION ALL SELECT 'grades', g.id, to_jsonb(g) FROM grades g
 UNION ALL SELECT 'grade_items', g.id, to_jsonb(g) FROM grade_items g
)
SELECT kind, id, md5(payload::text) AS checksum, payload::text FROM evidence ORDER BY kind, id;
