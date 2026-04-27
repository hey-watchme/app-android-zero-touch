-- Add upload idempotency for device-originated ambient recordings.
-- Android generates one local_recording_id per finalized audio file and sends
-- it with /api/upload. Retrying the same file for the same device must not
-- create duplicate zerotouch_sessions rows.

ALTER TABLE public.zerotouch_sessions
  ADD COLUMN IF NOT EXISTS local_recording_id TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS idx_zerotouch_sessions_device_local_recording
  ON public.zerotouch_sessions(device_id, local_recording_id)
  WHERE local_recording_id IS NOT NULL;

COMMENT ON COLUMN public.zerotouch_sessions.local_recording_id IS
  'Client-generated stable id for an audio file; used to make /api/upload idempotent per device.';
