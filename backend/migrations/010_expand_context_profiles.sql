-- Expand context profiles to support structured context enrichment

ALTER TABLE public.zerotouch_context_profiles
  ADD COLUMN IF NOT EXISTS schema_version INTEGER NOT NULL DEFAULT 1;

ALTER TABLE public.zerotouch_context_profiles
  ADD COLUMN IF NOT EXISTS account_context JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE public.zerotouch_context_profiles
  ADD COLUMN IF NOT EXISTS workspace_context JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE public.zerotouch_context_profiles
  ADD COLUMN IF NOT EXISTS device_contexts JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE public.zerotouch_context_profiles
  ADD COLUMN IF NOT EXISTS environment_context JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE public.zerotouch_context_profiles
  ADD COLUMN IF NOT EXISTS analysis_context JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE public.zerotouch_context_profiles
  ADD COLUMN IF NOT EXISTS onboarding_completed_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_zt_context_profiles_schema_version
  ON public.zerotouch_context_profiles(schema_version);

CREATE INDEX IF NOT EXISTS idx_zt_context_profiles_workspace_context
  ON public.zerotouch_context_profiles USING GIN (workspace_context);

CREATE INDEX IF NOT EXISTS idx_zt_context_profiles_analysis_context
  ON public.zerotouch_context_profiles USING GIN (analysis_context);

COMMENT ON COLUMN public.zerotouch_context_profiles.schema_version IS 'Context profile schema version';
COMMENT ON COLUMN public.zerotouch_context_profiles.account_context IS 'Account-level context JSON';
COMMENT ON COLUMN public.zerotouch_context_profiles.workspace_context IS 'Workspace-level context JSON';
COMMENT ON COLUMN public.zerotouch_context_profiles.device_contexts IS 'Device-level context list JSON';
COMMENT ON COLUMN public.zerotouch_context_profiles.environment_context IS 'Environment context JSON';
COMMENT ON COLUMN public.zerotouch_context_profiles.analysis_context IS 'Analysis goals and preferences JSON';
COMMENT ON COLUMN public.zerotouch_context_profiles.onboarding_completed_at IS 'Timestamp when onboarding was completed';
