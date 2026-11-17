-- =====================================================================
-- V41 (P4-D final-gate fixes, issues #91–#94 review):
--
--   1. 30-day media retention now covers FULL/ROLLING recording objects:
--      monitoring_recording_jobs.expires_at set at insert from the frozen
--      session policy (media_retention_days). The hourly retention job
--      deletes expired objects and marks object_deleted=true ONLY after a
--      successful delete (or verified absence) — rows survive as metadata
--      and gap evidence.
--   2. Evidence access audit can reference a recording job (playback URLs
--      for FULL segments): evidence_id becomes nullable, recording_job_id
--      added; exactly one of the two is set (CHECK).
--
-- Forward-only: two ALTERs on P4-D tables (empty or dev-only rows).
-- =====================================================================

-- 1. Recording expiry (mirrors monitoring_evidence.expires_at semantics).
ALTER TABLE monitoring_recording_jobs
    ADD COLUMN expires_at TIMESTAMPTZ;

CREATE INDEX ix_recording_jobs_expiry
    ON monitoring_recording_jobs (expires_at)
    WHERE object_deleted = false AND expires_at IS NOT NULL;

COMMENT ON COLUMN monitoring_recording_jobs.expires_at IS
    'P4-D retention: media expiry from the frozen session policy; the row survives as metadata after object deletion (object_deleted=true).';

-- 2. Audit rows may point at a recording segment instead of an evidence row.
ALTER TABLE monitoring_evidence_access
    ALTER COLUMN evidence_id DROP NOT NULL;

ALTER TABLE monitoring_evidence_access
    ADD COLUMN recording_job_id UUID;

ALTER TABLE monitoring_evidence_access
    ADD CONSTRAINT fk_evidence_access_recording
        FOREIGN KEY (recording_job_id) REFERENCES monitoring_recording_jobs(id) ON DELETE CASCADE;

ALTER TABLE monitoring_evidence_access
    ADD CONSTRAINT chk_evidence_access_target CHECK (
        (evidence_id IS NULL) <> (recording_job_id IS NULL)
    );

CREATE INDEX ix_evidence_access_recording
    ON monitoring_evidence_access (recording_job_id) WHERE recording_job_id IS NOT NULL;

-- Verification: audit rows always reference exactly one target.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM monitoring_evidence_access
               WHERE evidence_id IS NULL AND recording_job_id IS NULL) THEN
        RAISE EXCEPTION 'V41 verification failed: audit rows must reference a target';
    END IF;
END $$;
