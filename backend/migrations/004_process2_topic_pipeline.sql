-- Process 2 topic pipeline foundation:
-- 1) align topic table naming with zerotouch_ prefix
-- 2) add evaluation run table for device-based batch processing
-- 3) add per-session evaluation state columns

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.tables
    WHERE table_schema = 'public'
      AND table_name = 'conversation_topics'
  ) AND NOT EXISTS (
    SELECT 1
    FROM information_schema.tables
    WHERE table_schema = 'public'
      AND table_name = 'zerotouch_conversation_topics'
  ) THEN
    ALTER TABLE public.conversation_topics
      RENAME TO zerotouch_conversation_topics;
  END IF;
END$$;

CREATE TABLE IF NOT EXISTS public.zerotouch_topic_evaluation_runs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id TEXT NOT NULL,
  run_status TEXT NOT NULL DEFAULT 'processing',
  trigger_type TEXT NOT NULL DEFAULT 'idle_60s_no_utterance',
  session_count INTEGER NOT NULL DEFAULT 0,
  first_session_at TIMESTAMPTZ,
  last_session_at TIMESTAMPTZ,
  first_session_id UUID,
  last_session_id UUID,
  triggered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  locked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ,
  retry_count INTEGER NOT NULL DEFAULT 0,
  error_code TEXT,
  error_message TEXT,
  llm_provider TEXT,
  llm_model TEXT,
  llm_response_json JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (run_status IN ('processing', 'completed', 'needs_retry'))
);

ALTER TABLE public.zerotouch_sessions
  ADD COLUMN IF NOT EXISTS topic_eval_status TEXT NOT NULL DEFAULT 'pending',
  ADD COLUMN IF NOT EXISTS topic_eval_run_id UUID,
  ADD COLUMN IF NOT EXISTS topic_eval_marked_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS topic_eval_error TEXT;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'zerotouch_sessions_topic_eval_status_check'
  ) THEN
    ALTER TABLE public.zerotouch_sessions
      ADD CONSTRAINT zerotouch_sessions_topic_eval_status_check
      CHECK (topic_eval_status IN ('pending', 'processing', 'grouped', 'needs_retry'));
  END IF;
END$$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'zerotouch_sessions_topic_eval_run_id_fkey'
  ) THEN
    ALTER TABLE public.zerotouch_sessions
      ADD CONSTRAINT zerotouch_sessions_topic_eval_run_id_fkey
      FOREIGN KEY (topic_eval_run_id)
      REFERENCES public.zerotouch_topic_evaluation_runs(id)
      ON DELETE SET NULL;
  END IF;
END$$;

UPDATE public.zerotouch_sessions
SET topic_eval_status = CASE
  WHEN topic_id IS NOT NULL THEN 'grouped'
  WHEN status = 'transcribed' THEN 'pending'
  ELSE topic_eval_status
END,
topic_eval_marked_at = CASE
  WHEN topic_eval_marked_at IS NOT NULL THEN topic_eval_marked_at
  ELSE now()
END
WHERE topic_eval_status IS DISTINCT FROM CASE
  WHEN topic_id IS NOT NULL THEN 'grouped'
  WHEN status = 'transcribed' THEN 'pending'
  ELSE topic_eval_status
END
OR topic_eval_marked_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_zerotouch_topic_runs_device_status_triggered
  ON public.zerotouch_topic_evaluation_runs(device_id, run_status, triggered_at DESC);

CREATE INDEX IF NOT EXISTS idx_zerotouch_topic_runs_device_locked
  ON public.zerotouch_topic_evaluation_runs(device_id, locked_at DESC)
  WHERE run_status = 'processing';

CREATE INDEX IF NOT EXISTS idx_zerotouch_sessions_device_eval_pending
  ON public.zerotouch_sessions(device_id, recorded_at ASC)
  WHERE topic_id IS NULL
    AND status = 'transcribed'
    AND topic_eval_status IN ('pending', 'needs_retry');

CREATE INDEX IF NOT EXISTS idx_zerotouch_sessions_eval_run
  ON public.zerotouch_sessions(topic_eval_run_id, created_at ASC);

COMMENT ON TABLE public.zerotouch_conversation_topics IS 'Topic groups for ZeroTouch ambient sessions';
COMMENT ON TABLE public.zerotouch_topic_evaluation_runs IS 'Process 2 evaluation batches for topic generation';
COMMENT ON COLUMN public.zerotouch_sessions.topic_eval_status IS 'pending | processing | grouped | needs_retry';
COMMENT ON COLUMN public.zerotouch_sessions.topic_eval_run_id IS 'Batch run id used for topic evaluation';

ALTER TABLE public.zerotouch_topic_evaluation_runs ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename = 'zerotouch_topic_evaluation_runs'
      AND policyname = 'zerotouch_topic_runs_select'
  ) THEN
    CREATE POLICY "zerotouch_topic_runs_select"
      ON public.zerotouch_topic_evaluation_runs
      FOR SELECT
      USING (true);
  END IF;
END$$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename = 'zerotouch_topic_evaluation_runs'
      AND policyname = 'zerotouch_topic_runs_insert'
  ) THEN
    CREATE POLICY "zerotouch_topic_runs_insert"
      ON public.zerotouch_topic_evaluation_runs
      FOR INSERT
      WITH CHECK (true);
  END IF;
END$$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename = 'zerotouch_topic_evaluation_runs'
      AND policyname = 'zerotouch_topic_runs_update'
  ) THEN
    CREATE POLICY "zerotouch_topic_runs_update"
      ON public.zerotouch_topic_evaluation_runs
      FOR UPDATE
      USING (true);
  END IF;
END$$;
