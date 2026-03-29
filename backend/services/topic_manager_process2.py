"""
Process 2 topic evaluation:
- cards are shown immediately from zerotouch_sessions
- topics are finalized after 30s without new utterances per device
"""

from __future__ import annotations

import json
import threading
import os
import re
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple

from supabase import Client

from services.llm_providers import LLMFactory, get_current_llm
from services.prompts import build_topic_batch_group_prompt, build_topic_finalize_prompt
from services.topic_scorer import score_topic
from services.topic_annotator import annotate_topic
from services.workspace_registry import resolve_workspace_id_for_device


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
GROUPING_METHOD_LIVE_ACTIVE_TOPIC = "live_active_topic"

DEFAULT_IDLE_SECONDS = 30
VALID_BOUNDARY_REASONS = {"idle_timeout", "ambient_stopped", "manual", "legacy_repair"}
OPTIONAL_TOPIC_COLUMNS = {"live_description", "final_description", "boundary_reason", "workspace_id"}


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


def _error_mentions_missing_column(error: Exception, column: str) -> bool:
    message = str(error).lower()
    column_name = column.lower()
    return column_name in message and (
        "column" in message
        or "schema cache" in message
        or "could not find" in message
    )


def _insert_topic_with_compat(
    supabase: Client,
    payload: Dict[str, Any],
) -> List[Dict[str, Any]]:
    candidate = dict(payload)
    while True:
        try:
            return supabase.table(TOPIC_TABLE).insert(candidate).execute().data or []
        except Exception as error:
            missing = [
                column
                for column in OPTIONAL_TOPIC_COLUMNS
                if column in candidate and _error_mentions_missing_column(error, column)
            ]
            if not missing:
                raise
            for column in missing:
                candidate.pop(column, None)


def _update_topic_with_compat(
    supabase: Client,
    topic_id: str,
    payload: Dict[str, Any],
) -> None:
    candidate = dict(payload)
    while True:
        try:
            supabase.table(TOPIC_TABLE).update(candidate).eq("id", topic_id).execute()
            return
        except Exception as error:
            missing = [
                column
                for column in OPTIONAL_TOPIC_COLUMNS
                if column in candidate and _error_mentions_missing_column(error, column)
            ]
            if not missing:
                raise
            for column in missing:
                candidate.pop(column, None)


def resolve_device_llm_service(
    supabase: Client,
    device_id: str,
    fallback_llm_service=None,
):
    if not device_id:
        return fallback_llm_service or get_current_llm()

    try:
        rows = (
            supabase.table(DEVICE_SETTINGS_TABLE)
            .select("llm_provider, llm_model")
            .eq("device_id", device_id)
            .limit(1)
            .execute()
            .data
            or []
        )
    except Exception:
        return fallback_llm_service or get_current_llm()

    settings = rows[0] if rows else None
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


def _build_live_topic_title(transcript: str) -> str:
    text = (transcript or "").strip()
    if not text:
        return "Conversation"
    title = text[:28].strip()
    return title or "Conversation"


def _build_live_topic_summary(transcript: str) -> Optional[str]:
    text = (transcript or "").strip()
    if not text:
        return None
    return text[:220]


def _build_live_topic_description(transcript: str) -> Optional[str]:
    text = (transcript or "").strip()
    if not text:
        return None
    return text[:500]


def _get_active_topic(
    supabase: Client,
    device_id: str,
) -> Optional[Dict[str, Any]]:
    try:
        rows = (
            supabase.table(TOPIC_TABLE)
            .select(
                "id, device_id, topic_status, start_at, end_at, last_utterance_at, "
                "utterance_count, live_title, live_summary, live_description"
            )
            .eq("device_id", device_id)
            .eq("topic_status", "active")
            .order("last_utterance_at", desc=True)
            .limit(1)
            .execute()
            .data
            or []
        )
    except Exception as error:
        if not _error_mentions_missing_column(error, "live_description"):
            raise
        rows = (
            supabase.table(TOPIC_TABLE)
            .select(
                "id, device_id, topic_status, start_at, end_at, last_utterance_at, "
                "utterance_count, live_title, live_summary"
            )
            .eq("device_id", device_id)
            .eq("topic_status", "active")
            .order("last_utterance_at", desc=True)
            .limit(1)
            .execute()
            .data
            or []
        )
    return rows[0] if rows else None


