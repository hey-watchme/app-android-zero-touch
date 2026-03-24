"""
Process 2 topic evaluation:
- cards are shown immediately from zerotouch_sessions
- topics are generated in batch after 60s without new utterances per device
"""

from __future__ import annotations

import json
import os
import re
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple

from supabase import Client

from services.llm_providers import LLMFactory, get_current_llm
from services.prompts import build_topic_batch_group_prompt


SESSION_TABLE = "zerotouch_sessions"
TOPIC_TABLE = os.getenv("TOPIC_TABLE", "zerotouch_conversation_topics")
RUN_TABLE = "zerotouch_topic_evaluation_runs"
DEVICE_SETTINGS_TABLE = "zerotouch_device_settings"

TOPIC_EVAL_PENDING = "pending"
TOPIC_EVAL_PROCESSING = "processing"
TOPIC_EVAL_GROUPED = "grouped"
TOPIC_EVAL_NEEDS_RETRY = "needs_retry"

RUN_STATUS_PROCESSING = "processing"
RUN_STATUS_COMPLETED = "completed"
RUN_STATUS_NEEDS_RETRY = "needs_retry"

GROUPING_STATUS_GROUPED = "grouped"
GROUPING_METHOD_LLM_BATCH = "llm_batch_60s_idle"
GROUPING_METHOD_FALLBACK_BATCH = "fallback_batch_60s_idle"

DEFAULT_IDLE_SECONDS = 60


def _now_utc() -> datetime:
    return datetime.now(timezone.utc)


def _iso_now() -> str:
    return _now_utc().isoformat()


def _parse_timestamp(value: Optional[str]) -> datetime:
    if not value:
        return _now_utc()
    text = str(value).strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except Exception:
        return _now_utc()
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def _extract_json(text: str) -> Optional[Dict[str, Any]]:
    if not text:
        return None
    cleaned = text.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?", "", cleaned).strip()
        cleaned = cleaned.rstrip("`").strip()

    candidates = [cleaned]
    match = re.search(r"\{[\s\S]*\}", cleaned)
    if match:
        candidates.append(match.group(0))

    for candidate in candidates:
        try:
            payload = json.loads(candidate)
            if isinstance(payload, dict):
                return payload
        except Exception:
            continue
    return None


def resolve_device_llm_service(
    supabase: Client,
    device_id: str,
    fallback_llm_service=None,
):
    if not device_id:
        return fallback_llm_service or get_current_llm()

    settings = (
        supabase.table(DEVICE_SETTINGS_TABLE)
        .select("llm_provider, llm_model")
        .eq("device_id", device_id)
        .single()
        .execute()
        .data
    )
    if not settings:
        return fallback_llm_service or get_current_llm()

    provider = (settings.get("llm_provider") or "").strip()
    model = (settings.get("llm_model") or "").strip()
    if not provider or not model:
        return fallback_llm_service or get_current_llm()

    try:
        return LLMFactory.create(provider, model)
    except Exception:
        return fallback_llm_service or get_current_llm()


def mark_session_for_topic_evaluation(
    session_id: str,
    supabase: Client,
) -> Dict[str, Any]:
    response = (
        supabase.table(SESSION_TABLE)
        .select("id, device_id, status, topic_id, transcription")
        .eq("id", session_id)
        .single()
        .execute()
    )
    session = response.data
    if not session:
        raise ValueError(f"Session not found: {session_id}")

    if session.get("topic_id"):
        return {
            "session_id": session_id,
            "device_id": session.get("device_id"),
            "queued": False,
            "reason": "already_grouped",
        }

    if session.get("status") != "transcribed":
        return {
            "session_id": session_id,
            "device_id": session.get("device_id"),
            "queued": False,
            "reason": "status_not_transcribed",
        }

    transcript = (session.get("transcription") or "").strip()
    if not transcript:
        supabase.table(SESSION_TABLE).update(
            {
                "topic_eval_status": TOPIC_EVAL_GROUPED,
                "topic_eval_error": "empty_transcription_skipped",
                "updated_at": _iso_now(),
            }
        ).eq("id", session_id).execute()
        return {
            "session_id": session_id,
            "device_id": session.get("device_id"),
            "queued": False,
            "reason": "empty_transcription",
        }

    supabase.table(SESSION_TABLE).update(
        {
            "topic_eval_status": TOPIC_EVAL_PENDING,
            "topic_eval_run_id": None,
            "topic_eval_marked_at": _iso_now(),
            "topic_eval_error": None,
            "updated_at": _iso_now(),
        }
    ).eq("id", session_id).execute()

    return {
        "session_id": session_id,
        "device_id": session.get("device_id"),
        "queued": True,
        "reason": "queued_for_process2",
    }


