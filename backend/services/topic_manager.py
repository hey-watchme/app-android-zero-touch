"""
Topic grouping and lifecycle management for ZeroTouch conversations.
"""

from __future__ import annotations

import json
import os
import re
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple

from supabase import Client

from services.prompts import (
    build_topic_finalize_prompt,
    build_topic_join_prompt,
    build_topic_live_update_prompt,
)


SESSION_TABLE = "zerotouch_sessions"
TOPIC_TABLE = os.getenv("TOPIC_TABLE", "zerotouch_conversation_topics")

STATUS_ACTIVE = "active"
STATUS_COOLING = "cooling"
STATUS_FINALIZED = "finalized"

GROUPING_STATUS_GROUPED = "grouped"
GROUPING_METHOD_TIME_RULE = "time_rule"
GROUPING_METHOD_LLM_JOIN = "llm_join"
GROUPING_METHOD_REOPEN = "reopen"

FORCE_NEW_TOPIC_GAP_SECONDS = 15 * 60
ACTIVE_TO_COOLING_SECONDS = 2 * 60
COOLING_TO_FINALIZED_SECONDS = 10 * 60
LIVE_REFRESH_UTTERANCE_STEP = 3
RECENT_UTTERANCE_WINDOW = 5


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
        dt = datetime.fromisoformat(text)
    except ValueError:
        return _now_utc()
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


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


def _collect_recent_topic_utterances(
    supabase: Client,
    topic_id: str,
    limit: int = RECENT_UTTERANCE_WINDOW,
    ascending: bool = True,
) -> List[Dict[str, Any]]:
    response = (
        supabase.table(SESSION_TABLE)
        .select("id, transcription, recorded_at, created_at, status")
        .eq("topic_id", topic_id)
        .order("created_at", desc=not ascending)
        .limit(limit)
        .execute()
    )
    rows = response.data or []
    filtered = [row for row in rows if (row.get("transcription") or "").strip()]
    if not ascending:
        filtered.reverse()
    return filtered


def _build_fallback_live_title(transcript: str) -> str:
    text = (transcript or "").strip()
    if not text:
        return "Untitled conversation"
    if len(text) <= 24:
        return text
    return f"{text[:24].rstrip()}..."


def _find_candidate_topic(supabase: Client, device_id: str) -> Optional[Dict[str, Any]]:
    response = (
        supabase.table(TOPIC_TABLE)
        .select("*")
        .eq("device_id", device_id)
        .in_("topic_status", [STATUS_ACTIVE, STATUS_COOLING])
        .order("last_utterance_at", desc=True)
        .limit(1)
        .execute()
    )
    rows = response.data or []
    return rows[0] if rows else None


def _create_topic(
    supabase: Client,
    device_id: str,
    event_ts: datetime,
    transcript: str,
) -> Dict[str, Any]:
    now_iso = _iso_now()
    payload = {
        "device_id": device_id,
        "topic_status": STATUS_ACTIVE,
        "start_at": event_ts.isoformat(),
        "end_at": event_ts.isoformat(),
        "last_utterance_at": event_ts.isoformat(),
        "utterance_count": 0,
        "live_title": _build_fallback_live_title(transcript),
        "live_summary": (transcript or "").strip()[:180] or None,
        "created_at": now_iso,
        "updated_at": now_iso,
    }
    response = supabase.table(TOPIC_TABLE).insert(payload).execute()
    rows = response.data or []
    if not rows:
        raise RuntimeError("Failed to create conversation topic")
    return rows[0]


def _assign_session_to_topic(
    supabase: Client,
    session_id: str,
    topic_id: str,
    grouping_method: str,
    grouping_confidence: Optional[float] = None,
) -> None:
    payload: Dict[str, Any] = {
        "topic_id": topic_id,
        "grouping_status": GROUPING_STATUS_GROUPED,
        "grouping_method": grouping_method,
        "topic_assigned_at": _iso_now(),
        "updated_at": _iso_now(),
    }
    if grouping_confidence is not None:
        payload["grouping_confidence"] = grouping_confidence

    supabase.table(SESSION_TABLE).update(payload).eq("id", session_id).execute()


def _update_topic_with_session(
    supabase: Client,
    topic: Dict[str, Any],
    event_ts: datetime,
    reopen: bool = False,
) -> Dict[str, Any]:
    topic_end = _parse_timestamp(topic.get("end_at"))
    updated_end = max(topic_end, event_ts)
    current_count = int(topic.get("utterance_count") or 0)
    current_reopened = int(topic.get("reopened_count") or 0)
    payload = {
        "topic_status": STATUS_ACTIVE,
        "end_at": updated_end.isoformat(),
        "last_utterance_at": event_ts.isoformat(),
        "utterance_count": current_count + 1,
        "updated_at": _iso_now(),
    }
    if reopen:
        payload["reopened_count"] = current_reopened + 1
    supabase.table(TOPIC_TABLE).update(payload).eq("id", topic["id"]).execute()
    topic.update(payload)
    return topic