def _create_active_topic(
    supabase: Client,
    device_id: str,
    event_ts: datetime,
    transcript: str,
    workspace_id: Optional[str] = None,
) -> Dict[str, Any]:
    now_iso = _iso_now()
    payload = {
        "device_id": device_id,
        "workspace_id": workspace_id,
        "topic_status": "active",
        "start_at": event_ts.isoformat(),
        "end_at": event_ts.isoformat(),
        "last_utterance_at": event_ts.isoformat(),
        "utterance_count": 0,
        "live_title": _build_live_topic_title(transcript),
        "live_summary": _build_live_topic_summary(transcript),
        "live_description": _build_live_topic_description(transcript),
        "created_at": now_iso,
        "updated_at": now_iso,
    }
    try:
        rows = _insert_topic_with_compat(supabase=supabase, payload=payload)
        if not rows:
            raise RuntimeError("Failed to create active topic")
        return rows[0]
    except Exception:
        # Another concurrent session may have created the active topic.
        existing = _get_active_topic(supabase=supabase, device_id=device_id)
        if existing:
            return existing
        raise


def _touch_active_topic_with_session(
    supabase: Client,
    topic: Dict[str, Any],
    event_ts: datetime,
    transcript: str,
) -> None:
    now_iso = _iso_now()
    current_end = _parse_timestamp(topic.get("end_at"))
    updated_end = max(current_end, event_ts).isoformat()
    current_count = int(topic.get("utterance_count") or 0)
    current_summary = topic.get("live_summary")
    current_description = topic.get("live_description")

    payload: Dict[str, Any] = {
        "end_at": updated_end,
        "last_utterance_at": event_ts.isoformat(),
        "utterance_count": current_count + 1,
        "updated_at": now_iso,
    }
    if not current_summary:
        payload["live_summary"] = _build_live_topic_summary(transcript)
    if not current_description:
        payload["live_description"] = _build_live_topic_description(transcript)

    _update_topic_with_compat(
        supabase=supabase,
        topic_id=topic["id"],
        payload=payload,
    )


def assign_session_to_active_topic(
    session_id: str,
    supabase: Client,
) -> Dict[str, Any]:
    response = (
        supabase.table(SESSION_TABLE)
        .select("id, device_id, workspace_id, status, topic_id, transcription, recorded_at, created_at")
        .eq("id", session_id)
        .single()
        .execute()
    )
    session = response.data
    if not session:
        raise ValueError(f"Session not found: {session_id}")

    device_id = session.get("device_id")
    if not device_id:
        raise ValueError(f"device_id is missing for session: {session_id}")

    if session.get("topic_id"):
        return {
            "session_id": session_id,
            "device_id": device_id,
            "topic_id": session.get("topic_id"),
            "assigned": False,
            "reason": "already_assigned",
        }

    if session.get("status") != "transcribed":
        return {
            "session_id": session_id,
            "device_id": device_id,
            "assigned": False,
            "reason": "status_not_transcribed",
        }

    transcript = (session.get("transcription") or "").strip()
    event_ts = _parse_timestamp(session.get("recorded_at") or session.get("created_at"))
    workspace_id = session.get("workspace_id") or resolve_workspace_id_for_device(
        supabase=supabase,
        device_id=device_id,
    )
    topic = _get_active_topic(supabase=supabase, device_id=device_id)
    created_new_topic = False
    if not topic:
        topic = _create_active_topic(
            supabase=supabase,
            device_id=device_id,
            event_ts=event_ts,
            transcript=transcript,
            workspace_id=workspace_id,
        )
        created_new_topic = True

    now_iso = _iso_now()
    supabase.table(SESSION_TABLE).update(
        {
            "topic_id": topic["id"],
            "grouping_status": GROUPING_STATUS_GROUPED,
            "grouping_method": GROUPING_METHOD_LIVE_ACTIVE_TOPIC,
            "topic_assigned_at": now_iso,
            "topic_eval_status": TOPIC_EVAL_GROUPED,
            "topic_eval_run_id": None,
            "topic_eval_marked_at": now_iso,
            "topic_eval_error": None,
            "updated_at": now_iso,
        }
    ).eq("id", session_id).execute()

    _touch_active_topic_with_session(
        supabase=supabase,
        topic=topic,
        event_ts=event_ts,
        transcript=transcript,
    )

    return {
        "session_id": session_id,
        "device_id": device_id,
        "topic_id": topic["id"],
        "assigned": True,
        "created_new_topic": created_new_topic,
        "reason": "assigned_to_active_topic",
    }


