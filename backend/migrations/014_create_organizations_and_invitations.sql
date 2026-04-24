-- Migration 014: Organizations + Invitations + Role unification
--
-- Verified against live schema (project qvtlwotzuzbavrzqhyvt) on 2026-04-24:
--   - zerotouch_accounts.id                  UUID PK (gen_random_uuid)
--   - zerotouch_workspaces.id                UUID PK (gen_random_uuid)
--   - zerotouch_workspaces.owner_account_id  UUID nullable, FK -> accounts(id)
--   - zerotouch_workspace_members.role       CHECK ('owner','admin','member','viewer')
--     constraint name: zt_workspace_members_role_check
--   - helper function update_updated_at_column() already exists (reuse it)
--   - gen_random_uuid() is available
--
-- What this migration does:
--   1. Create zerotouch_organizations
--   2. Create zerotouch_organization_members (role: org_admin / org_member)
--   3. Add zerotouch_workspaces.organization_id (FK)
--   4. For each existing workspace, create one Organization and link it
--   5. Populate organization_members from existing workspace_members
--   6. Unify workspace_members.role to 3 tiers: admin / editor / viewer
--   7. Create zerotouch_workspace_invitations
--   8. Enable RLS with permissive policies (app layer enforces access)
--   9. Attach updated_at triggers using existing update_updated_at_column()
--
-- Run as a single transaction: any failure rolls back the entire migration.

BEGIN;

