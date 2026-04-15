#!/usr/bin/env python3
"""Import normalized Amical artifacts into ZeroTouch as transcribed sessions.

This is an experimental importer kept outside the runtime backend path.
It writes ZeroTouch-compatible rows into Supabase and then reuses the
existing Topic / scoring / annotation services to process them.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Optional, Sequence

from supabase import Client, create_client


SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent.parent
BACKEND_DIR = PROJECT_ROOT / "backend"
DEFAULT_ARTIFACTS_ROOT = SCRIPT_DIR / "artifacts"
DEFAULT_BACKEND_ENV = BACKEND_DIR / ".env"
DEVICE_TABLE = "zerotouch_devices"
SESSION_TABLE = "zerotouch_sessions"
WORKSPACE_TABLE = "zerotouch_workspaces"
TOPIC_TABLE = "zerotouch_conversation_topics"


@dataclass
class DatasetWindow:
    dataset_id: str
    dataset_dir: Path
    start_at: datetime
    end_at: datetime


@dataclass
class ImportRow:
    dataset_id: str
    dataset_dir: Path
    utterance_id: str
    timestamp: datetime
    text: str
    language: Optional[str]
    duration_seconds: Optional[float]
    raw_ref: Optional[str]
    source_session_id: Optional[str]
    audio_file_path: Optional[str]


@dataclass
class ExistingSessionMatch:
    id: str
    topic_id: Optional[str]
    status: Optional[str]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--dataset-dir",
        action="append",
        default=[],
        help="Artifact dataset directory containing 00_source_window.json and 02_raw_corpus.jsonl. Can be passed multiple times.",
    )
    parser.add_argument(
        "--daily-rollup-date",
        help="Load dataset ids from artifacts/daily-rollups/<YYYY-MM-DD>/08_daily_rollup.json",
    )
    parser.add_argument(
        "--artifacts-root",
        default=str(DEFAULT_ARTIFACTS_ROOT),
        help="Root directory for Amical artifacts",
    )
    parser.add_argument(
        "--workspace-id",
        required=True,
        help="Target ZeroTouch workspace id",
    )
    parser.add_argument(
        "--device-id",
        required=True,
        help="Target virtual device_id for imported Amical data",
    )
    parser.add_argument(
        "--display-name",
        default=None,
        help="Display name used when creating the virtual device",
    )
    parser.add_argument(
        "--platform",
        default="amical",
        help="Platform label for the virtual device row",
    )
    parser.add_argument(
        "--context-note",
        default=None,
        help="Optional context note stored on the virtual device row",
    )
    parser.add_argument(
        "--create-device-if-missing",
        action="store_true",
        help="Create a zerotouch_devices row when the target device_id does not exist",
    )
    parser.add_argument(
        "--append-to-existing-device",
        action="store_true",
        help="Allow importing into a device that already has zerotouch_sessions rows",
    )
    parser.add_argument(
        "--skip-existing-sessions",
        action="store_true",
        help="Skip rows that already exist for the same device_id + recorded_at + transcription",
    )
    parser.add_argument(
        "--allow-overlap",
        action="store_true",
        help="Allow overlapping dataset windows. Disabled by default to avoid duplicate imports.",
    )
    parser.add_argument(
        "--idle-seconds",
        type=int,
        default=30,
        help="Gap threshold used to force topic finalize while replaying historical utterances",
    )
    parser.add_argument(
        "--provider",
        default=None,
        help="Override LLM provider used during topic finalize / distillation",
    )
    parser.add_argument(
        "--model",
        default=None,
        help="Override LLM model used during topic finalize / distillation",
    )
    parser.add_argument(
        "--disable-llm",
        action="store_true",
        help="Disable LLM usage during finalize / scoring / annotation",
    )
    parser.add_argument(
        "--backend-env",
        default=str(DEFAULT_BACKEND_ENV),
        help="Path to backend .env file that contains Supabase / LLM credentials",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print the import plan without writing to Supabase",
    )
    return parser.parse_args()


def load_env_file(path: Path) -> None:
    if not path.exists():
        return
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line or line.startswith("export "):
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())


def load_json(path: Path) -> Dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def load_jsonl(path: Path) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def parse_iso_datetime(value: str) -> datetime:
    text = str(value or "").strip()
    if not text:
        raise ValueError("timestamp is empty")
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    return datetime.fromisoformat(text)


def normalize_text(value: Any) -> str:
    return " ".join(str(value or "").split()).strip()


def bootstrap_backend_env(env_path: Path) -> None:
    load_env_file(env_path)
    if str(BACKEND_DIR) not in sys.path:
        sys.path.insert(0, str(BACKEND_DIR))


def create_supabase_from_env() -> Client:
    url = os.getenv("SUPABASE_URL")
    service_role_key = os.getenv("SUPABASE_SERVICE_ROLE_KEY")
    if not url or not service_role_key:
        raise RuntimeError("SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY must be set")
    return create_client(url, service_role_key)


def execute_with_retry(
    operation: Callable[[], Any],
    *,
    label: str,
    max_attempts: int = 4,
    base_sleep_seconds: float = 1.5,
) -> Any:
    last_error: Optional[Exception] = None
    for attempt in range(1, max_attempts + 1):
        try:
            return operation()
        except Exception as error:
            last_error = error
            if attempt >= max_attempts:
                break
            sleep_seconds = base_sleep_seconds * attempt
            print(
                f"[Importer] {label} failed on attempt {attempt}/{max_attempts}: {error}. "
                f"Retrying in {sleep_seconds:.1f}s..."
            )
            time.sleep(sleep_seconds)
    assert last_error is not None
    raise last_error


def resolve_dataset_dirs(args: argparse.Namespace) -> List[Path]:
    artifacts_root = Path(args.artifacts_root).expanduser().resolve()
    dataset_dirs = [Path(value).expanduser().resolve() for value in args.dataset_dir]

    if args.daily_rollup_date:
        daily_rollup_path = artifacts_root / "daily-rollups" / args.daily_rollup_date / "08_daily_rollup.json"
        if not daily_rollup_path.exists():
            raise SystemExit(f"Daily rollup not found: {daily_rollup_path}")
        daily_rollup = load_json(daily_rollup_path)
        source_dataset_ids = daily_rollup.get("source_dataset_ids") or []
        if not isinstance(source_dataset_ids, list) or not source_dataset_ids:
            raise SystemExit(f"No source_dataset_ids found in {daily_rollup_path}")
        dataset_dirs.extend(artifacts_root / str(dataset_id) for dataset_id in source_dataset_ids)

    if not dataset_dirs:
        raise SystemExit("Provide at least one --dataset-dir or --daily-rollup-date")

    unique_dirs: List[Path] = []
    seen: set[str] = set()
    for path in dataset_dirs:
        key = str(path)
        if key in seen:
            continue
        seen.add(key)
        unique_dirs.append(path)
    return unique_dirs


def load_dataset_windows(dataset_dirs: Sequence[Path]) -> List[DatasetWindow]:
    windows: List[DatasetWindow] = []
    for dataset_dir in dataset_dirs:
        manifest_path = dataset_dir / "00_source_window.json"
        raw_corpus_path = dataset_dir / "02_raw_corpus.jsonl"
        if not manifest_path.exists():
            raise SystemExit(f"Missing manifest: {manifest_path}")
        if not raw_corpus_path.exists():
            raise SystemExit(f"Missing raw corpus: {raw_corpus_path}")
        manifest = load_json(manifest_path)
        dataset_id = normalize_text(manifest.get("dataset_id")) or dataset_dir.name
        start_at = parse_iso_datetime(str(manifest.get("window_start") or ""))
        end_at = parse_iso_datetime(str(manifest.get("window_end") or ""))
        windows.append(
            DatasetWindow(
                dataset_id=dataset_id,
                dataset_dir=dataset_dir,
                start_at=start_at,
                end_at=end_at,
            )
        )
    return sorted(windows, key=lambda item: (item.start_at, item.end_at, item.dataset_id))


def validate_non_overlapping(windows: Sequence[DatasetWindow], allow_overlap: bool) -> None:
    if allow_overlap:
        return
    previous: Optional[DatasetWindow] = None
    for current in windows:
        if previous and current.start_at < previous.end_at:
            raise SystemExit(
                "Overlapping dataset windows detected. "
                f"Use curated daily rollups, pass explicit non-overlapping --dataset-dir values, "
                f"or set --allow-overlap if duplication is intentional.\n"
                f"Previous: {previous.dataset_id}\n"
                f"Current: {current.dataset_id}"
            )
        previous = current


def load_import_rows(windows: Sequence[DatasetWindow]) -> List[ImportRow]:
    rows: List[ImportRow] = []
    for window in windows:
        for item in load_jsonl(window.dataset_dir / "02_raw_corpus.jsonl"):
            text = normalize_text(item.get("text"))
            if not text:
                continue
            rows.append(
                ImportRow(
                    dataset_id=window.dataset_id,
                    dataset_dir=window.dataset_dir,
                    utterance_id=normalize_text(item.get("utterance_id")) or str(uuid.uuid4()),
                    timestamp=parse_iso_datetime(str(item.get("timestamp") or "")),
                    text=text,
                    language=normalize_text(item.get("language")) or None,
                    duration_seconds=_to_optional_float(item.get("duration_seconds")),
                    raw_ref=normalize_text(item.get("raw_ref")) or None,
                    source_session_id=normalize_text(item.get("session_id")) or None,
                    audio_file_path=normalize_text(item.get("audio_file_path")) or None,
                )
            )
    return sorted(rows, key=lambda row: (row.timestamp, row.dataset_id, row.utterance_id))


def _to_optional_float(value: Any) -> Optional[float]:
    if value is None or value == "":
        return None
    try:
        return float(value)
    except Exception:
        return None


def ensure_workspace_exists(supabase: Client, workspace_id: str) -> None:
    rows = execute_with_retry(
        lambda: (
            supabase.table(WORKSPACE_TABLE)
            .select("id")
            .eq("id", workspace_id)
            .limit(1)
            .execute()
            .data
            or []
        ),
        label=f"workspace lookup {workspace_id}",
    )
    if not rows:
        raise SystemExit(f"Workspace not found: {workspace_id}")


def ensure_device(
    supabase: Client,
    workspace_id: str,
    device_id: str,
    display_name: Optional[str],
    platform: Optional[str],
    context_note: Optional[str],
    create_if_missing: bool,
    dry_run: bool,
) -> Dict[str, Any]:
    rows = execute_with_retry(
        lambda: (
            supabase.table(DEVICE_TABLE)
            .select("*")
            .eq("device_id", device_id)
            .limit(1)
            .execute()
            .data
            or []
        ),
        label=f"device lookup {device_id}",
    )
    if rows:
        device = rows[0]
        existing_workspace_id = normalize_text(device.get("workspace_id"))
        if existing_workspace_id and existing_workspace_id != workspace_id:
            raise SystemExit(
                f"Device {device_id} belongs to a different workspace.\n"
                f"expected={workspace_id}\nactual={existing_workspace_id}"
            )
        return device

    if not create_if_missing:
        raise SystemExit(
            f"Device not found: {device_id}\n"
            "Pass --create-device-if-missing to create a dedicated virtual Amical device."
        )

    payload = {
        "workspace_id": workspace_id,
        "device_id": device_id,
        "display_name": display_name or device_id,
        "device_kind": "virtual_device",
        "source_type": "amical_transcriptions",
        "platform": normalize_text(platform) or None,
        "context_note": normalize_text(context_note) or "Imported from Amical normalized artifacts",
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


def count_existing_sessions_for_device(supabase: Client, device_id: str) -> int:
    rows = execute_with_retry(
        lambda: (
            supabase.table(SESSION_TABLE)
            .select("id")
            .eq("device_id", device_id)
            .limit(1)
            .execute()
            .data
            or []
        ),
        label=f"session count lookup {device_id}",
    )
    return len(rows)


def assert_device_is_safe_to_import(
    supabase: Client,
    device_id: str,
    append_to_existing_device: bool,
) -> None:
    count = count_existing_sessions_for_device(supabase=supabase, device_id=device_id)
    if count and not append_to_existing_device:
        raise SystemExit(
            f"Target device already has existing sessions: {device_id}\n"
            "Use a fresh virtual device_id or pass --append-to-existing-device intentionally."
        )


def find_existing_session(
    supabase: Client,
    device_id: str,
    timestamp: datetime,
    text: str,
) -> Optional[ExistingSessionMatch]:
    rows = execute_with_retry(
        lambda: (
            supabase.table(SESSION_TABLE)
            .select("id, topic_id, status")
            .eq("device_id", device_id)
            .eq("recorded_at", timestamp.isoformat())
            .eq("transcription", text)
            .limit(1)
            .execute()
            .data
            or []
        ),
        label=f"existing session lookup {device_id} {timestamp.isoformat()}",
    )
    if not rows:
        return None
    row = rows[0]
    return ExistingSessionMatch(
        id=str(row.get("id")),
        topic_id=row.get("topic_id"),
        status=row.get("status"),
    )


def build_session_payload(
    row: ImportRow,
    workspace_id: str,
    device_id: str,
    import_run_id: str,
) -> Dict[str, Any]:
    recorded_at = row.timestamp.isoformat()
    now_iso = datetime.now().astimezone().isoformat()
    duration_seconds = None
    if row.duration_seconds is not None:
        duration_seconds = max(1, int(round(row.duration_seconds)))

    metadata = {
        "source": "amical",
        "source_type": "amical_transcriptions",
        "dataset_id": row.dataset_id,
        "utterance_id": row.utterance_id,
        "raw_ref": row.raw_ref,
        "source_session_id": row.source_session_id,
        "audio_file_path": row.audio_file_path,
        "language": row.language,
        "import_run_id": import_run_id,
        "imported_via": "experiments/amical/import_to_zerotouch.py",
        "imported_at": now_iso,
    }

    return {
        "id": str(uuid.uuid4()),
        "device_id": device_id,
        "workspace_id": workspace_id,
        "s3_audio_path": None,
        "duration_seconds": duration_seconds,
        "transcription": row.text,
        "transcription_metadata": metadata,
        "status": "transcribed",
        "error_message": None,
        "recorded_at": recorded_at,
        "created_at": recorded_at,
        "updated_at": recorded_at,
        "grouping_status": "ungrouped",
        "topic_eval_status": "pending",
        "topic_eval_marked_at": recorded_at,
    }


def maybe_finalize_after_row(
    *,
    current_row: ImportRow,
    next_row: Optional[ImportRow],
    idle_seconds: int,
    supabase: Client,
    device_id: str,
    llm_service: Any,
    finalize_active_topic_for_device_fn,
    dry_run: bool,
) -> Optional[Dict[str, Any]]:
    boundary_reason: Optional[str] = None
    if next_row is None:
        boundary_reason = "manual"
    else:
        gap_seconds = (next_row.timestamp - current_row.timestamp).total_seconds()
        if gap_seconds > idle_seconds:
            boundary_reason = "idle_timeout"

    if not boundary_reason:
        return None
    if dry_run:
        return {
            "dry_run": True,
            "device_id": device_id,
            "boundary_reason": boundary_reason,
            "after_utterance_id": current_row.utterance_id,
        }
    return execute_with_retry(
        lambda: finalize_active_topic_for_device_fn(
            supabase=supabase,
            device_id=device_id,
            llm_service=llm_service,
            idle_seconds=idle_seconds,
            force=True,
            boundary_reason=boundary_reason,
        ),
        label=f"topic finalize {device_id} after {current_row.utterance_id}",
    )


def build_import_summary(
    rows: Sequence[ImportRow],
    windows: Sequence[DatasetWindow],
) -> Dict[str, Any]:
    return {
        "dataset_count": len(windows),
        "dataset_ids": [window.dataset_id for window in windows],
        "utterance_count": len(rows),
        "start_at": rows[0].timestamp.isoformat() if rows else None,
        "end_at": rows[-1].timestamp.isoformat() if rows else None,
    }


def main() -> None:
    args = parse_args()
    backend_env_path = Path(args.backend_env).expanduser().resolve()
    bootstrap_backend_env(backend_env_path)

    from services.llm_providers import LLMFactory, get_current_llm  # type: ignore
    from services.topic_manager_process2 import (  # type: ignore
        assign_session_to_active_topic,
        finalize_active_topic_for_device,
        resolve_device_llm_service,
    )

    supabase = create_supabase_from_env()
    ensure_workspace_exists(supabase=supabase, workspace_id=args.workspace_id)

    dataset_dirs = resolve_dataset_dirs(args)
    windows = load_dataset_windows(dataset_dirs)
    validate_non_overlapping(windows=windows, allow_overlap=args.allow_overlap)
    import_rows = load_import_rows(windows=windows)
    if not import_rows:
        raise SystemExit("No utterances found in the selected datasets")

    device = ensure_device(
        supabase=supabase,
        workspace_id=args.workspace_id,
        device_id=args.device_id,
        display_name=args.display_name,
        platform=args.platform,
        context_note=args.context_note,
        create_if_missing=args.create_device_if_missing,
        dry_run=args.dry_run,
    )
    if not args.dry_run:
        assert_device_is_safe_to_import(
            supabase=supabase,
            device_id=args.device_id,
            append_to_existing_device=args.append_to_existing_device,
        )

    llm_service = None
    llm_warning: Optional[str] = None
    if not args.disable_llm:
        try:
            if args.provider or args.model:
                provider = args.provider or "openai"
                llm_service = LLMFactory.create(provider=provider, model=args.model)
            else:
                llm_service = resolve_device_llm_service(
                    supabase=supabase,
                    device_id=args.device_id,
                    fallback_llm_service=get_current_llm(),
                )
        except Exception as error:
            llm_service = None
            llm_warning = f"LLM disabled due to setup error: {error}"

    import_run_id = str(uuid.uuid4())
    summary = build_import_summary(rows=import_rows, windows=windows)

    print(json.dumps(
        {
            "mode": "dry_run" if args.dry_run else "import",
            "workspace_id": args.workspace_id,
            "device_id": args.device_id,
            "device_row": {
                "device_id": device.get("device_id"),
                "display_name": device.get("display_name"),
                "source_type": device.get("source_type"),
                "is_virtual": device.get("is_virtual"),
            },
            "summary": summary,
            "llm_warning": llm_warning,
        },
        ensure_ascii=False,
        indent=2,
    ))

    inserted_count = 0
    skipped_count = 0
    assigned_count = 0
    reused_existing_count = 0
    finalize_results: List[Dict[str, Any]] = []

    for index, row in enumerate(import_rows):
        next_row = import_rows[index + 1] if index + 1 < len(import_rows) else None
        session_id_for_assignment: Optional[str] = None

        if args.skip_existing_sessions and not args.dry_run:
            existing_session = find_existing_session(
                supabase=supabase,
                device_id=args.device_id,
                timestamp=row.timestamp,
                text=row.text,
            )
            if existing_session:
                skipped_count += 1
                reused_existing_count += 1
                session_id_for_assignment = existing_session.id

        payload = build_session_payload(
            row=row,
            workspace_id=args.workspace_id,
            device_id=args.device_id,
            import_run_id=import_run_id,
        )

        if args.dry_run:
            inserted_count += 1
        else:
            if session_id_for_assignment is None:
                execute_with_retry(
                    lambda: supabase.table(SESSION_TABLE).insert(payload).execute(),
                    label=f"session insert {row.utterance_id}",
                )
                inserted_count += 1
                session_id_for_assignment = payload["id"]

            assignment = execute_with_retry(
                lambda: assign_session_to_active_topic(
                    session_id=session_id_for_assignment,
                    supabase=supabase,
                ),
                label=f"topic assignment {session_id_for_assignment}",
            )
            if assignment.get("assigned") or assignment.get("reason") == "already_assigned":
                assigned_count += 1

        finalize_result = maybe_finalize_after_row(
            current_row=row,
            next_row=next_row,
            idle_seconds=max(1, args.idle_seconds),
            supabase=supabase,
            device_id=args.device_id,
            llm_service=llm_service,
            finalize_active_topic_for_device_fn=finalize_active_topic_for_device,
            dry_run=args.dry_run,
        )
        if finalize_result is not None:
            finalize_results.append(finalize_result)

    print(json.dumps(
        {
            "import_run_id": import_run_id,
            "device_id": args.device_id,
            "workspace_id": args.workspace_id,
            "dataset_ids": summary["dataset_ids"],
            "utterance_count": summary["utterance_count"],
            "inserted_count": inserted_count,
            "skipped_count": skipped_count,
            "reused_existing_count": reused_existing_count,
            "assigned_count": assigned_count,
            "finalize_count": len(finalize_results),
            "finalize_results": finalize_results,
            "llm_enabled": not args.disable_llm,
            "llm_warning": llm_warning,
            "dry_run": args.dry_run,
        },
        ensure_ascii=False,
        indent=2,
    ))


if __name__ == "__main__":
    main()
