-- Migration 017: Live Support foundation tables
-- Creates live session, realtime transcript, and keypoint tables.

CREATE TABLE IF NOT EXISTS public.zerotouch_live_sessions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id TEXT NOT NULL,
  workspace_id UUID REFERENCES public.zerotouch_workspaces(id) ON DELETE SET NULL,
  share_token TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL DEFAULT 'active',
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at TIMESTAMPTZ,
  expires_at TIMESTAMPTZ,
  qr_code_url TEXT,
  language_primary TEXT DEFAULT 'ja',
  visibility TEXT NOT NULL DEFAULT 'public',
  is_deleted BOOLEAN NOT NULL DEFAULT false,
  deleted_at TIMESTAMPTZ,
  deleted_reason TEXT,
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT zerotouch_live_sessions_visibility_check
    CHECK (visibility IN ('public', 'private', 'deleted'))
);

CREATE TABLE IF NOT EXISTS public.zerotouch_live_transcripts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  live_session_id UUID NOT NULL REFERENCES public.zerotouch_live_sessions(id) ON DELETE CASCADE,
  chunk_index INTEGER NOT NULL,
  text TEXT NOT NULL,
  provider TEXT,
  model TEXT,
  language TEXT,
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(live_session_id, chunk_index)
);

CREATE TABLE IF NOT EXISTS public.zerotouch_live_keypoints (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  live_session_id UUID NOT NULL REFERENCES public.zerotouch_live_sessions(id) ON DELETE CASCADE,
  transcript_id UUID REFERENCES public.zerotouch_live_transcripts(id) ON DELETE SET NULL,
  keypoint_type TEXT NOT NULL DEFAULT 'note',
  content TEXT NOT NULL,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  confidence NUMERIC(3, 2),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_zerotouch_live_sessions_device_status
  ON public.zerotouch_live_sessions(device_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_zerotouch_live_sessions_share_token
  ON public.zerotouch_live_sessions(share_token);
CREATE INDEX IF NOT EXISTS idx_zerotouch_live_sessions_workspace
  ON public.zerotouch_live_sessions(workspace_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_zerotouch_live_transcripts_session_chunk
  ON public.zerotouch_live_transcripts(live_session_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_zerotouch_live_transcripts_created_at
  ON public.zerotouch_live_transcripts(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_zerotouch_live_keypoints_session_created
  ON public.zerotouch_live_keypoints(live_session_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_zerotouch_live_keypoints_transcript
  ON public.zerotouch_live_keypoints(transcript_id);

COMMENT ON TABLE public.zerotouch_live_sessions IS
  'Live Support sessions for ambient memo assistance.';
COMMENT ON TABLE public.zerotouch_live_transcripts IS
  'Realtime transcription chunks for each live session.';
COMMENT ON TABLE public.zerotouch_live_keypoints IS
  'Derived key points from realtime transcription stream.';

ALTER TABLE public.zerotouch_live_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.zerotouch_live_transcripts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.zerotouch_live_keypoints ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename = 'zerotouch_live_sessions'
      AND policyname = 'zerotouch_live_sessions_select'
  ) THEN
    CREATE POLICY "zerotouch_live_sessions_select"
      ON public.zerotouch_live_sessions FOR SELECT USING (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename = 'zerotouch_live_sessions'
      AND policyname = 'zerotouch_live_sessions_insert'
  ) THEN
    CREATE POLICY "zerotouch_live_sessions_insert"
      ON public.zerotouch_live_sessions FOR INSERT WITH CHECK (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename = 'zerotouch_live_sessions'
      AND policyname = 'zerotouch_live_sessions_update'
  ) THEN
    CREATE POLICY "zerotouch_live_sessions_update"
      ON public.zerotouch_live_sessions FOR UPDATE USING (true);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename = 'zerotouch_live_transcripts'
      AND policyname = 'zerotouch_live_transcripts_select'
  ) THEN
    CREATE POLICY "zerotouch_live_transcripts_select"
      ON public.zerotouch_live_transcripts FOR SELECT USING (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename = 'zerotouch_live_transcripts'
      AND policyname = 'zerotouch_live_transcripts_insert'
  ) THEN
    CREATE POLICY "zerotouch_live_transcripts_insert"
      ON public.zerotouch_live_transcripts FOR INSERT WITH CHECK (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename = 'zerotouch_live_transcripts'
      AND policyname = 'zerotouch_live_transcripts_update'
  ) THEN
    CREATE POLICY "zerotouch_live_transcripts_update"
      ON public.zerotouch_live_transcripts FOR UPDATE USING (true);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename = 'zerotouch_live_keypoints'
      AND policyname = 'zerotouch_live_keypoints_select'
  ) THEN
    CREATE POLICY "zerotouch_live_keypoints_select"
      ON public.zerotouch_live_keypoints FOR SELECT USING (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename = 'zerotouch_live_keypoints'
      AND policyname = 'zerotouch_live_keypoints_insert'
  ) THEN
    CREATE POLICY "zerotouch_live_keypoints_insert"
      ON public.zerotouch_live_keypoints FOR INSERT WITH CHECK (true);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename = 'zerotouch_live_keypoints'
      AND policyname = 'zerotouch_live_keypoints_update'
  ) THEN
    CREATE POLICY "zerotouch_live_keypoints_update"
      ON public.zerotouch_live_keypoints FOR UPDATE USING (true);
  END IF;
END$$;