def _has_processing_run(supabase: Client, device_id: str) -> bool:
    rows = (
        supabase.table(RUN_TABLE)
        .select("id")
        .eq("device_id", device_id)
        .eq("run_status", RUN_STATUS_PROCESSING)
        .limit(1)
        .execute()
        .data
        or []
    )
    return bool(rows)


def _fetch_pending_sessions(
    supabase: Client,
    device_id: str,
    max_sessions: int,
) -> List[Dict[str, Any]]:
    return (
        supabase.table(SESSION_TABLE)
        .select(
            "id, device_id, status, transcription, recorded_at, created_at, "
            "topic_id, topic_eval_status"
        )
        .eq("device_id", device_id)
        .is_("topic_id", "null")
        .eq("status", "transcribed")
        .in_("topic_eval_status", [TOPIC_EVAL_PENDING, TOPIC_EVAL_NEEDS_RETRY])
        .order("recorded_at", desc=False)
        .limit(max_sessions)
        .execute()
        .data
        or []
    )


def _create_run(
    supabase: Client,
    device_id: str,
    sessions: List[Dict[str, Any]],
) -> Dict[str, Any]:
    first = sessions[0]
    last = sessions[-1]
    payload = {
        "device_id": device_id,
        "run_status": RUN_STATUS_PROCESSING,
        "trigger_type": "idle_60s_no_utterance",
        "session_count": len(sessions),
        "first_session_at": first.get("recorded_at") or first.get("created_at"),
        "last_session_at": last.get("recorded_at") or last.get("created_at"),
        "first_session_id": first.get("id"),
        "last_session_id": last.get("id"),
        "triggered_at": _iso_now(),
        "locked_at": _iso_now(),
        "updated_at": _iso_now(),
    }
    rows = supabase.table(RUN_TABLE).insert(payload).execute().data or []
    if not rows:
        raise RuntimeError("Failed to create topic evaluation run")
    return rows[0]


