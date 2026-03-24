-- Store per-device LLM settings for Process 2 topic evaluation.

CREATE TABLE IF NOT EXISTS public.zerotouch_device_settings (
  device_id TEXT PRIMARY KEY,
  llm_provider TEXT NOT NULL DEFAULT 'openai',
  llm_model TEXT NOT NULL DEFAULT 'gpt-4.1-nano',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE public.zerotouch_device_settings IS 'Per-device settings for ZeroTouch pipelines';
COMMENT ON COLUMN public.zerotouch_device_settings.llm_provider IS 'LLM provider for topic evaluation';
COMMENT ON COLUMN public.zerotouch_device_settings.llm_model IS 'LLM model for topic evaluation';

ALTER TABLE public.zerotouch_device_settings ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename = 'zerotouch_device_settings'
      AND policyname = 'zerotouch_device_settings_select'
  ) THEN
    CREATE POLICY "zerotouch_device_settings_select"
      ON public.zerotouch_device_settings
      FOR SELECT
      USING (true);
  END IF;
END$$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename = 'zerotouch_device_settings'
      AND policyname = 'zerotouch_device_settings_insert'
  ) THEN
    CREATE POLICY "zerotouch_device_settings_insert"
      ON public.zerotouch_device_settings
      FOR INSERT
      WITH CHECK (true);
  END IF;
END$$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename = 'zerotouch_device_settings'
      AND policyname = 'zerotouch_device_settings_update'
  ) THEN
    CREATE POLICY "zerotouch_device_settings_update"
      ON public.zerotouch_device_settings
      FOR UPDATE
      USING (true);
  END IF;
END$$;