def _classify_join_or_new(
    llm_service,
    topic: Dict[str, Any],
    recent_utterances: List[Dict[str, Any]],
    incoming_transcript: str,
) -> Tuple[str, Optional[float], str]:
    if not llm_service:
        return "join", None, "No LLM configured; fallback to time-based join"

    prompt = build_topic_join_prompt(
        topic_live_title=topic.get("live_title") or "",
        topic_live_summary=topic.get("live_summary") or "",
        recent_utterances=[u.get("transcription") or "" for u in recent_utterances],
        incoming_utterance=incoming_transcript or "",
    )

    output = llm_service.generate(prompt)
    payload = _extract_json(output)
    if not payload:
        return "unsure", None, "LLM output could not be parsed as JSON"

    decision = str(payload.get("decision", "unsure")).strip().lower()
    if decision not in {"join", "new", "unsure"}:
        decision = "unsure"

    confidence_raw = payload.get("confidence")
    confidence = None
    if confidence_raw is not None:
        try:
            confidence = float(confidence_raw)
        except Exception:
            confidence = None

    reason = str(payload.get("reason") or "").strip()
    return decision, confidence, reason


def _fallback_decision_without_llm(
    gap_seconds: float,
) -> Tuple[str, Optional[float], str]:
    if gap_seconds >= FORCE_NEW_TOPIC_GAP_SECONDS:
        return "new", None, f"Fallback: time gap {int(gap_seconds)}s exceeded threshold"
    return "join", None, f"Fallback: joined by time gap {int(gap_seconds)}s"


def maybe_refresh_live_topic(
    supabase: Client,
    topic_id: str,
    llm_service=None,
) -> None:
    topic_response = (
        supabase.table(TOPIC_TABLE)
        .select("*")
        .eq("id", topic_id)
        .single()
        .execute()
    )
    topic = topic_response.data
    if not topic:
        return

    if topic.get("topic_status") != STATUS_ACTIVE:
        return

    utterance_count = int(topic.get("utterance_count") or 0)
    if utterance_count > 1 and utterance_count % LIVE_REFRESH_UTTERANCE_STEP != 0:
        return

    utterances = _collect_recent_topic_utterances(
        supabase=supabase,
        topic_id=topic_id,
        limit=8,
        ascending=True,
    )
    texts = [u.get("transcription") or "" for u in utterances if (u.get("transcription") or "").strip()]
    if not texts:
        return

    next_title = topic.get("live_title") or _build_fallback_live_title(texts[-1])
    next_summary = topic.get("live_summary") or texts[-1][:180]

    if llm_service:
        prompt = build_topic_live_update_prompt(
            current_title=topic.get("live_title") or "",
            current_summary=topic.get("live_summary") or "",
            utterances=texts,
        )
        try:
            output = llm_service.generate(prompt)
            payload = _extract_json(output) or {}
            next_title = str(payload.get("live_title") or next_title).strip() or next_title
            next_summary = str(payload.get("live_summary") or next_summary).strip() or next_summary
        except Exception:
            pass

    supabase.table(TOPIC_TABLE).update(
        {
            "live_title": next_title,
            "live_summary": next_summary,
            "last_title_refreshed_at": _iso_now(),
            "updated_at": _iso_now(),
        }
    ).eq("id", topic_id).execute()


def _finalize_topic(
    supabase: Client,
    topic: Dict[str, Any],
    llm_service=None,
) -> None:
    topic_id = topic["id"]
    utterances = _collect_recent_topic_utterances(
        supabase=supabase,
        topic_id=topic_id,
        limit=100,
        ascending=True,
    )
    utterance_texts = [u.get("transcription") or "" for u in utterances if (u.get("transcription") or "").strip()]

    final_title = topic.get("live_title") or "Conversation"
    final_summary = topic.get("live_summary") or ""
    task_candidates: List[Dict[str, Any]] = []
    topic_type = topic.get("topic_type")

    if utterance_texts and llm_service:
        prompt = build_topic_finalize_prompt(utterance_texts)
        try:
            output = llm_service.generate(prompt)
            payload = _extract_json(output) or {}
            final_title = str(payload.get("final_title") or final_title).strip() or final_title
            final_summary = str(payload.get("final_summary") or final_summary).strip() or final_summary
            if isinstance(payload.get("task_candidates"), list):
                task_candidates = payload["task_candidates"]
            if payload.get("topic_type"):
                topic_type = str(payload["topic_type"])
        except Exception:
            if not final_summary and utterance_texts:
                final_summary = " ".join(utterance_texts[:2])[:280]
    elif utterance_texts and not final_summary:
        final_summary = " ".join(utterance_texts[:2])[:280]

    supabase.table(TOPIC_TABLE).update(
        {
            "topic_status": STATUS_FINALIZED,
            "final_title": final_title,
            "final_summary": final_summary,
            "task_candidates": task_candidates if task_candidates else None,
            "topic_type": topic_type,
            "finalized_at": _iso_now(),
            "updated_at": _iso_now(),
        }
    ).eq("id", topic_id).execute()


