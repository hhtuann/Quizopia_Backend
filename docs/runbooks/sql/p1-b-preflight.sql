-- Run at V12 with all writers stopped, in a read-only transaction.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM exam_sessions s LEFT JOIN exam_versions v ON v.id=s.exam_version_id
               WHERE v.id IS NULL OR v.status <> 'PUBLISHED') THEN
        RAISE EXCEPTION 'P1-B preflight: orphan session or session on non-published version';
    END IF;
    IF EXISTS (SELECT 1 FROM attempts a JOIN exam_sessions s ON s.id=a.exam_session_id
               WHERE a.exam_version_id IS DISTINCT FROM s.exam_version_id) THEN
        RAISE EXCEPTION 'P1-B preflight: attempt/session version lineage mismatch';
    END IF;
    IF EXISTS (SELECT 1 FROM attempt_questions a JOIN attempts t ON t.id=a.attempt_id
               JOIN exam_questions q ON q.id=a.exam_question_id
               WHERE q.exam_version_id IS DISTINCT FROM t.exam_version_id) THEN
        RAISE EXCEPTION 'P1-B preflight: attempt question/version lineage mismatch';
    END IF;
    IF EXISTS (SELECT 1 FROM exam_sections s LEFT JOIN exam_versions v ON v.id=s.exam_version_id WHERE v.id IS NULL)
       OR EXISTS (SELECT 1 FROM exam_questions q LEFT JOIN exam_versions v ON v.id=q.exam_version_id WHERE v.id IS NULL)
       OR EXISTS (SELECT 1 FROM attempts a LEFT JOIN exam_versions v ON v.id=a.exam_version_id WHERE v.id IS NULL)
       OR EXISTS (SELECT 1 FROM exam_versions v LEFT JOIN exams e ON e.id=v.exam_id WHERE e.id IS NULL) THEN
        RAISE EXCEPTION 'P1-B preflight: orphan content/version/attempt';
    END IF;
END $$;
