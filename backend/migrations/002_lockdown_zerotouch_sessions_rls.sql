-- ZeroTouch Sessions table - RLS lockdown
-- This project is intended to be backend-only (service role) access for now.
-- Remove the permissive MVP policies so anon/authenticated clients cannot read/write directly.

ALTER TABLE public.zerotouch_sessions ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "zerotouch_sessions_select" ON public.zerotouch_sessions;
DROP POLICY IF EXISTS "zerotouch_sessions_insert" ON public.zerotouch_sessions;
DROP POLICY IF EXISTS "zerotouch_sessions_update" ON public.zerotouch_sessions;

