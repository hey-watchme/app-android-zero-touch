-- Enable Supabase Realtime for the conversation pipeline tables.
-- Android subscribes to row changes via Phoenix Channels WebSocket so the
-- Conversation panel reflects Card / Topic updates without polling.

-- Add tables to the supabase_realtime publication (idempotent guard).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime'
          AND schemaname = 'public'
          AND tablename = 'zerotouch_sessions'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.zerotouch_sessions;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime'
          AND schemaname = 'public'
          AND tablename = 'zerotouch_conversation_topics'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.zerotouch_conversation_topics;
    END IF;
END
$$;

-- REPLICA IDENTITY FULL ensures UPDATE/DELETE events carry the previous row,
-- so RLS filters that depend on existing column values work correctly.
ALTER TABLE public.zerotouch_sessions REPLICA IDENTITY FULL;
ALTER TABLE public.zerotouch_conversation_topics REPLICA IDENTITY FULL;
