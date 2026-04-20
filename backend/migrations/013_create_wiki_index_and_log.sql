-- Migration 013: Create wiki index (one-liner summary table) and wiki operation log
-- zerotouch_wiki_index: lightweight per-page summary used as a table of contents for Query.
-- zerotouch_wiki_log:   append-only audit log for ingest / query / lint / flag_inconsistency operations.

CREATE TABLE IF NOT EXISTS public.zerotouch_wiki_index (
  page_id UUID PRIMARY KEY REFERENCES public.zerotouch_wiki_pages(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL,
  workspace_id UUID REFERENCES public.zerotouch_workspaces(id) ON DELETE SET NULL,
  title TEXT NOT NULL,
  project_id UUID REFERENCES public.zerotouch_workspace_projects(id) ON DELETE SET NULL,
  project_key TEXT,
  project_name TEXT,
  category TEXT,
  page_key TEXT,
  kind TEXT,
  summary_one_liner TEXT NOT NULL,
  source_count INTEGER NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_zt_wiki_index_device_id
  ON public.zerotouch_wiki_index(device_id);

CREATE INDEX IF NOT EXISTS idx_zt_wiki_index_device_project_category
  ON public.zerotouch_wiki_index(device_id, project_id, category);

CREATE INDEX IF NOT EXISTS idx_zt_wiki_index_device_kind
  ON public.zerotouch_wiki_index(device_id, kind);

ALTER TABLE public.zerotouch_wiki_index ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_wiki_index' AND policyname = 'zerotouch_wiki_index_select'
  ) THEN
    CREATE POLICY "zerotouch_wiki_index_select" ON public.zerotouch_wiki_index FOR SELECT USING (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_wiki_index' AND policyname = 'zerotouch_wiki_index_insert'
  ) THEN
    CREATE POLICY "zerotouch_wiki_index_insert" ON public.zerotouch_wiki_index FOR INSERT WITH CHECK (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_wiki_index' AND policyname = 'zerotouch_wiki_index_update'
  ) THEN
    CREATE POLICY "zerotouch_wiki_index_update" ON public.zerotouch_wiki_index FOR UPDATE USING (true);
  END IF;
END$$;


CREATE TABLE IF NOT EXISTS public.zerotouch_wiki_log (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id TEXT NOT NULL,
  workspace_id UUID REFERENCES public.zerotouch_workspaces(id) ON DELETE SET NULL,
  operation TEXT NOT NULL,
  question TEXT,
  answer TEXT,
  confidence TEXT,
  outcome TEXT,
  reasoning TEXT,
  source_page_ids UUID[] NOT NULL DEFAULT '{}',
  target_page_id UUID REFERENCES public.zerotouch_wiki_pages(id) ON DELETE SET NULL,
  result_page_id UUID REFERENCES public.zerotouch_wiki_pages(id) ON DELETE SET NULL,
  llm_provider TEXT,
  llm_model TEXT,
  meta JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.zerotouch_wiki_log
  DROP CONSTRAINT IF EXISTS zt_wiki_log_operation_check;

ALTER TABLE public.zerotouch_wiki_log
  ADD CONSTRAINT zt_wiki_log_operation_check
  CHECK (operation IN ('ingest', 'query', 'lint', 'flag_inconsistency'));

CREATE INDEX IF NOT EXISTS idx_zt_wiki_log_device_created
  ON public.zerotouch_wiki_log(device_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_zt_wiki_log_device_operation_created
  ON public.zerotouch_wiki_log(device_id, operation, created_at DESC);

ALTER TABLE public.zerotouch_wiki_log ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_wiki_log' AND policyname = 'zerotouch_wiki_log_select'
  ) THEN
    CREATE POLICY "zerotouch_wiki_log_select" ON public.zerotouch_wiki_log FOR SELECT USING (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_wiki_log' AND policyname = 'zerotouch_wiki_log_insert'
  ) THEN
    CREATE POLICY "zerotouch_wiki_log_insert" ON public.zerotouch_wiki_log FOR INSERT WITH CHECK (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'zerotouch_wiki_log' AND policyname = 'zerotouch_wiki_log_update'
  ) THEN
    CREATE POLICY "zerotouch_wiki_log_update" ON public.zerotouch_wiki_log FOR UPDATE USING (true);
  END IF;
END$$;

COMMENT ON TABLE public.zerotouch_wiki_index IS 'One-liner summary per wiki page; used as a table of contents for Query selector';
COMMENT ON COLUMN public.zerotouch_wiki_index.summary_one_liner IS 'Single Japanese sentence (<=120 chars) describing what the page is about';
COMMENT ON COLUMN public.zerotouch_wiki_index.source_count IS 'Number of source facts that contributed to the underlying wiki page';

COMMENT ON TABLE public.zerotouch_wiki_log IS 'Append-only audit log for wiki ingest/query/lint/flag_inconsistency operations';
COMMENT ON COLUMN public.zerotouch_wiki_log.outcome IS 'For query ops: derivable | synthesis | gap_or_conflict';
COMMENT ON COLUMN public.zerotouch_wiki_log.target_page_id IS 'Page the operation flagged (gap_or_conflict) or otherwise targeted';
COMMENT ON COLUMN public.zerotouch_wiki_log.result_page_id IS 'Page produced by the operation (e.g., new query_answer page from synthesis)';
