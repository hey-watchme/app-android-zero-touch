-- Multi-account / multi-workspace ownership model
-- Adds account, workspace, device registry, and context profile tables.
-- Existing device_id-based pipelines continue to work; workspace_id is additive.

CREATE TABLE IF NOT EXISTS public.zerotouch_accounts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  external_auth_provider TEXT,
  external_auth_subject TEXT,
  email TEXT,
  display_name TEXT NOT NULL,
  avatar_url TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_zt_accounts_external_subject
  ON public.zerotouch_accounts(external_auth_provider, external_auth_subject)
  WHERE external_auth_provider IS NOT NULL AND external_auth_subject IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_zt_accounts_email
  ON public.zerotouch_accounts(email)
  WHERE email IS NOT NULL;

CREATE TABLE IF NOT EXISTS public.zerotouch_workspaces (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_account_id UUID REFERENCES public.zerotouch_accounts(id) ON DELETE SET NULL,
  name TEXT NOT NULL,
  slug TEXT,
  description TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_zt_workspaces_slug
  ON public.zerotouch_workspaces(slug)
  WHERE slug IS NOT NULL;

CREATE TABLE IF NOT EXISTS public.zerotouch_workspace_members (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.zerotouch_workspaces(id) ON DELETE CASCADE,
  account_id UUID NOT NULL REFERENCES public.zerotouch_accounts(id) ON DELETE CASCADE,
  role TEXT NOT NULL DEFAULT 'member',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(workspace_id, account_id)
);

ALTER TABLE public.zerotouch_workspace_members
  DROP CONSTRAINT IF EXISTS zt_workspace_members_role_check;

ALTER TABLE public.zerotouch_workspace_members
  ADD CONSTRAINT zt_workspace_members_role_check
  CHECK (role IN ('owner', 'admin', 'member', 'viewer'));

CREATE TABLE IF NOT EXISTS public.zerotouch_devices (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.zerotouch_workspaces(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL,
  display_name TEXT NOT NULL,
  device_kind TEXT NOT NULL DEFAULT 'android_tablet',
  source_type TEXT NOT NULL DEFAULT 'android_ambient',
  platform TEXT,
  context_note TEXT,
  is_virtual BOOLEAN NOT NULL DEFAULT false,
  is_active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(device_id)
);

ALTER TABLE public.zerotouch_devices
  DROP CONSTRAINT IF EXISTS zt_devices_kind_check;

ALTER TABLE public.zerotouch_devices
  ADD CONSTRAINT zt_devices_kind_check
  CHECK (device_kind IN ('android_tablet', 'virtual_device', 'desktop_capture', 'other'));

ALTER TABLE public.zerotouch_devices
  DROP CONSTRAINT IF EXISTS zt_devices_source_check;

ALTER TABLE public.zerotouch_devices
  ADD CONSTRAINT zt_devices_source_check
  CHECK (source_type IN ('android_ambient', 'amical_transcriptions', 'manual_import', 'other'));

CREATE INDEX IF NOT EXISTS idx_zt_devices_workspace_id
  ON public.zerotouch_devices(workspace_id);

CREATE TABLE IF NOT EXISTS public.zerotouch_context_profiles (
  workspace_id UUID PRIMARY KEY REFERENCES public.zerotouch_workspaces(id) ON DELETE CASCADE,
  profile_name TEXT,
  owner_name TEXT,
  role_title TEXT,
  environment TEXT,
  usage_scenario TEXT,
  goal TEXT,
  reference_materials JSONB NOT NULL DEFAULT '[]'::jsonb,
  glossary JSONB NOT NULL DEFAULT '[]'::jsonb,
  prompt_preamble TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.zerotouch_sessions
  ADD COLUMN IF NOT EXISTS workspace_id UUID REFERENCES public.zerotouch_workspaces(id) ON DELETE SET NULL;

ALTER TABLE public.zerotouch_conversation_topics
  ADD COLUMN IF NOT EXISTS workspace_id UUID REFERENCES public.zerotouch_workspaces(id) ON DELETE SET NULL;

ALTER TABLE public.zerotouch_facts
  ADD COLUMN IF NOT EXISTS workspace_id UUID REFERENCES public.zerotouch_workspaces(id) ON DELETE SET NULL;

ALTER TABLE public.zerotouch_device_settings
  ADD COLUMN IF NOT EXISTS workspace_id UUID REFERENCES public.zerotouch_workspaces(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_zt_sessions_workspace_id
  ON public.zerotouch_sessions(workspace_id);

CREATE INDEX IF NOT EXISTS idx_zt_topics_workspace_id
  ON public.zerotouch_conversation_topics(workspace_id);

CREATE INDEX IF NOT EXISTS idx_zt_facts_workspace_id
  ON public.zerotouch_facts(workspace_id);

CREATE INDEX IF NOT EXISTS idx_zt_device_settings_workspace_id
  ON public.zerotouch_device_settings(workspace_id);

UPDATE public.zerotouch_sessions s
SET workspace_id = d.workspace_id
FROM public.zerotouch_devices d
WHERE s.device_id = d.device_id
  AND s.workspace_id IS NULL;

UPDATE public.zerotouch_conversation_topics t
SET workspace_id = d.workspace_id
FROM public.zerotouch_devices d
WHERE t.device_id = d.device_id
  AND t.workspace_id IS NULL;

UPDATE public.zerotouch_facts f
SET workspace_id = d.workspace_id
FROM public.zerotouch_devices d
WHERE f.device_id = d.device_id
  AND f.workspace_id IS NULL;

UPDATE public.zerotouch_device_settings ds
SET workspace_id = d.workspace_id
FROM public.zerotouch_devices d
WHERE ds.device_id = d.device_id
  AND ds.workspace_id IS NULL;

COMMENT ON TABLE public.zerotouch_accounts IS 'User accounts that can own or access ZeroTouch workspaces';
COMMENT ON TABLE public.zerotouch_workspaces IS 'Logical tenant for devices, context, sessions, topics, and facts';
COMMENT ON TABLE public.zerotouch_devices IS 'Registered physical or virtual devices that produce ZeroTouch-compatible data';
COMMENT ON TABLE public.zerotouch_context_profiles IS 'Workspace-level context used to improve knowledge extraction quality';
COMMENT ON COLUMN public.zerotouch_sessions.workspace_id IS 'Owning workspace for this session/card';
COMMENT ON COLUMN public.zerotouch_conversation_topics.workspace_id IS 'Owning workspace for this topic';
COMMENT ON COLUMN public.zerotouch_facts.workspace_id IS 'Owning workspace for this fact';

ALTER TABLE public.zerotouch_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.zerotouch_workspaces ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.zerotouch_workspace_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.zerotouch_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.zerotouch_context_profiles ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_accounts' AND policyname = 'zerotouch_accounts_select'
  ) THEN
    CREATE POLICY "zerotouch_accounts_select" ON public.zerotouch_accounts FOR SELECT USING (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_accounts' AND policyname = 'zerotouch_accounts_insert'
  ) THEN
    CREATE POLICY "zerotouch_accounts_insert" ON public.zerotouch_accounts FOR INSERT WITH CHECK (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_accounts' AND policyname = 'zerotouch_accounts_update'
  ) THEN
    CREATE POLICY "zerotouch_accounts_update" ON public.zerotouch_accounts FOR UPDATE USING (true);
  END IF;
END$$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_workspaces' AND policyname = 'zerotouch_workspaces_select'
  ) THEN
    CREATE POLICY "zerotouch_workspaces_select" ON public.zerotouch_workspaces FOR SELECT USING (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_workspaces' AND policyname = 'zerotouch_workspaces_insert'
  ) THEN
    CREATE POLICY "zerotouch_workspaces_insert" ON public.zerotouch_workspaces FOR INSERT WITH CHECK (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_workspaces' AND policyname = 'zerotouch_workspaces_update'
  ) THEN
    CREATE POLICY "zerotouch_workspaces_update" ON public.zerotouch_workspaces FOR UPDATE USING (true);
  END IF;
END$$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_workspace_members' AND policyname = 'zerotouch_workspace_members_select'
  ) THEN
    CREATE POLICY "zerotouch_workspace_members_select" ON public.zerotouch_workspace_members FOR SELECT USING (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_workspace_members' AND policyname = 'zerotouch_workspace_members_insert'
  ) THEN
    CREATE POLICY "zerotouch_workspace_members_insert" ON public.zerotouch_workspace_members FOR INSERT WITH CHECK (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_workspace_members' AND policyname = 'zerotouch_workspace_members_update'
  ) THEN
    CREATE POLICY "zerotouch_workspace_members_update" ON public.zerotouch_workspace_members FOR UPDATE USING (true);
  END IF;
END$$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_devices' AND policyname = 'zerotouch_devices_select'
  ) THEN
    CREATE POLICY "zerotouch_devices_select" ON public.zerotouch_devices FOR SELECT USING (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_devices' AND policyname = 'zerotouch_devices_insert'
  ) THEN
    CREATE POLICY "zerotouch_devices_insert" ON public.zerotouch_devices FOR INSERT WITH CHECK (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_devices' AND policyname = 'zerotouch_devices_update'
  ) THEN
    CREATE POLICY "zerotouch_devices_update" ON public.zerotouch_devices FOR UPDATE USING (true);
  END IF;
END$$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_context_profiles' AND policyname = 'zerotouch_context_profiles_select'
  ) THEN
    CREATE POLICY "zerotouch_context_profiles_select" ON public.zerotouch_context_profiles FOR SELECT USING (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_context_profiles' AND policyname = 'zerotouch_context_profiles_insert'
  ) THEN
    CREATE POLICY "zerotouch_context_profiles_insert" ON public.zerotouch_context_profiles FOR INSERT WITH CHECK (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_context_profiles' AND policyname = 'zerotouch_context_profiles_update'
  ) THEN
    CREATE POLICY "zerotouch_context_profiles_update" ON public.zerotouch_context_profiles FOR UPDATE USING (true);
  END IF;
END$$;