def _fallback_group(sessions: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    if not sessions:
        return []
    title_base = (sessions[0].get("transcription") or "").strip()
    title = title_base[:28].strip() if title_base else "Conversation"
    if not title:
        title = "Conversation"
    summary_texts = [(s.get("transcription") or "").strip() for s in sessions if (s.get("transcription") or "").strip()]
    summary = " ".join(summary_texts[:2])[:220] if summary_texts else ""
    return [
        {
            "title": title,
            "summary": summary,
            "session_ids": [s["id"] for s in sessions],
        }
    ]


def _normalize_topics(
    sessions: List[Dict[str, Any]],
    llm_topics: Any,
) -> List[Dict[str, Any]]:
    valid_ids = [s["id"] for s in sessions]
    valid_id_set = set(valid_ids)
    assigned: set[str] = set()
    normalized: List[Dict[str, Any]] = []

    if isinstance(llm_topics, list):
        for topic in llm_topics:
            if not isinstance(topic, dict):
                continue
            raw_ids = topic.get("session_ids")
            if not isinstance(raw_ids, list):
                continue
            topic_ids: List[str] = []
            for session_id in raw_ids:
                text_id = str(session_id).strip()
                if not text_id or text_id not in valid_id_set or text_id in assigned:
                    continue
                assigned.add(text_id)
                topic_ids.append(text_id)
            if not topic_ids:
                continue
            title = str(topic.get("title") or "").strip() or "Conversation"
            summary = str(topic.get("summary") or "").strip()
            normalized.append(
                {
                    "title": title[:80],
                    "summary": summary[:500],
                    "session_ids": topic_ids,
                }
            )

    unassigned = [session_id for session_id in valid_ids if session_id not in assigned]
    if unassigned:
        session_by_id = {s["id"]: s for s in sessions}
        overflow_sessions = [session_by_id[session_id] for session_id in unassigned]
        fallback = _fallback_group(overflow_sessions)[0]
        normalized.append(fallback)

    return normalized if normalized else _fallback_group(sessions)


def _group_sessions_with_llm(
    sessions: List[Dict[str, Any]],
    llm_service=None,
) -> Tuple[List[Dict[str, Any]], str, Optional[str]]:
    if not llm_service:
        return _fallback_group(sessions), GROUPING_METHOD_FALLBACK_BATCH, None

    prompt = build_topic_batch_group_prompt(sessions)
    raw_output = llm_service.generate(prompt)
    payload = _extract_json(raw_output) or {}
    topics = _normalize_topics(sessions=sessions, llm_topics=payload.get("topics"))
    return topics, GROUPING_METHOD_LLM_BATCH, raw_output


def _persist_topics_for_run(
    supabase: Client,
    run_id: str,
    device_id: str,
    sessions: List[Dict[str, Any]],
    grouped_topics: List[Dict[str, Any]],
    grouping_method: str,
) -> int:
    now_iso = _iso_now()
    session_map = {row["id"]: row for row in sessions}
    created_topics = 0

    for grouped in grouped_topics:
        session_ids = [session_id for session_id in grouped.get("session_ids", []) if session_id in session_map]
        if not session_ids:
            continue
        group_rows = [session_map[session_id] for session_id in session_ids]
        start_at = min(_parse_timestamp(r.get("recorded_at") or r.get("created_at")) for r in group_rows)
        end_at = max(_parse_timestamp(r.get("recorded_at") or r.get("created_at")) for r in group_rows)

        title = str(grouped.get("title") or "").strip() or "Conversation"
        summary = str(grouped.get("summary") or "").strip()
        if not summary:
            texts = [(row.get("transcription") or "").strip() for row in group_rows if (row.get("transcription") or "").strip()]
            summary = " ".join(texts[:2])[:220]

        topic_payload = {
            "device_id": device_id,
            "topic_status": "finalized",
            "start_at": start_at.isoformat(),
            "end_at": end_at.isoformat(),
            "last_utterance_at": end_at.isoformat(),
            "utterance_count": len(session_ids),
            "live_title": title,
            "live_summary": summary or None,
            "final_title": title,
            "final_summary": summary or None,
            "finalized_at": now_iso,
            "created_at": now_iso,
            "updated_at": now_iso,
        }
        topic_rows = supabase.table(TOPIC_TABLE).insert(topic_payload).execute().data or []
        if not topic_rows:
            raise RuntimeError("Failed to create topic in Process 2")
        topic_id = topic_rows[0]["id"]
        created_topics += 1

        supabase.table(SESSION_TABLE).update(
            {
                "topic_id": topic_id,
                "grouping_status": GROUPING_STATUS_GROUPED,
                "grouping_method": grouping_method,
                "topic_assigned_at": now_iso,
                "topic_eval_status": TOPIC_EVAL_GROUPED,
                "topic_eval_error": None,
                "updated_at": now_iso,
            }
        ).in_("id", session_ids).execute()

    supabase.table(RUN_TABLE).update(
        {
            "run_status": RUN_STATUS_COMPLETED,
            "completed_at": _iso_now(),
            "updated_at": _iso_now(),
        }
    ).eq("id", run_id).execute()

    return created_topics


def _mark_run_retry(
    supabase: Client,
    run_id: str,
    error: Exception,
) -> None:
    error_message = str(error)[:1000]
    now_iso = _iso_now()
    supabase.table(SESSION_TABLE).update(
        {
            "topic_eval_status": TOPIC_EVAL_NEEDS_RETRY,
            "topic_eval_error": error_message,
            "updated_at": now_iso,
        }
    ).eq("topic_eval_run_id", run_id).execute()

    supabase.table(RUN_TABLE).update(
        {
            "run_status": RUN_STATUS_NEEDS_RETRY,
            "completed_at": now_iso,
            "error_code": "evaluation_failed",
            "error_message": error_message,
            "updated_at": now_iso,
        }
    ).eq("id", run_id).execute()


def process_pending_topics_for_device(
    supabase: Client,
    device_id: str,
    llm_service=None,
    idle_seconds: int = DEFAULT_IDLE_SECONDS,
    max_sessions: int = 200,
    force: bool = False,
) -> Dict[str, Any]:
    if not device_id:
        raise ValueError("device_id is required")

    if _has_processing_run(supabase=supabase, device_id=device_id):
        return {
            "device_id": device_id,
            "ready": False,
            "reason": "processing_run_exists",
        }

    sessions = _fetch_pending_sessions(
        supabase=supabase,
        device_id=device_id,
        max_sessions=max_sessions,
    )
    non_empty_sessions = [row for row in sessions if (row.get("transcription") or "").strip()]
    empty_session_ids = [row["id"] for row in sessions if not (row.get("transcription") or "").strip()]
    if empty_session_ids:
        supabase.table(SESSION_TABLE).update(
            {
                "topic_eval_status": TOPIC_EVAL_GROUPED,
                "topic_eval_error": "empty_transcription_skipped",
                "updated_at": _iso_now(),
            }
        ).in_("id", empty_session_ids).execute()
    sessions = non_empty_sessions
    if not sessions:
        return {
            "device_id": device_id,
            "ready": False,
            "reason": "no_pending_sessions",
        }

    latest = _parse_timestamp(sessions[-1].get("recorded_at") or sessions[-1].get("created_at"))
    idle_elapsed = (_now_utc() - latest).total_seconds()
    if not force and idle_elapsed < idle_seconds:
        return {
            "device_id": device_id,
            "ready": False,
            "reason": "idle_threshold_not_reached",
            "idle_elapsed_seconds": int(idle_elapsed),
            "idle_threshold_seconds": idle_seconds,
            "pending_count": len(sessions),
        }

    run = _create_run(
        supabase=supabase,
        device_id=device_id,
        sessions=sessions,
    )
    run_id = run["id"]
    session_ids = [session["id"] for session in sessions]
    now_iso = _iso_now()
    supabase.table(SESSION_TABLE).update(
        {
            "topic_eval_status": TOPIC_EVAL_PROCESSING,
            "topic_eval_run_id": run_id,
            "topic_eval_error": None,
            "updated_at": now_iso,
        }
    ).in_("id", session_ids).execute()

    try:
        grouped_topics, grouping_method, raw_output = _group_sessions_with_llm(
            sessions=sessions,
            llm_service=llm_service,
        )
        if raw_output:
            supabase.table(RUN_TABLE).update(
                {
                    "llm_response_json": {"raw_output": raw_output},
                    "updated_at": _iso_now(),
                }
            ).eq("id", run_id).execute()

        created_topics = _persist_topics_for_run(
            supabase=supabase,
            run_id=run_id,
            device_id=device_id,
            sessions=sessions,
            grouped_topics=grouped_topics,
            grouping_method=grouping_method,
        )
    except Exception as error:
        _mark_run_retry(
            supabase=supabase,
            run_id=run_id,
            error=error,
        )
        return {
            "device_id": device_id,
            "ready": True,
            "run_id": run_id,
            "status": RUN_STATUS_NEEDS_RETRY,
            "error": str(error),
            "pending_count": len(sessions),
        }

    return {
        "device_id": device_id,
        "ready": True,
        "run_id": run_id,
        "status": RUN_STATUS_COMPLETED,
        "pending_count": len(sessions),
        "created_topics": created_topics,
    }
