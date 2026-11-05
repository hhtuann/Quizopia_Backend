-- =====================================================================
-- V10 (Task A / Issue #1): Workspace / Organization / Membership foundation.
--
-- Public platform foundation per ADR-001:
--   * organizations      — institutional tenant, 1:1 provenance from legacy schools
--   * workspaces         — PERSONAL (exactly one per user) | ORGANIZATION (1:1 per org in Phase 1)
--   * workspace_memberships — workspace-scoped role + lifecycle per user
--
-- Backfill (deterministic, same transaction):
--   1. one organization per school (legacy_school_id provenance)
--   2. one organization workspace per organization
--   3. one personal workspace + OWNER ACTIVE membership per user
--   4. org memberships from legacy profiles:
--        active ACADEMIC_ADMIN w/ profile in school -> ADMIN
--        else teacher profile                        -> EDITOR
--        else student profile                        -> MEMBER
--
-- Verification DO-blocks FAIL the migration on any inconsistency
-- (fail preflight; never silently drop or reassign).
-- Legacy school/profile tables are NOT modified.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. organizations
-- ---------------------------------------------------------------------
CREATE TABLE organizations (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    legacy_school_id  BIGINT UNIQUE,
    code              VARCHAR(50)  NOT NULL,
    name              VARCHAR(255) NOT NULL,
    timezone          VARCHAR(64)  NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_organizations_status CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED')),
    CONSTRAINT chk_organizations_code_not_blank CHECK (LENGTH(TRIM(code)) > 0),
    CONSTRAINT chk_organizations_name_not_blank CHECK (LENGTH(TRIM(name)) > 0),
    CONSTRAINT fk_organizations_legacy_school FOREIGN KEY (legacy_school_id)
        REFERENCES schools (id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX uk_organizations_code_ci ON organizations (LOWER(code));

COMMENT ON TABLE organizations IS
    'Institutional tenant. legacy_school_id is 1:1 provenance from the school era (ADR-001 §2.1); new organizations have NULL.';

INSERT INTO organizations (legacy_school_id, code, name, timezone, status)
SELECT s.id, s.code, s.name, s.timezone, s.status
FROM schools s
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- 2. workspaces
-- ---------------------------------------------------------------------
CREATE TABLE workspaces (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_type    VARCHAR(20)  NOT NULL,
    name              VARCHAR(255) NOT NULL,
    owner_user_id     BIGINT,
    organization_id   BIGINT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_workspaces_type CHECK (workspace_type IN ('PERSONAL','ORGANIZATION')),
    CONSTRAINT chk_workspaces_shape CHECK (
        (workspace_type = 'PERSONAL'   AND owner_user_id IS NOT NULL AND organization_id IS NULL) OR
        (workspace_type = 'ORGANIZATION' AND owner_user_id IS NULL AND organization_id IS NOT NULL)
    ),
    CONSTRAINT chk_workspaces_name_not_blank CHECK (LENGTH(TRIM(name)) > 0),
    CONSTRAINT fk_workspaces_owner_user FOREIGN KEY (owner_user_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_workspaces_organization FOREIGN KEY (organization_id)
        REFERENCES organizations (id) ON DELETE RESTRICT
);

-- Exactly one PERSONAL workspace per user (also the concurrency backstop for
-- registration-time provisioning).
CREATE UNIQUE INDEX uk_workspaces_personal_owner
    ON workspaces (owner_user_id) WHERE workspace_type = 'PERSONAL';

-- One ORGANIZATION workspace per organization (Phase 1; may relax later).
CREATE UNIQUE INDEX uk_workspaces_organization
    ON workspaces (organization_id) WHERE workspace_type = 'ORGANIZATION';

COMMENT ON TABLE workspaces IS
    'User/workspace-centric container (ADR-001 §2.1). PERSONAL: 1:1 with owner user. ORGANIZATION: 1:1 with organization in Phase 1.';

-- Organization workspaces from organizations.
INSERT INTO workspaces (workspace_type, name, organization_id)
SELECT 'ORGANIZATION', o.name, o.id
FROM organizations o
ON CONFLICT DO NOTHING;

-- Personal workspaces for every user (display name of the owner as label).
INSERT INTO workspaces (workspace_type, name, owner_user_id)
SELECT 'PERSONAL', COALESCE(NULLIF(u.display_name, ''), u.username), u.id
FROM users u
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- 3. workspace_memberships
-- ---------------------------------------------------------------------
CREATE TABLE workspace_memberships (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_id  BIGINT      NOT NULL,
    user_id       BIGINT      NOT NULL,
    role          VARCHAR(20) NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_workspace_memberships_role CHECK (role IN ('OWNER','ADMIN','EDITOR','MEMBER')),
    CONSTRAINT chk_workspace_memberships_status CHECK (status IN ('ACTIVE','INVITED','SUSPENDED','REMOVED')),
    CONSTRAINT uk_workspace_memberships_workspace_user UNIQUE (workspace_id, user_id),
    CONSTRAINT fk_workspace_memberships_workspace FOREIGN KEY (workspace_id)
        REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_workspace_memberships_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_workspace_memberships_user ON workspace_memberships (user_id);

COMMENT ON TABLE workspace_memberships IS
    'Workspace-scoped membership and administrative access (ADR-001 §2.1). Distinct from classroom membership.';

-- OWNER of one's personal workspace.
INSERT INTO workspace_memberships (workspace_id, user_id, role, status)
SELECT w.id, w.owner_user_id, 'OWNER', 'ACTIVE'
FROM workspaces w
WHERE w.workspace_type = 'PERSONAL'
ON CONFLICT DO NOTHING;

-- Organization memberships from legacy profiles.
INSERT INTO workspace_memberships (workspace_id, user_id, role, status)
SELECT w.id,
       p.user_id,
       CASE
           WHEN EXISTS (
               SELECT 1
               FROM user_roles ur
               JOIN roles r ON r.id = ur.role_id
               WHERE ur.user_id = p.user_id
                 AND r.code = 'ACADEMIC_ADMIN'
                 AND (ur.expires_at IS NULL OR ur.expires_at > now())
           ) THEN 'ADMIN'
           WHEN EXISTS (
               SELECT 1 FROM teacher_profiles tp WHERE tp.user_id = p.user_id
           ) THEN 'EDITOR'
           ELSE 'MEMBER'
       END,
       'ACTIVE'
FROM workspaces w
JOIN organizations o ON o.id = w.organization_id
JOIN (
    SELECT user_id, school_id FROM teacher_profiles
    UNION
    SELECT user_id, school_id FROM student_profiles
) p ON p.school_id = o.legacy_school_id
WHERE w.workspace_type = 'ORGANIZATION'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- 5. Transitional resolver functions (ADR-001 §2.2 transitional defaults).
--    Used ONLY as column DEFAULTS for legacy-style INSERTs that omit the
--    new workspace/user ownership columns (raw fixtures, transitional
--    writers). JPA always writes the columns explicitly and never triggers
--    them. The org-workspace resolver lazily provisions the organization +
--    workspace pair for post-migration legacy schools, mirroring the V10
--    backfill. Removed in the Task B contract phase.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION quizopia_org_workspace_for_school(sid BIGINT) RETURNS BIGINT
LANGUAGE plpgsql VOLATILE AS $$
DECLARE
    ws_id BIGINT;
    org_id BIGINT;
    s RECORD;
BEGIN
    IF sid IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT w.id INTO ws_id
    FROM workspaces w
    JOIN organizations o ON o.id = w.organization_id
    WHERE w.workspace_type = 'ORGANIZATION'
      AND o.legacy_school_id = sid;
    IF ws_id IS NOT NULL THEN
        RETURN ws_id;
    END IF;
    SELECT code, name, timezone, status INTO s FROM schools WHERE id = sid;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    INSERT INTO organizations (legacy_school_id, code, name, timezone, status)
    VALUES (sid, s.code, s.name, s.timezone, s.status)
    ON CONFLICT (legacy_school_id) DO UPDATE
        SET legacy_school_id = EXCLUDED.legacy_school_id
    RETURNING id INTO org_id;
    IF org_id IS NULL THEN
        SELECT id INTO org_id FROM organizations WHERE legacy_school_id = sid;
    END IF;
    INSERT INTO workspaces (workspace_type, name, organization_id)
    VALUES ('ORGANIZATION', s.name, org_id)
    ON CONFLICT (organization_id) WHERE workspace_type = 'ORGANIZATION' DO NOTHING;
    SELECT w.id INTO ws_id
    FROM workspaces w
    WHERE w.workspace_type = 'ORGANIZATION'
      AND w.organization_id = org_id;
    RETURN ws_id;
END $$;

CREATE OR REPLACE FUNCTION quizopia_user_for_teacher(tpid BIGINT) RETURNS BIGINT
LANGUAGE sql STABLE AS $$
    SELECT user_id FROM teacher_profiles WHERE id = tpid
$$;

CREATE OR REPLACE FUNCTION quizopia_user_for_student(spid BIGINT) RETURNS BIGINT
LANGUAGE sql STABLE AS $$
    SELECT user_id FROM student_profiles WHERE id = spid
$$;

-- ---------------------------------------------------------------------
-- 6. Verification — fail the migration on any inconsistency.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    schools_count   BIGINT;
    orgs_count      BIGINT;
    org_ws_count    BIGINT;
    users_count     BIGINT;
    personal_ws_count BIGINT;
    owner_ms_count  BIGINT;
    profile_rows    BIGINT;
    org_ms_count    BIGINT;
BEGIN
    SELECT COUNT(*) INTO schools_count FROM schools;
    SELECT COUNT(*) INTO orgs_count FROM organizations WHERE legacy_school_id IS NOT NULL;
    IF orgs_count <> schools_count THEN
        RAISE EXCEPTION 'V10 verification: expected % organizations from schools, found %', schools_count, orgs_count;
    END IF;

    SELECT COUNT(*) INTO org_ws_count
    FROM workspaces w JOIN organizations o ON o.id = w.organization_id;
    IF org_ws_count <> orgs_count THEN
        RAISE EXCEPTION 'V10 verification: expected % organization workspaces, found %', orgs_count, org_ws_count;
    END IF;

    SELECT COUNT(*) INTO users_count FROM users;
    SELECT COUNT(*) INTO personal_ws_count FROM workspaces WHERE workspace_type = 'PERSONAL';
    IF personal_ws_count <> users_count THEN
        RAISE EXCEPTION 'V10 verification: expected % personal workspaces (one per user), found %', users_count, personal_ws_count;
    END IF;

    SELECT COUNT(*) INTO owner_ms_count
    FROM workspace_memberships m
    JOIN workspaces w ON w.id = m.workspace_id
    WHERE w.workspace_type = 'PERSONAL' AND m.role = 'OWNER' AND m.status = 'ACTIVE';
    IF owner_ms_count <> users_count THEN
        RAISE EXCEPTION 'V10 verification: expected % personal OWNER memberships, found %', users_count, owner_ms_count;
    END IF;

    SELECT COUNT(*) INTO profile_rows
    FROM (
        SELECT user_id, school_id FROM teacher_profiles
        UNION
        SELECT user_id, school_id FROM student_profiles
    ) p;
    SELECT COUNT(*) INTO org_ms_count
    FROM workspace_memberships m
    JOIN workspaces w ON w.id = m.workspace_id
    WHERE w.workspace_type = 'ORGANIZATION';
    IF org_ms_count <> profile_rows THEN
        RAISE EXCEPTION 'V10 verification: expected % organization memberships (one per legacy profile), found %', profile_rows, org_ms_count;
    END IF;

    -- Workspace shape is enforced by chk_workspaces_shape at insert time;
    -- no misshapen row can exist.
END $$;
