-- ZeroTouch Sessions table
-- MVP: Recording -> Transcription -> Card generation pipeline

CREATE TABLE public.zerotouch_sessions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id TEXT NOT NULL,

  -- Audio
  s3_audio_path TEXT,
  duration_seconds INTEGER,

  -- Phase 0: Transcription (STT)
  transcription TEXT,
  transcription_metadata JSONB,

  -- Phase 1: Card generation (LLM)
  cards_prompt TEXT,
  cards_result JSONB,
  model_used TEXT,

  -- Status management
  status TEXT NOT NULL DEFAULT 'recording',
  error_message TEXT,

  -- Timestamps
  recorded_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indexes
CREATE INDEX idx_zerotouch_sessions_device_id ON public.zerotouch_sessions(device_id);
CREATE INDEX idx_zerotouch_sessions_status ON public.zerotouch_sessions(status);
CREATE INDEX idx_zerotouch_sessions_created_at ON public.zerotouch_sessions(created_at DESC);

-- Comments
COMMENT ON TABLE public.zerotouch_sessions IS 'ZeroTouch: ambient voice capture sessions for memo/card generation';
COMMENT ON COLUMN public.zerotouch_sessions.status IS 'recording -> uploaded -> transcribing -> transcribed -> generating -> completed / failed';
COMMENT ON COLUMN public.zerotouch_sessions.cards_result IS 'LLM-generated cards in JSON format';

-- RLS
ALTER TABLE public.zerotouch_sessions ENABLE ROW LEVEL SECURITY;

-- For MVP: allow all operations (tighten later with auth)
CREATE POLICY "zerotouch_sessions_select" ON public.zerotouch_sessions
  FOR SELECT USING (true);

CREATE POLICY "zerotouch_sessions_insert" ON public.zerotouch_sessions
  FOR INSERT WITH CHECK (true);

CREATE POLICY "zerotouch_sessions_update" ON public.zerotouch_sessions
  FOR UPDATE USING (true);
