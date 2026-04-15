# AGENTS

This file contains project-specific notes for `/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch`.

If this file and a parent `AGENTS.md` overlap, follow this file for `android-zero-touch` work.

## Python Environment

- Import / reprocess scripts under `experiments/amical/` are currently run with the system `python3`:
  `/Library/Frameworks/Python.framework/Versions/3.12/bin/python3`
- Before running importers in a new session, install backend dependencies from:
  `app/android-zero-touch/backend/requirements.txt`
- Recommended command:

```bash
cd /Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/backend
python3 -m pip install -r requirements.txt
```

- Verified package baseline on `2026-04-15`:
  - `supabase 2.11.0`
  - `postgrest 0.19.3`
  - `httpx 0.28.1`
  - `openai 1.59.4`
  - `fastapi 0.115.6`
  - `uvicorn 0.34.0`

## Importer Behavior

- Daily Amical imports use:

```bash
python3 ../experiments/amical/import_amical_db.py \
  --date YYYY-MM-DD \
  --provider openai \
  --model gpt-4.1-mini
```

- `import_amical_db.py` was patched on `2026-04-15` to treat `23505 duplicate key`
  during `zerotouch_sessions` insert as idempotent success.
- Reason: Supabase may return `Server disconnected` after the insert already committed.
  The retry then sees the duplicate primary key.

## Current DB Notes

- Project ref: `qvtlwotzuzbavrzqhyvt` (`WatchMe`)
- `zerotouch_wiki_pages` does **not** exist yet as of `2026-04-15`
- `Ingest / Wiki` viewer tabs therefore show "not generated yet" state
