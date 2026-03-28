"""
Phase 1: Topic importance scoring (Lv.0-5)

Heuristic rules handle obvious cases (failed transcription, empty topics).
LLM handles nuanced scoring for topics with real content.
"""

from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple

from supabase import Client

from services.prompts import build_topic_scoring_prompt


SESSION_TABLE = "zerotouch_sessions"
TOPIC_TABLE = "zerotouch_conversation_topics"

# Columns that may not exist in older DB schemas
SCORING_COLUMNS = {"importance_level", "importance_reason", "distillation_status", "scored_at"}


def _iso_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _extract_json(text: str) -> Optional[Dict[str, Any]]:
    if not text:
        return None
    cleaned = text.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?", "", cleaned).strip()
        cleaned = cleaned.rstrip("`").strip()
    match = re.search(r"\{[\s\S]*\}", cleaned)
    if match:
        try:
            payload = json.loads(match.group(0))
            if isinstance(payload, dict):
                return payload
        except Exception:
            pass
    try:
        payload = json.loads(cleaned)
        if isinstance(payload, dict):
            return payload
    except Exception:
        pass
    return None


def _error_mentions_missing_column(error: Exception, column: str) -> bool:
    message = str(error).lower()
    column_name = column.lower()
    return column_name in message and (
        "column" in message
        or "schema cache" in message
        or "could not find" in message
    )


def _update_topic_scoring_compat(
    supabase: Client,
    topic_id: str,
    payload: Dict[str, Any],
) -> None:
    """Update topic with scoring columns, dropping missing columns gracefully."""
    candidate = dict(payload)
    while True:
        try:
            supabase.table(TOPIC_TABLE).update(candidate).eq("id", topic_id).execute()
            return
        except Exception as error:
            missing = [
                col for col in SCORING_COLUMNS
                if col in candidate and _error_mentions_missing_column(error, col)
            ]
            if not missing:
                raise
            for col in missing:
                candidate.pop(col, None)
            if not candidate:
                return


def _collect_topic_transcriptions(
    supabase: Client,
    topic_id: str,
) -> Tuple[List[str], int, int]:
    """
    Collect transcriptions for a topic.
    Returns: (transcription_texts, total_sessions, failed_sessions)
    """
    rows = (
        supabase.table(SESSION_TABLE)
        .select("id, transcription, status")
        .eq("topic_id", topic_id)
        .order("created_at", desc=False)
        .limit(500)
        .execute()
        .data
        or []
    )
    total = len(rows)
    failed = sum(1 for r in rows if r.get("status") == "failed")
    texts = [
        (r.get("transcription") or "").strip()
        for r in rows
        if (r.get("transcription") or "").strip()
    ]
    return texts, total, failed


def _heuristic_score(
    texts: List[str],
    total_sessions: int,
    failed_sessions: int,
) -> Optional[Tuple[int, str]]:
    """
    Apply heuristic rules before calling LLM.
    Returns (level, reason) if deterministic, None if LLM is needed.
    """
    # No sessions at all
    if total_sessions == 0:
        return 0, "no sessions in topic"

    # All sessions failed transcription
    if failed_sessions == total_sessions:
        return 0, "all sessions failed transcription"

    # No usable text (all empty after stripping)
    if not texts:
        return 0, "no transcription text available"

    # All texts are very short noise (< 5 chars each)
    if all(len(t) < 5 for t in texts):
        return 0, "all utterances too short (likely noise)"

    # LLM needed for nuanced scoring
    return None


def score_topic(
    supabase: Client,
    topic_id: str,
    llm_service=None,
) -> Dict[str, Any]:
    """
    Score a finalized topic's importance (Lv.0-5).

    1. Collect all session transcriptions for the topic
    2. Apply heuristic rules (failed/empty -> Lv.0 without LLM)
    3. If heuristics don't resolve, call LLM for scoring
    4. Save result to topic record
    """
    # Fetch topic
    topic_resp = (
        supabase.table(TOPIC_TABLE)
        .select("id, topic_status, final_title, final_summary, importance_level")
        .eq("id", topic_id)
        .single()
        .execute()
    )
    topic = topic_resp.data
    if not topic:
        return {"topic_id": topic_id, "scored": False, "reason": "topic_not_found"}

    # Already scored
    if topic.get("importance_level") is not None:
        return {
            "topic_id": topic_id,
            "scored": False,
            "reason": "already_scored",
            "importance_level": topic["importance_level"],
        }

    # Collect transcriptions
    texts, total_sessions, failed_sessions = _collect_topic_transcriptions(
        supabase=supabase,
        topic_id=topic_id,
    )

    # Try heuristic first
    heuristic_result = _heuristic_score(texts, total_sessions, failed_sessions)
    if heuristic_result is not None:
        level, reason = heuristic_result
        _update_topic_scoring_compat(supabase, topic_id, {
            "importance_level": level,
            "importance_reason": reason,
            "distillation_status": "scored",
            "scored_at": _iso_now(),
            "updated_at": _iso_now(),
        })
        return {
            "topic_id": topic_id,
            "scored": True,
            "importance_level": level,
            "importance_reason": reason,
            "method": "heuristic",
        }

    # LLM scoring
    if not llm_service:
        return {
            "topic_id": topic_id,
            "scored": False,
            "reason": "llm_service_not_available",
        }

    final_title = (topic.get("final_title") or "").strip()
    final_summary = (topic.get("final_summary") or "").strip()

    try:
        prompt = build_topic_scoring_prompt(
            final_title=final_title,
            final_summary=final_summary,
            utterances=texts,
        )
        raw_output = llm_service.generate(prompt)
        payload = _extract_json(raw_output) or {}

        level = payload.get("importance_level")
        if not isinstance(level, int) or level < 0 or level > 5:
            level = 1  # default to low if parsing fails
        reason = str(payload.get("importance_reason") or "").strip() or "scored by LLM"

    except Exception as e:
        # LLM failure: don't block finalize, mark as pending for retry
        print(f"[Scorer] LLM scoring failed for topic {topic_id}: {e}")
        return {
            "topic_id": topic_id,
            "scored": False,
            "reason": f"llm_error: {str(e)[:200]}",
        }

    _update_topic_scoring_compat(supabase, topic_id, {
        "importance_level": level,
        "importance_reason": reason[:500],
        "distillation_status": "scored",
        "scored_at": _iso_now(),
        "updated_at": _iso_now(),
    })

    return {
        "topic_id": topic_id,
        "scored": True,
        "importance_level": level,
        "importance_reason": reason,
        "method": "llm",
    }
