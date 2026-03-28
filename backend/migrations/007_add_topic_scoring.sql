-- 007: Add importance scoring columns to zerotouch_conversation_topics
-- Phase 1 of Knowledge Distillation Pipeline
--
-- importance_level: 0-5 (0=noise, 5=master knowledge)
-- importance_reason: LLM or heuristic explanation
-- distillation_status: pipeline progress tracking
-- scored_at: when scoring was completed

-- Step 1: Add columns
ALTER TABLE zerotouch_conversation_topics
  ADD COLUMN IF NOT EXISTS importance_level INTEGER,
  ADD COLUMN IF NOT EXISTS importance_reason TEXT,
  ADD COLUMN IF NOT EXISTS distillation_status TEXT DEFAULT 'pending',
  ADD COLUMN IF NOT EXISTS scored_at TIMESTAMPTZ;

-- Step 2: Check constraint for importance_level (0-5)
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'zt_topics_importance_level_range'
  ) THEN
    ALTER TABLE zerotouch_conversation_topics
      ADD CONSTRAINT zt_topics_importance_level_range
      CHECK (importance_level IS NULL OR (importance_level >= 0 AND importance_level <= 5));
  END IF;
END $$;

-- Step 3: Check constraint for distillation_status
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'zt_topics_distillation_status_check'
  ) THEN
    ALTER TABLE zerotouch_conversation_topics
      ADD CONSTRAINT zt_topics_distillation_status_check
      CHECK (distillation_status IS NULL OR distillation_status IN (
        'pending', 'scored', 'annotated', 'consolidated', 'skipped'
      ));
  END IF;
END $$;

-- Step 4: Index for filtering by importance_level
CREATE INDEX IF NOT EXISTS idx_zt_topics_importance_level
  ON zerotouch_conversation_topics (importance_level)
  WHERE importance_level IS NOT NULL;

-- Step 5: Index for distillation pipeline queries
CREATE INDEX IF NOT EXISTS idx_zt_topics_distillation_status
  ON zerotouch_conversation_topics (distillation_status)
  WHERE distillation_status IS NOT NULL AND distillation_status != 'skipped';

-- Step 6: Backfill existing finalized topics as 'pending'
UPDATE zerotouch_conversation_topics
SET distillation_status = 'pending'
WHERE topic_status = 'finalized'
  AND distillation_status IS NULL;

-- Step 7: Mark active topics as NULL (not yet ready for scoring)
-- Active topics will be scored when they are finalized
