-- Add conversation topics for grouping fragmented ambient utterances.

CREATE TABLE IF NOT EXISTS public.conversation_topics (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id TEXT NOT NULL,
  topic_status TEXT NOT NULL DEFAULT 'active',
  start_at TIMESTAMPTZ NOT NULL,
  end_at TIMESTAMPTZ NOT NULL,
  last_utterance_at TIMESTAMPTZ NOT NULL,
  utterance_count INTEGER NOT NULL DEFAULT 0,
  live_title TEXT,
  live_summary TEXT,
  final_title TEXT,
  final_summary TEXT,
  task_candidates JSONB,
  topic_type TEXT,
  grouping_version TEXT,
  last_title_refreshed_at TIMESTAMPTZ,
  last_llm_join_checked_at TIMESTAMPTZ,
  reopened_count INTEGER NOT NULL DEFAULT 0,
  finalized_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (topic_status IN ('active', 'cooling', 'finalized'))
);

ALTER TABLE public.zerotouch_sessions
  ADD COLUMN IF NOT EXISTS topic_id UUID,
  ADD COLUMN IF NOT EXISTS grouping_status TEXT NOT NULL DEFAULT 'ungrouped',
  ADD COLUMN IF NOT EXISTS grouping_method TEXT,
  ADD COLUMN IF NOT EXISTS grouping_confidence NUMERIC,
  ADD COLUMN IF NOT EXISTS topic_assigned_at TIMESTAMPTZ;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'zerotouch_sessions_topic_id_fkey'
  ) THEN
    ALTER TABLE public.zerotouch_sessions
      ADD CONSTRAINT zerotouch_sessions_topic_id_fkey
      FOREIGN KEY (topic_id)
      REFERENCES public.conversation_topics(id)
      ON DELETE SET NULL;
  END IF;
END$$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'zerotouch_sessions_grouping_status_check'
  ) THEN
    ALTER TABLE public.zerotouch_sessions
      ADD CONSTRAINT zerotouch_sessions_grouping_status_check
      CHECK (grouping_status IN ('ungrouped', 'grouped', 'review_needed'));
  END IF;
END$$;

CREATE INDEX IF NOT EXISTS idx_conversation_topics_device_status_last
  ON public.conversation_topics(device_id, topic_status, last_utterance_at DESC);

CREATE INDEX IF NOT EXISTS idx_conversation_topics_device_created
  ON public.conversation_topics(device_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_zerotouch_sessions_topic_id_created
  ON public.zerotouch_sessions(topic_id, created_at ASC);

CREATE INDEX IF NOT EXISTS idx_conversation_topics_active_cooling
  ON public.conversation_topics(device_id, last_utterance_at DESC)
  WHERE topic_status IN ('active', 'cooling');

COMMENT ON TABLE public.conversation_topics IS 'Grouped conversation topics built from zerotouch ambient sessions';
COMMENT ON COLUMN public.conversation_topics.topic_status IS 'active -> cooling -> finalized';
COMMENT ON COLUMN public.zerotouch_sessions.topic_id IS 'Linked topic id for grouped session view';
COMMENT ON COLUMN public.zerotouch_sessions.grouping_status IS 'ungrouped | grouped | review_needed';

ALTER TABLE public.conversation_topics ENABLE ROW LEVEL SECURITY;

CREATE POLICY "conversation_topics_select"
  ON public.conversation_topics
  FOR SELECT
  USING (true);

CREATE POLICY "conversation_topics_insert"
  ON public.conversation_topics
  FOR INSERT
  WITH CHECK (true);

CREATE POLICY "conversation_topics_update"
  ON public.conversation_topics
  FOR UPDATE
  USING (true);
