"""
Import one day of Amical transcriptions directly from the local SQLite DB.

Reads from ~/Library/Application Support/Amical/amical.db and inserts
into ZeroTouch (Supabase) as transcribed sessions, then runs the full
topic-finalize / scoring / annotation pipeline.

Usage (run from backend/ directory):
    python3 ../experiments/amical/import_amical_db.py \
        --date 2026-03-02 \
        [--workspace-id 6cbaeb05-9de6-4127-8b0a-dc0e46ac4046] \
        [--provider openai] [--model gpt-4.1-mini] \
        [--idle-seconds 300] \
        [--dry-run]

Each date gets its own device_id (amical-db-YYYY-MM-DD) for clean isolation.
Re-running the same date is safe: existing sessions are detected and skipped.
"""

from __future__ import annotations

import argparse
import json
import os
import sqlite3
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

SCRIPT_DIR = Path(__file__).resolve().parent
BACKEND_DIR = SCRIPT_DIR.parent.parent / "backend"
DEFAULT_AMICAL_DB = Path.home() / "Library/Application Support/Amical/amical.db"
DEFAULT_WORKSPACE_ID = "6cbaeb05-9de6-4127-8b0a-dc0e46ac4046"
DEFAULT_BACKEND_ENV = BACKEND_DIR / ".env"

DEVICE_TABLE = "zerotouch_devices"
SESSION_TABLE = "zerotouch_sessions"
WORKSPACE_TABLE = "zerotouch_workspaces"


# ---------------------------------------------------------------------------
# Bootstrap
# ---------------------------------------------------------------------------

def bootstrap_backend_env(env_path: Path) -> None:
    if not env_path.exists():
        raise SystemExit(f"Backend .env not found: {env_path}")
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())
    sys.path.insert(0, str(BACKEND_DIR))


def create_supabase_from_env():
    from supabase import create_client  # type: ignore
    return create_client(os.environ["SUPABASE_URL"], os.environ["SUPABASE_SERVICE_ROLE_KEY"])


# ---------------------------------------------------------------------------
# Amical SQLite reader
# ---------------------------------------------------------------------------

def load_utterances_for_date(amical_db: Path, date: str) -> List[Dict[str, Any]]:
    """
    Read transcriptions for a single date from the Amical SQLite DB.
    Returns list of dicts sorted by timestamp.
    """
    if not amical_db.exists():
        raise SystemExit(f"Amical DB not found: {amical_db}")

    conn = sqlite3.connect(str(amical_db))
    conn.row_factory = sqlite3.Row
    try:
        rows = conn.execute(
            """
            SELECT id, text, timestamp, language, duration, confidence
            FROM transcriptions
            WHERE date(timestamp, 'unixepoch', 'localtime') = ?
              AND text IS NOT NULL AND trim(text) != ''
            ORDER BY timestamp, id
            """,
            (date,),
        ).fetchall()
    finally:
        conn.close()

    result = []
    for row in rows:
        text = (row["text"] or "").strip()
        if not text:
            continue
        ts = datetime.fromtimestamp(row["timestamp"], tz=timezone.utc).astimezone()
        result.append({
            "utterance_id": str(row["id"]),
            "timestamp": ts,
            "text": text,
            "language": row["language"] or None,
            "duration_seconds": row["duration"] / 1000.0 if row["duration"] else None,
        })
    return result


# ---------------------------------------------------------------------------
# Supabase helpers (adapted from import_to_zerotouch.py)
# ---------------------------------------------------------------------------

def execute_with_retry(fn, label: str = "", retries: int = 3, delay: float = 1.5):
    for attempt in range(retries):
        try:
            return fn()
        except Exception as exc:
            if attempt + 1 >= retries:
                raise
            print(f"  [retry {attempt+1}/{retries}] {label}: {exc}")
            time.sleep(delay)


def ensure_workspace_exists(supabase, workspace_id: str) -> None:
    rows = execute_with_retry(
        lambda: supabase.table(WORKSPACE_TABLE).select("id").eq("id", workspace_id).limit(1).execute().data or [],
        label=f"workspace lookup {workspace_id}",
    )
    if not rows:
        raise SystemExit(f"Workspace not found: {workspace_id}")


def ensure_device(supabase, workspace_id: str, device_id: str, date: str, dry_run: bool) -> Dict[str, Any]:
    rows = execute_with_retry(
        lambda: supabase.table(DEVICE_TABLE).select("*").eq("device_id", device_id).limit(1).execute().data or [],
        label=f"device lookup {device_id}",
    )
    if rows:
        return rows[0]

    payload = {
        "workspace_id": workspace_id,
        "device_id": device_id,
        "display_name": "Amical Kaya (accumulation test)",
        "device_kind": "virtual_device",
        "source_type": "amical_transcriptions",
        "platform": "macos",
        "context_note": f"Imported directly from Amical SQLite DB for {date}",
        "is_virtual": True,
        "is_active": True,
        "created_at": datetime.now().astimezone().isoformat(),
        "updated_at": datetime.now().astimezone().isoformat(),
    }
    if dry_run:
        return payload

    inserted = execute_with_retry(
        lambda: supabase.table(DEVICE_TABLE).insert(payload).execute().data or [],
        label=f"device insert {device_id}",
    )
    return inserted[0] if inserted else payload


