-- Migration 012: Add project registry and extend wiki pages to project/category/page model

CREATE TABLE IF NOT EXISTS public.zerotouch_workspace_projects (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.zerotouch_workspaces(id) ON DELETE CASCADE,
  project_key TEXT NOT NULL,
  display_name TEXT NOT NULL,
  description TEXT,
  aliases JSONB NOT NULL DEFAULT '[]'::jsonb,
  status TEXT NOT NULL DEFAULT 'active',
  source TEXT NOT NULL DEFAULT 'manual',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(workspace_id, project_key)
);

ALTER TABLE public.zerotouch_workspace_projects
  DROP CONSTRAINT IF EXISTS zt_workspace_projects_status_check;

ALTER TABLE public.zerotouch_workspace_projects
  ADD CONSTRAINT zt_workspace_projects_status_check
  CHECK (status IN ('active', 'archived'));

ALTER TABLE public.zerotouch_workspace_projects
  DROP CONSTRAINT IF EXISTS zt_workspace_projects_source_check;

ALTER TABLE public.zerotouch_workspace_projects
  ADD CONSTRAINT zt_workspace_projects_source_check
  CHECK (source IN ('manual', 'seeded_from_context', 'imported'));

CREATE INDEX IF NOT EXISTS idx_zt_workspace_projects_workspace_id
  ON public.zerotouch_workspace_projects(workspace_id);

CREATE INDEX IF NOT EXISTS idx_zt_workspace_projects_workspace_status
  ON public.zerotouch_workspace_projects(workspace_id, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_zt_workspace_projects_aliases
  ON public.zerotouch_workspace_projects USING GIN (aliases);

ALTER TABLE public.zerotouch_workspace_projects ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_workspace_projects' AND policyname = 'zerotouch_workspace_projects_select'
  ) THEN
    CREATE POLICY "zerotouch_workspace_projects_select" ON public.zerotouch_workspace_projects FOR SELECT USING (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_workspace_projects' AND policyname = 'zerotouch_workspace_projects_insert'
  ) THEN
    CREATE POLICY "zerotouch_workspace_projects_insert" ON public.zerotouch_workspace_projects FOR INSERT WITH CHECK (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_workspace_projects' AND policyname = 'zerotouch_workspace_projects_update'
  ) THEN
    CREATE POLICY "zerotouch_workspace_projects_update" ON public.zerotouch_workspace_projects FOR UPDATE USING (true);
  END IF;
END$$;

ALTER TABLE public.zerotouch_wiki_pages
  ADD COLUMN IF NOT EXISTS project_id UUID REFERENCES public.zerotouch_workspace_projects(id) ON DELETE SET NULL;

ALTER TABLE public.zerotouch_wiki_pages
  ADD COLUMN IF NOT EXISTS category TEXT;

ALTER TABLE public.zerotouch_wiki_pages
  ADD COLUMN IF NOT EXISTS page_key TEXT;

UPDATE public.zerotouch_wiki_pages
SET category = theme
WHERE category IS NULL
  AND theme IS NOT NULL;

UPDATE public.zerotouch_wiki_pages
SET page_key = id::text
WHERE page_key IS NULL;

CREATE INDEX IF NOT EXISTS idx_zt_wiki_pages_project_id
  ON public.zerotouch_wiki_pages(project_id);

CREATE INDEX IF NOT EXISTS idx_zt_wiki_pages_device_project_category
  ON public.zerotouch_wiki_pages(device_id, project_id, category, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_zt_wiki_pages_device_page_key
  ON public.zerotouch_wiki_pages(device_id, page_key);

COMMENT ON TABLE public.zerotouch_workspace_projects IS 'Workspace-scoped registry of human-managed projects used to classify wiki pages';
COMMENT ON COLUMN public.zerotouch_workspace_projects.project_key IS 'Stable machine key for a project within a workspace';
COMMENT ON COLUMN public.zerotouch_workspace_projects.aliases IS 'Known alternative names or shorthand labels for project matching';
COMMENT ON COLUMN public.zerotouch_wiki_pages.project_id IS 'Optional owning project for this wiki page';
COMMENT ON COLUMN public.zerotouch_wiki_pages.category IS 'Project-level category that replaces legacy theme over time';
COMMENT ON COLUMN public.zerotouch_wiki_pages.page_key IS 'Stable machine key for the page, independent of display title';