def process_transcribed_session(
    session_id: str,
    supabase: Client,
    llm_service=None,
) -> Dict[str, Any]:
    session_response = (
        supabase.table(SESSION_TABLE)
        .select("id, device_id, transcription, recorded_at, created_at")
        .eq("id", session_id)
        .single()
        .execute()
    )
    session = session_response.data
    if not session:
        raise ValueError(f"Session not found: {session_id}")

    transcript = (session.get("transcription") or "").strip()
    if not transcript:
        return {"session_id": session_id, "assigned": False, "reason": "empty_transcription"}

    device_id = session["device_id"]
    event_ts = _parse_timestamp(session.get("recorded_at") or session.get("created_at"))
    candidate = _find_candidate_topic(supabase, device_id)

    decision = "new"
    confidence = None
    reason = ""
    topic: Optional[Dict[str, Any]] = None
    grouping_method = GROUPING_METHOD_TIME_RULE
    llm_checked = False

    if candidate:
        last_ts = _parse_timestamp(candidate.get("last_utterance_at"))
        gap_seconds = (event_ts - last_ts).total_seconds()
        if gap_seconds >= FORCE_NEW_TOPIC_GAP_SECONDS:
            decision = "new"
            reason = f"Time gap {int(gap_seconds)}s exceeded threshold"
        else:
            recent = _collect_recent_topic_utterances(
                supabase=supabase,
                topic_id=candidate["id"],
                limit=RECENT_UTTERANCE_WINDOW,
                ascending=True,
            )
            llm_checked = llm_service is not None
            if llm_checked:
                try:
                    decision, confidence, reason = _classify_join_or_new(
                        llm_service=llm_service,
                        topic=candidate,
                        recent_utterances=recent,
                        incoming_transcript=transcript,
                    )
                    supabase.table(TOPIC_TABLE).update(
                        {
                            "last_llm_join_checked_at": _iso_now(),
                            "updated_at": _iso_now(),
                        }
                    ).eq("id", candidate["id"]).execute()
                except Exception as llm_error:
                    decision, confidence, reason = _fallback_decision_without_llm(gap_seconds)
                    reason = f"{reason}; llm_error={llm_error}"
                    llm_checked = False
            else:
                decision, confidence, reason = _fallback_decision_without_llm(gap_seconds)

            if decision == "join":
                topic = _update_topic_with_session(
                    supabase=supabase,
                    topic=candidate,
                    event_ts=event_ts,
                    reopen=candidate.get("topic_status") == STATUS_FINALIZED,
                )
                grouping_method = GROUPING_METHOD_LLM_JOIN if llm_checked else GROUPING_METHOD_TIME_RULE
            else:
                topic = _create_topic(supabase, device_id, event_ts, transcript)
        # fallthrough handles created topic
    else:
        topic = _create_topic(supabase, device_id, event_ts, transcript)
        reason = "No active/cooling topic found"

    if decision != "join" and topic:
        topic = _update_topic_with_session(
            supabase=supabase,
            topic=topic,
            event_ts=event_ts,
            reopen=False,
        )
        if llm_checked:
            grouping_method = GROUPING_METHOD_LLM_JOIN

    if not topic:
        raise RuntimeError("Topic assignment failed to produce a topic")

    _assign_session_to_topic(
        supabase=supabase,
        session_id=session_id,
        topic_id=topic["id"],
        grouping_method=GROUPING_METHOD_REOPEN if topic.get("reopened_count", 0) else grouping_method,
        grouping_confidence=confidence,
    )

    maybe_refresh_live_topic(supabase=supabase, topic_id=topic["id"], llm_service=llm_service)

    return {
        "session_id": session_id,
        "device_id": device_id,
        "topic_id": topic["id"],
        "decision": decision,
        "reason": reason,
        "confidence": confidence,
    }