def find_existing_session(supabase, device_id: str, recorded_at: str, text: str) -> Optional[str]:
    rows = execute_with_retry(
        lambda: supabase.table(SESSION_TABLE)
            .select("id")
            .eq("device_id", device_id)
            .eq("recorded_at", recorded_at)
            .eq("transcription", text[:500])
            .limit(1)
            .execute()
            .data or [],
        label=f"existing session lookup",
    )
    return rows[0]["id"] if rows else None


def build_session_payload(
    utt: Dict[str, Any],
    workspace_id: str,
    device_id: str,
    import_run_id: str,
) -> Dict[str, Any]:
    recorded_at = utt["timestamp"].isoformat()
    duration = int(round(utt["duration_seconds"])) if utt["duration_seconds"] else None
    return {
        "id": str(uuid.uuid4()),
        "device_id": device_id,
        "workspace_id": workspace_id,
        "s3_audio_path": None,
        "duration_seconds": duration,
        "transcription": utt["text"],
        "transcription_metadata": {
            "source": "amical_db",
            "utterance_id": utt["utterance_id"],
            "language": utt["language"],
            "import_run_id": import_run_id,
            "imported_via": "experiments/amical/import_amical_db.py",
            "imported_at": datetime.now().astimezone().isoformat(),
        },
        "status": "transcribed",
        "error_message": None,
        "recorded_at": recorded_at,
        "created_at": recorded_at,
        "updated_at": recorded_at,
        "grouping_status": "ungrouped",
        "topic_eval_status": "pending",
        "topic_eval_marked_at": recorded_at,
    }


def insert_session_idempotent(
    supabase,
    payload: Dict[str, Any],
    utterance_id: str,
) -> str:
    """
    Insert a session row, but treat duplicate primary-key errors as success.

    This recovers the common case where Supabase disconnects after commit:
    the first insert already succeeded, and the retry only sees 23505.
    """

    def _insert():
        try:
            supabase.table(SESSION_TABLE).insert(payload).execute()
            return payload["id"]
        except Exception as exc:
            message = str(exc)
            if (
                "duplicate key value violates unique constraint" in message
                and "23505" in message
            ):
                return payload["id"]
            raise

    return execute_with_retry(
        _insert,
        label=f"session insert {utterance_id}",
    )


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--date", required=True, help="Date to import (YYYY-MM-DD)")
    parser.add_argument("--workspace-id", default=DEFAULT_WORKSPACE_ID)
    parser.add_argument("--device-id", default="amical-db-test", help="Device ID for accumulation (default: amical-db-test)")
    parser.add_argument("--amical-db", default=str(DEFAULT_AMICAL_DB), help="Path to Amical SQLite DB")
    parser.add_argument("--provider", default="openai", help="LLM provider")
    parser.add_argument("--model", default="gpt-4.1-mini", help="LLM model")
    parser.add_argument("--idle-seconds", type=int, default=300, help="Gap (seconds) to trigger topic finalize (default: 300)")
    parser.add_argument("--backend-env", default=str(DEFAULT_BACKEND_ENV))
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def annotate_pending_topics(supabase, device_id: str, llm, date: str) -> None:
    """
    Post-import step: annotate any topics that finished scoring but
    whose annotation was skipped due to a transient error (distillation_status='scored').
    Only processes topics whose conversation started on `date`.
    """
    from services.topic_annotator import annotate_topic  # type: ignore

    rows = execute_with_retry(
        lambda: (
            supabase.table("zerotouch_conversation_topics")
            .select("id, final_title, importance_level")
            .eq("device_id", device_id)
            .eq("distillation_status", "scored")
            .gte("importance_level", 3)
            .gte("start_at", f"{date}T00:00:00+00:00")
            .lt("start_at", f"{date}T23:59:59+00:00")
            .execute()
            .data or []
        ),
        label="pending annotation lookup",
    )

    if not rows:
        print("  [annotation] all topics already annotated")
        return

    print(f"\n  [annotation retry] {len(rows)} topics need annotation")
    for row in rows:
        topic_id = row["id"]
        title = (row.get("final_title") or "")[:50]
        result = execute_with_retry(
            lambda tid=topic_id: annotate_topic(
                supabase=supabase,
                topic_id=tid,
                llm_service=llm,
                force=False,
            ),
            label=f"annotate {topic_id}",
        )
        if result.get("annotated"):
            print(f"    -> {title} : {result.get('fact_count')} facts")
        else:
            print(f"    -> {title} : skipped ({result.get('reason')})")
        time.sleep(0.3)


