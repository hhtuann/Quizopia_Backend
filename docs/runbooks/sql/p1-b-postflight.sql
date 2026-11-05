DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM exam_versions v LEFT JOIN legacy_exam_content_map m ON m.legacy_version_id=v.id
               LEFT JOIN exams e ON e.id=m.new_exam_id WHERE m.legacy_version_id IS NULL OR e.id IS NULL
               OR m.legacy_exam_id IS DISTINCT FROM v.exam_id OR m.legacy_version_number IS DISTINCT FROM v.version_number
               OR m.legacy_status IS DISTINCT FROM v.status) THEN
        RAISE EXCEPTION 'P1-B postflight: missing or incorrect version mapping';
    END IF;
    IF EXISTS (SELECT 1 FROM legacy_exam_content_map m JOIN exam_versions v ON v.id=m.legacy_version_id
               WHERE m.is_canonical IS DISTINCT FROM (v.id=COALESCE(
                   (SELECT p.id FROM exam_versions p WHERE p.exam_id=v.exam_id AND p.status='PUBLISHED' ORDER BY p.version_number LIMIT 1),
                   (SELECT d.id FROM exam_versions d WHERE d.exam_id=v.exam_id AND d.status='DRAFT' ORDER BY d.version_number LIMIT 1)))
               OR (m.is_canonical AND m.new_exam_id<>m.legacy_exam_id)) THEN
        RAISE EXCEPTION 'P1-B postflight: incorrect canonical version';
    END IF;
    IF EXISTS (SELECT new_exam_id FROM legacy_exam_content_map GROUP BY new_exam_id HAVING count(*)<>1) THEN
        RAISE EXCEPTION 'P1-B postflight: multiple versions mapped to one exam';
    END IF;
    IF EXISTS (SELECT 1 FROM exam_questions q JOIN exam_sections s ON s.id=q.exam_section_id WHERE q.exam_id<>s.exam_id)
       OR EXISTS (SELECT 1 FROM attempt_questions q JOIN attempts a ON a.id=q.attempt_id
                  JOIN exam_sessions s ON s.id=a.exam_session_id JOIN exam_questions e ON e.id=q.exam_question_id
                  WHERE e.exam_id<>s.exam_id) THEN
        RAISE EXCEPTION 'P1-B postflight: inconsistent question/session lineage';
    END IF;
END $$;
