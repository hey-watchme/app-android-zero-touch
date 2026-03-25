-- Live-topic container constraints and metadata alignment
-- Goal:
-- 1) keep one active topic per device
-- 2) add description and boundary metadata for finalize output
-- 3) preserve compatibility with existing topic_status values

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

ALTER TABLE public.zerotouch_conversation_topics
  ADD COLUMN IF NOT EXISTS live_description TEXT,
  ADD COLUMN IF NOT EXISTS final_description TEXT,
  ADD COLUMN IF NOT EXISTS boundary_reason TEXT;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'zerotouch_topics_boundary_reason_check'
  ) THEN
    ALTER TABLE public.zerotouch_conversation_topics
      ADD CONSTRAINT zerotouch_topics_boundary_reason_check
      CHECK (
        boundary_reason IS NULL
        OR boundary_reason IN (
          'idle_timeout',
          'ambient_stopped',
          'manual',
          'legacy_repair'
        )
      );
  END IF;
END$$;

-- Backfill new description fields from existing summary columns.
UPDATE public.zerotouch_conversation_topics
SET live_description = live_summary
WHERE live_description IS NULL
  AND live_summary IS NOT NULL;

UPDATE public.zerotouch_conversation_topics
SET final_description = final_summary
WHERE final_description IS NULL
  AND final_summary IS NOT NULL;

-- Data repair:
-- If multiple active topics exist for one device, keep the latest active topic
-- and finalize the rest so we can safely enforce a unique active-topic index.
WITH ranked AS (
  SELECT
    id,
    device_id,
    ROW_NUMBER() OVER (
      PARTITION BY device_id
      ORDER BY
        COALESCE(last_utterance_at, updated_at, created_at) DESC,
        updated_at DESC,
        created_at DESC,
        id DESC
    ) AS rn
  FROM public.zerotouch_conversation_topics
  WHERE topic_status = 'active'
)
UPDATE public.zerotouch_conversation_topics t
SET
  topic_status = 'finalized',
  finalized_at = COALESCE(t.finalized_at, now()),
  boundary_reason = COALESCE(t.boundary_reason, 'legacy_repair'),
  updated_at = now()
FROM ranked r
WHERE t.id = r.id
  AND r.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS idx_zt_topics_one_active_per_device
  ON public.zerotouch_conversation_topics(device_id)
  WHERE topic_status = 'active';

CREATE INDEX IF NOT EXISTS idx_zt_topics_device_active_last
  ON public.zerotouch_conversation_topics(device_id, last_utterance_at DESC)
  WHERE topic_status = 'active';

COMMENT ON COLUMN public.zerotouch_conversation_topics.live_description IS 'Provisional topic description while topic is active';
COMMENT ON COLUMN public.zerotouch_conversation_topics.final_description IS 'Finalized topic description after LLM enrichment';
COMMENT ON COLUMN public.zerotouch_conversation_topics.boundary_reason IS 'Why this topic was closed (idle_timeout, ambient_stopped, manual, legacy_repair)';