def _collect_topic_sessions(
    supabase: Client,
    topic_id: str,
    limit: int = 500,
) -> List[Dict[str, Any]]:
    return (
        supabase.table(SESSION_TABLE)
        .select("id, transcription, recorded_at, created_at")
        .eq("topic_id", topic_id)
        .order("created_at", desc=False)
        .limit(limit)
        .execute()
        .data
        or []
    )


def _normalize_boundary_reason(
    boundary_reason: Optional[str],
    force: bool,
) -> str:
    reason = (boundary_reason or "").strip().lower()
    if reason in VALID_BOUNDARY_REASONS:
        return reason
    if force:
        return "manual"
    return "idle_timeout"


def _build_finalize_defaults(
    topic: Dict[str, Any],
    sessions: List[Dict[str, Any]],
) -> Tuple[str, str, str]:
    texts = [
        (row.get("transcription") or "").strip()
        for row in sessions
        if (row.get("transcription") or "").strip()
    ]
    title = str(topic.get("live_title") or "").strip() or "Conversation"
    summary = str(topic.get("live_summary") or "").strip()
    if not summary:
        summary = " ".join(texts[:2])[:220] if texts else ""
    description = str(topic.get("live_description") or "").strip()
    if not description:
        description = " ".join(texts[:4])[:500] if texts else summary
    return title, summary, description


def _run_llm_topic_finalize(
    llm_service,
    sessions: List[Dict[str, Any]],
    fallback_title: str,
    fallback_summary: str,
    fallback_description: str,
) -> Tuple[str, str, str, bool]:
    texts = [
        (row.get("transcription") or "").strip()
        for row in sessions
        if (row.get("transcription") or "").strip()
    ]
    if not llm_service or not texts:
        return fallback_title, fallback_summary, fallback_description, False

    try:
        prompt = build_topic_finalize_prompt(texts)
        raw_output = llm_service.generate(prompt)
        payload = _extract_json(raw_output) or {}

        title = str(payload.get("final_title") or "").strip() or fallback_title
        summary = str(payload.get("final_summary") or "").strip() or fallback_summary
        description = str(
            payload.get("final_description")
            or payload.get("description")
            or ""
        ).strip()
        if not description:
            description = fallback_description or summary
        return title[:80], summary[:500], description[:500], True
    except Exception:
        return fallback_title, fallback_summary, fallback_description, False


def _extract_llm_metadata(llm_service) -> Tuple[Optional[str], Optional[str]]:
    model_name = str(getattr(llm_service, "model_name", "") or "").strip()
    if not model_name:
        return None, None
    if "/" in model_name:
        provider, model = model_name.split("/", 1)
        return provider or None, model or None
    return None, model_name


