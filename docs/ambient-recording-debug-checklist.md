# Ambient Recording Debug Checklist

Issue: `#6` P0 instrumentation for ambient recording and VAD lifecycle.

## 1) Log capture setup

Use real-device logs with only the relevant tags:

```bash
adb logcat -v time AmbientService:D AmbientRecorder:D SileroVadDetector:D *:S
```

## 2) Minimum reproduction flow

1. Enable ambient mode on device.
2. Keep room quiet for 10-20 seconds.
3. Speak one short sentence.
4. Stop speaking and wait at least 7 seconds.
5. Repeat with one short noise burst (desk tap/rustle), no speech.
6. Disable ambient mode.
7. Save the full log segment from ambient start to stop.

## 3) What must be explainable from logs

- Why recording started:
  - `Recording started ... reason=speech_confirmed ...`
  - Includes debounce score, VAD engine, VAD reason, pre-roll size.
- Why recording stopped:
  - `Recording stopped ... reason=...`
  - Includes duration, silenceMs, speech/non-speech frame counts, read error count.
- Why recorder restarted automatically:
  - `Watchdog restart triggered reason=recording_heartbeat_stale ...`
  - Includes stale heartbeat age and status snapshot.

## 4) Fast diagnosis patterns

### A. VAD false positive

- Noise-only environment still shows repeated:
  - `VAD transition speech=true`
  - `Recording started ...`
- Session remains active until long timeout, with low/no real voice.

### B. Recorder read stall

- Repeated:
  - `AudioRecord read error ...`
- Followed by:
  - `AudioRecord read recovered ...` (recovered case), or
  - `... stopping recording` + `reason=read_error` (failed case).

### C. Heartbeat stale restart loop

- Repeated:
  - `Watchdog restart triggered reason=recording_heartbeat_stale ...`
  - `Watchdog restart completed ...`
- Appears without user stop/start action.

### D. UI-only speech hold artifact (not active recording)

- `UI speech hold event=hold_enter` while `isRecording=false`
- No corresponding `Recording started` line
- Hold exits by timeout (`hold_exit`) without session creation.

## 5) Attachments for issue updates

- One full log trace (start -> stop) from a real device session.
- Brief classification label:
  - `vad_false_positive`
  - `read_stall`
  - `heartbeat_stale_restart`
  - `ui_hold_only`