-- ----- 1. zerotouch_organizations ---------------------------------------
CREATE TABLE IF NOT EXISTS zerotouch_organizations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL,
    slug        TEXT,
    created_by  UUID REFERENCES zerotouch_accounts(id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_zt_organizations_slug
    ON zerotouch_organizations(slug) WHERE slug IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_zt_organizations_created_by
    ON zerotouch_organizations(created_by);

-- ----- 2. zerotouch_organization_members --------------------------------
CREATE TABLE IF NOT EXISTS zerotouch_organization_members (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID NOT NULL REFERENCES zerotouch_organizations(id) ON DELETE CASCADE,
    account_id       UUID NOT NULL REFERENCES zerotouch_accounts(id)      ON DELETE CASCADE,
    role             TEXT NOT NULL DEFAULT 'org_member',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT zt_org_members_role_check
        CHECK (role IN ('org_admin', 'org_member')),
    CONSTRAINT zt_org_members_unique
        UNIQUE (organization_id, account_id)
);

CREATE INDEX IF NOT EXISTS idx_zt_org_members_account
    ON zerotouch_organization_members(account_id);
CREATE INDEX IF NOT EXISTS idx_zt_org_members_org
    ON zerotouch_organization_members(organization_id);

-- ----- 3. zerotouch_workspaces.organization_id --------------------------
ALTER TABLE zerotouch_workspaces
    ADD COLUMN IF NOT EXISTS organization_id UUID
    REFERENCES zerotouch_organizations(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_zt_workspaces_organization
    ON zerotouch_workspaces(organization_id);

-- ----- 4. Create one Organization per existing Workspace ---------------
DO $$
DECLARE
    ws RECORD;
    new_org_id UUID;
BEGIN
    FOR ws IN
        SELECT id, name, owner_account_id
        FROM zerotouch_workspaces
        WHERE organization_id IS NULL
    LOOP
        new_org_id := gen_random_uuid();

        INSERT INTO zerotouch_organizations (id, name, created_by)
        VALUES (new_org_id, ws.name, ws.owner_account_id);

        UPDATE zerotouch_workspaces
        SET organization_id = new_org_id
        WHERE id = ws.id;
    END LOOP;
END $$;

ALTER TABLE zerotouch_workspaces
    ALTER COLUMN organization_id SET NOT NULL;

-- ----- 5. Populate organization_members from workspace_members ----------
-- owner/admin -> org_admin, others -> org_member
INSERT INTO zerotouch_organization_members (organization_id, account_id, role)
SELECT DISTINCT ON (w.organization_id, wm.account_id)
    w.organization_id,
    wm.account_id,
    CASE
        WHEN wm.role IN ('owner', 'admin') THEN 'org_admin'
        ELSE 'org_member'
    END AS role
FROM zerotouch_workspace_members wm
JOIN zerotouch_workspaces w ON wm.workspace_id = w.id
ORDER BY
    w.organization_id,
    wm.account_id,
    CASE WHEN wm.role IN ('owner', 'admin') THEN 0 ELSE 1 END
ON CONFLICT (organization_id, account_id) DO NOTHING;

-- ----- 6. Unify workspace_members.role to 3 tiers -----------------------
-- Drop the existing 4-tier CHECK constraint (name from live DB)
ALTER TABLE zerotouch_workspace_members
    DROP CONSTRAINT IF EXISTS zt_workspace_members_role_check;

-- Convert values
UPDATE zerotouch_workspace_members SET role = 'admin'  WHERE role IN ('owner', 'admin');
UPDATE zerotouch_workspace_members SET role = 'editor' WHERE role = 'member';
-- 'viewer' stays as-is

-- Re-add CHECK constraint with the same name, new 3-tier set
ALTER TABLE zerotouch_workspace_members
    ADD CONSTRAINT zt_workspace_members_role_check
    CHECK (role IN ('admin', 'editor', 'viewer'));

-- Update default from 'member' to 'editor'
ALTER TABLE zerotouch_workspace_members
    ALTER COLUMN role SET DEFAULT 'editor';

-- ----- 7. zerotouch_workspace_invitations -------------------------------
CREATE TABLE IF NOT EXISTS zerotouch_workspace_invitations (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id   UUID NOT NULL REFERENCES zerotouch_workspaces(id) ON DELETE CASCADE,
    invited_email  TEXT NOT NULL,
    role           TEXT NOT NULL DEFAULT 'editor',
    token          TEXT NOT NULL UNIQUE,
    invited_by     UUID REFERENCES zerotouch_accounts(id) ON DELETE SET NULL,
    status         TEXT NOT NULL DEFAULT 'pending',
    expires_at     TIMESTAMPTZ NOT NULL,
    accepted_at    TIMESTAMPTZ,
    accepted_by    UUID REFERENCES zerotouch_accounts(id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT zt_invitations_role_check
        CHECK (role IN ('admin', 'editor', 'viewer')),
    CONSTRAINT zt_invitations_status_check
        CHECK (status IN ('pending', 'accepted', 'expired', 'revoked'))
);

CREATE INDEX IF NOT EXISTS idx_zt_invitations_workspace
    ON zerotouch_workspace_invitations(workspace_id);
CREATE INDEX IF NOT EXISTS idx_zt_invitations_email_status
    ON zerotouch_workspace_invitations(invited_email, status);

-- ----- 8. RLS (permissive; app.py enforces real access control) --------
ALTER TABLE zerotouch_organizations ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS zt_organizations_all ON zerotouch_organizations;
CREATE POLICY zt_organizations_all ON zerotouch_organizations
    FOR ALL USING (true) WITH CHECK (true);

ALTER TABLE zerotouch_organization_members ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS zt_org_members_all ON zerotouch_organization_members;
CREATE POLICY zt_org_members_all ON zerotouch_organization_members
    FOR ALL USING (true) WITH CHECK (true);

ALTER TABLE zerotouch_workspace_invitations ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS zt_invitations_all ON zerotouch_workspace_invitations;
CREATE POLICY zt_invitations_all ON zerotouch_workspace_invitations
    FOR ALL USING (true) WITH CHECK (true);

-- ----- 9. updated_at triggers (reuse existing helper) ------------------
DROP TRIGGER IF EXISTS trg_zt_organizations_updated_at ON zerotouch_organizations;
CREATE TRIGGER trg_zt_organizations_updated_at
    BEFORE UPDATE ON zerotouch_organizations
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

DROP TRIGGER IF EXISTS trg_zt_org_members_updated_at ON zerotouch_organization_members;
CREATE TRIGGER trg_zt_org_members_updated_at
    BEFORE UPDATE ON zerotouch_organization_members
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

DROP TRIGGER IF EXISTS trg_zt_invitations_updated_at ON zerotouch_workspace_invitations;
CREATE TRIGGER trg_zt_invitations_updated_at
    BEFORE UPDATE ON zerotouch_workspace_invitations
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMIT;