def finalize_active_topic_for_device(
    supabase: Client,
    device_id: str,
    llm_service=None,
    idle_seconds: int = DEFAULT_IDLE_SECONDS,
    force: bool = False,
    boundary_reason: Optional[str] = None,
) -> Dict[str, Any]:
    if not device_id:
        raise ValueError("device_id is required")

    topic = _get_active_topic(supabase=supabase, device_id=device_id)
    if not topic:
        return {
            "device_id": device_id,
            "ready": False,
            "reason": "no_active_topic",
        }

    latest_ts = _parse_timestamp(topic.get("last_utterance_at") or topic.get("end_at") or topic.get("updated_at"))
    idle_elapsed = (_now_utc() - latest_ts).total_seconds()
    if not force and idle_elapsed < idle_seconds:
        return {
            "device_id": device_id,
            "ready": False,
            "reason": "idle_threshold_not_reached",
            "idle_elapsed_seconds": int(idle_elapsed),
            "idle_threshold_seconds": idle_seconds,
            "topic_id": topic["id"],
        }

    sessions = _collect_topic_sessions(supabase=supabase, topic_id=topic["id"])
    fallback_title, fallback_summary, fallback_description = _build_finalize_defaults(topic, sessions)
    final_title, final_summary, final_description, used_llm = _run_llm_topic_finalize(
        llm_service=llm_service,
        sessions=sessions,
        fallback_title=fallback_title,
        fallback_summary=fallback_summary,
        fallback_description=fallback_description,
    )
    provider, model = _extract_llm_metadata(llm_service if used_llm else None)
    normalized_boundary_reason = _normalize_boundary_reason(boundary_reason=boundary_reason, force=force)

    now_iso = _iso_now()
    update_payload: Dict[str, Any] = {
        "topic_status": "finalized",
        "final_title": final_title,
        "final_summary": final_summary or None,
        "final_description": final_description or None,
        "boundary_reason": normalized_boundary_reason,
        "finalized_at": now_iso,
        "updated_at": now_iso,
    }
    if not (topic.get("live_title") or "").strip():
        update_payload["live_title"] = final_title
    if not (topic.get("live_summary") or "").strip() and final_summary:
        update_payload["live_summary"] = final_summary
    if not (topic.get("live_description") or "").strip() and final_description:
        update_payload["live_description"] = final_description

    _update_topic_with_compat(
        supabase=supabase,
        topic_id=topic["id"],
        payload=update_payload,
    )

    # Phase 1: Score topic importance (non-blocking)
    scoring_result: Optional[Dict[str, Any]] = None
    try:
        scoring_result = score_topic(
            supabase=supabase,
            topic_id=topic["id"],
            llm_service=llm_service,
        )
        print(f"[Finalize] Topic scoring result: {scoring_result}")
    except Exception as scoring_error:
        print(f"[Finalize] Topic scoring failed (non-blocking): {scoring_error}")

    # Phase 2: Annotation (async, only for Lv.3+)
    try:
        if (scoring_result or {}).get("importance_level", -1) >= 3 and llm_service is not None:
            def _run_annotation():
                try:
                    result = annotate_topic(
                        supabase=supabase,
                        topic_id=topic["id"],
                        llm_service=llm_service,
                        force=False,
                    )
                    print(f"[Finalize] Topic annotation result: {result}")
                except Exception as annotation_error:
                    print(f"[Finalize] Topic annotation failed (non-blocking): {annotation_error}")

            thread = threading.Thread(target=_run_annotation, daemon=True)
            thread.start()
    except Exception as annotation_error:
        print(f"[Finalize] Topic annotation scheduling failed: {annotation_error}")

    return {
        "device_id": device_id,
        "ready": True,
        "reason": "topic_finalized",
        "topic_id": topic["id"],
        "idle_elapsed_seconds": int(idle_elapsed),
        "boundary_reason": normalized_boundary_reason,
        "used_llm": used_llm,
        "llm_provider": provider,
        "llm_model": model,
        "importance_level": (scoring_result or {}).get("importance_level"),
    }


# Legacy path kept temporarily for /api/topics/evaluate-pending during migration.
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
