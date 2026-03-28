-- ZeroTouch Facts table (Phase 2: Annotation output)

CREATE TABLE IF NOT EXISTS public.zerotouch_facts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  topic_id UUID NOT NULL REFERENCES public.zerotouch_conversation_topics(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL,
  fact_text TEXT NOT NULL,
  importance_level INTEGER,
  entities JSONB,
  categories TEXT[] DEFAULT '{}',
  intents TEXT[] DEFAULT '{}',
  ttl_type TEXT,
  expires_at TIMESTAMPTZ,
  embedding_id TEXT,
  consolidation_status TEXT DEFAULT 'pending',
  knowledge_id UUID,
  source_cards UUID[] DEFAULT '{}',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_zerotouch_facts_topic_id ON public.zerotouch_facts(topic_id);
CREATE INDEX IF NOT EXISTS idx_zerotouch_facts_device_id ON public.zerotouch_facts(device_id);
CREATE INDEX IF NOT EXISTS idx_zerotouch_facts_importance ON public.zerotouch_facts(importance_level);
CREATE INDEX IF NOT EXISTS idx_zerotouch_facts_created_at ON public.zerotouch_facts(created_at DESC);

COMMENT ON TABLE public.zerotouch_facts IS 'ZeroTouch: annotation results extracted from topics';
COMMENT ON COLUMN public.zerotouch_facts.fact_text IS 'Normalized fact text extracted by LLM';
COMMENT ON COLUMN public.zerotouch_facts.entities IS 'Extracted entities as JSON';
COMMENT ON COLUMN public.zerotouch_facts.ttl_type IS 'ephemeral | seasonal | permanent';

ALTER TABLE public.zerotouch_facts ENABLE ROW LEVEL SECURITY;

CREATE POLICY "zerotouch_facts_select" ON public.zerotouch_facts
  FOR SELECT USING (true);

CREATE POLICY "zerotouch_facts_insert" ON public.zerotouch_facts
  FOR INSERT WITH CHECK (true);

CREATE POLICY "zerotouch_facts_update" ON public.zerotouch_facts
  FOR UPDATE USING (true);