def main() -> None:
    args = parse_args()
    bootstrap_backend_env(Path(args.backend_env))

    from services.llm_providers import LLMFactory  # type: ignore
    from services.topic_manager_process2 import (  # type: ignore
        assign_session_to_active_topic,
        finalize_active_topic_for_device,
    )

    device_id = args.device_id or f"amical-db-{args.date}"
    amical_db = Path(args.amical_db)

    utterances = load_utterances_for_date(amical_db, args.date)
    if not utterances:
        raise SystemExit(f"No transcriptions found for {args.date} in {amical_db}")

    print(json.dumps({
        "mode": "dry_run" if args.dry_run else "import",
        "date": args.date,
        "device_id": device_id,
        "workspace_id": args.workspace_id,
        "utterance_count": len(utterances),
        "time_range": {
            "start": utterances[0]["timestamp"].isoformat(),
            "end": utterances[-1]["timestamp"].isoformat(),
        },
        "model": f"{args.provider}/{args.model}",
    }, ensure_ascii=False, indent=2))

    supabase = create_supabase_from_env()
    ensure_workspace_exists(supabase, args.workspace_id)
    ensure_device(supabase, args.workspace_id, device_id, args.date, args.dry_run)

    llm = LLMFactory.create(provider=args.provider, model=args.model) if not args.dry_run else None
    import_run_id = str(uuid.uuid4())

    inserted = skipped = assigned = finalize_count = 0
    topics_created: List[Dict[str, Any]] = []
    # Track whether any new session was inserted in the current gap window
    new_in_window = 0

    for i, utt in enumerate(utterances):
        next_utt = utterances[i + 1] if i + 1 < len(utterances) else None
        recorded_at = utt["timestamp"].isoformat()

        if args.dry_run:
            inserted += 1
            new_in_window += 1
        else:
            # Skip if already imported
            existing_id = find_existing_session(supabase, device_id, recorded_at, utt["text"][:500])
            if existing_id:
                skipped += 1
                session_id = existing_id
            else:
                payload = build_session_payload(utt, args.workspace_id, device_id, import_run_id)
                session_id = insert_session_idempotent(
                    supabase=supabase,
                    payload=payload,
                    utterance_id=utt["utterance_id"],
                )
                inserted += 1
                new_in_window += 1

            result = execute_with_retry(
                lambda sid=session_id: assign_session_to_active_topic(session_id=sid, supabase=supabase),
                label=f"topic assign {session_id}",
            )
            if result.get("assigned") or result.get("reason") == "already_assigned":
                assigned += 1

        # Detect gap boundary
        should_finalize = False
        boundary_reason = None
        if next_utt is None:
            should_finalize = True
            boundary_reason = "manual"
        else:
            gap = (next_utt["timestamp"] - utt["timestamp"]).total_seconds()
            if gap > args.idle_seconds:
                should_finalize = True
                boundary_reason = "idle_timeout"

        if should_finalize:
            if args.dry_run:
                print(f"  [dry-run] Would finalize topic after utterance {i+1} ({boundary_reason})")
                finalize_count += 1
            elif new_in_window == 0:
                # All sessions in this window already existed — topic already finalized, skip
                pass
            else:
                result = execute_with_retry(
                    lambda: finalize_active_topic_for_device(
                        supabase=supabase,
                        device_id=device_id,
                        llm_service=llm,
                        idle_seconds=args.idle_seconds,
                        force=True,
                        boundary_reason=boundary_reason,
                    ),
                    label=f"finalize after utt {i+1}",
                )
                if result:
                    finalize_count += 1
                    importance = result.get("importance_level")
                    title = result.get("final_title") or result.get("live_title") or ""
                    topics_created.append({
                        "topic_id": result.get("topic_id"),
                        "title": title[:50],
                        "importance": importance,
                    })
                    lvl = importance if importance is not None else "?"
                    print(f"  [topic {finalize_count}] Lv.{lvl} {title[:50]}")
            # Reset window counter after each boundary
            new_in_window = 0

    # Post-import: retry annotation for any topics that failed during finalize
    if not args.dry_run and llm is not None:
        annotate_pending_topics(supabase, device_id, llm, args.date)

    print()
    print("=" * 50)
    print(json.dumps({
        "date": args.date,
        "device_id": device_id,
        "utterances": len(utterances),
        "inserted": inserted,
        "skipped_existing": skipped,
        "assigned": assigned,
        "topics_finalized": finalize_count,
        "dry_run": args.dry_run,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