def force_assign_session_as_new_topic(
    session_id: str,
    supabase: Client,
) -> Dict[str, Any]:
    session_response = (
        supabase.table(SESSION_TABLE)
        .select("id, device_id, transcription, recorded_at, created_at")
        .eq("id", session_id)
        .single()
        .execute()
    )
    session = session_response.data
    if not session:
        raise ValueError(f"Session not found: {session_id}")

    transcript = (session.get("transcription") or "").strip()
    if not transcript:
        return {"session_id": session_id, "assigned": False, "reason": "empty_transcription"}

    existing_topic_id = (
        supabase.table(SESSION_TABLE)
        .select("topic_id")
        .eq("id", session_id)
        .single()
        .execute()
        .data
        .get("topic_id")
    )
    if existing_topic_id:
        return {
            "session_id": session_id,
            "topic_id": existing_topic_id,
            "assigned": False,
            "reason": "already_assigned",
        }

    event_ts = _parse_timestamp(session.get("recorded_at") or session.get("created_at"))
    topic = _create_topic(
        supabase=supabase,
        device_id=session["device_id"],
        event_ts=event_ts,
        transcript=transcript,
    )
    topic = _update_topic_with_session(
        supabase=supabase,
        topic=topic,
        event_ts=event_ts,
        reopen=False,
    )
    _assign_session_to_topic(
        supabase=supabase,
        session_id=session_id,
        topic_id=topic["id"],
        grouping_method=GROUPING_METHOD_TIME_RULE,
        grouping_confidence=None,
    )
    return {
        "session_id": session_id,
        "device_id": session["device_id"],
        "topic_id": topic["id"],
        "decision": "new",
        "reason": "forced_new_topic_fallback",
        "confidence": None,
    }


def reconcile_topics(
    supabase: Client,
    llm_service=None,
    device_id: Optional[str] = None,
    topic_id: Optional[str] = None,
    force_finalize: bool = False,
) -> Dict[str, Any]:
    query = supabase.table(TOPIC_TABLE).select("*")
    if topic_id:
        query = query.eq("id", topic_id)
    if device_id:
        query = query.eq("device_id", device_id)
    if not force_finalize:
        query = query.in_("topic_status", [STATUS_ACTIVE, STATUS_COOLING])

    topics = (query.execute().data or [])
    now = _now_utc()
    moved_to_cooling = 0
    finalized = 0

    for topic in topics:
        status = topic.get("topic_status")
        if status == STATUS_FINALIZED and not force_finalize:
            continue

        last_utterance_at = _parse_timestamp(topic.get("last_utterance_at"))
        idle_seconds = (now - last_utterance_at).total_seconds()
        day_changed = last_utterance_at.date() != now.date()

        if force_finalize:
            _finalize_topic(supabase=supabase, topic=topic, llm_service=llm_service)
            finalized += 1
            continue

        if status == STATUS_ACTIVE and idle_seconds >= ACTIVE_TO_COOLING_SECONDS:
            supabase.table(TOPIC_TABLE).update(
                {
                    "topic_status": STATUS_COOLING,
                    "updated_at": _iso_now(),
                }
            ).eq("id", topic["id"]).execute()
            status = STATUS_COOLING
            moved_to_cooling += 1

        if status == STATUS_COOLING and (idle_seconds >= COOLING_TO_FINALIZED_SECONDS or day_changed):
            _finalize_topic(supabase=supabase, topic=topic, llm_service=llm_service)
            finalized += 1

    return {
        "checked": len(topics),
        "moved_to_cooling": moved_to_cooling,
        "finalized": finalized,
        "force_finalize": force_finalize,
    }


def backfill_ungrouped_sessions(
    supabase: Client,
    llm_service=None,
    device_id: Optional[str] = None,
    limit: int = 200,
) -> Dict[str, Any]:
    query = (
        supabase.table(SESSION_TABLE)
        .select("id, device_id, status, transcription, recorded_at, created_at, topic_id")
        .is_("topic_id", "null")
        .eq("status", "transcribed")
        .order("recorded_at", desc=False)
        .limit(limit)
    )
    if device_id:
        query = query.eq("device_id", device_id)

    rows = query.execute().data or []
    processed = 0
    skipped = 0
    errors = 0

    for row in rows:
        text = (row.get("transcription") or "").strip()
        if not text:
            skipped += 1
            continue
        try:
            process_transcribed_session(
                session_id=row["id"],
                supabase=supabase,
                llm_service=llm_service,
            )
            processed += 1
        except Exception:
            try:
                force_assign_session_as_new_topic(
                    session_id=row["id"],
                    supabase=supabase,
                )
                processed += 1
            except Exception:
                errors += 1

    return {
        "target": len(rows),
        "processed": processed,
        "skipped": skipped,
        "errors": errors,
        "limit": limit,
    }
