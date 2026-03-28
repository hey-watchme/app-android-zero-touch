"""
Phase 2: Topic annotation (Fact extraction)

Extracts structured facts for Lv.3+ topics and writes them to zerotouch_facts.
"""

from __future__ import annotations

import json
import re
from datetime import datetime
from typing import Any, Dict, List, Optional

from supabase import Client

from services.prompts import build_topic_annotation_prompt


SESSION_TABLE = "zerotouch_sessions"
TOPIC_TABLE = "zerotouch_conversation_topics"
FACT_TABLE = "zerotouch_facts"

ANNOTATION_COLUMNS = {"distillation_status"}


def _iso_now_local() -> str:
    return datetime.now().astimezone().isoformat()


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


def _update_topic_status_compat(
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
                col for col in ANNOTATION_COLUMNS
                if col in candidate and _error_mentions_missing_column(error, col)
            ]
            if not missing:
                raise
            for col in missing:
                candidate.pop(col, None)
            if not candidate:
                return


def _collect_topic_utterances(supabase: Client, topic_id: str) -> List[Dict[str, Any]]:
    rows = (
        supabase.table(SESSION_TABLE)
        .select("id, transcription, recorded_at, created_at")
        .eq("topic_id", topic_id)
        .order("created_at", desc=False)
        .limit(500)
        .execute()
        .data
        or []
    )
    return [row for row in rows if (row.get("transcription") or "").strip()]


def _normalize_list(value: Any) -> List[str]:
    if isinstance(value, list):
        return [str(v).strip() for v in value if str(v).strip()]
    if isinstance(value, str):
        return [value.strip()] if value.strip() else []
    return []


def _filter_uuid_list(values: Any) -> List[str]:
    items = _normalize_list(values)
    pattern = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    return [value for value in items if pattern.match(value)]


def _normalize_expires_at(value: Any) -> Optional[str]:
    if not value:
        return None
    text = str(value).strip()
    if not text:
        return None
    try:
        datetime.fromisoformat(text.replace("Z", "+00:00"))
        return text
    except Exception:
        return None


def annotate_topic(
    supabase: Client,
    topic_id: str,
    llm_service=None,
    force: bool = False,
) -> Dict[str, Any]:
    topic = (
        supabase.table(TOPIC_TABLE)
        .select(
            "id, device_id, final_title, final_summary, final_description, "
            "importance_level, distillation_status"
        )
        .eq("id", topic_id)
        .single()
        .execute()
        .data
    )
    if not topic:
        return {"topic_id": topic_id, "annotated": False, "reason": "topic_not_found"}

    importance_level = topic.get("importance_level")
    if importance_level is None:
        return {"topic_id": topic_id, "annotated": False, "reason": "not_scored"}

    if importance_level < 3:
        _update_topic_status_compat(
            supabase,
            topic_id,
            {"distillation_status": "skipped", "updated_at": _iso_now_local()},
        )
        return {"topic_id": topic_id, "annotated": False, "reason": "low_importance"}

    if topic.get("distillation_status") == "annotated" and not force:
        return {"topic_id": topic_id, "annotated": False, "reason": "already_annotated"}

    utterances = _collect_topic_utterances(supabase, topic_id)
    if not utterances:
        _update_topic_status_compat(
            supabase,
            topic_id,
            {"distillation_status": "skipped", "updated_at": _iso_now_local()},
        )
        return {"topic_id": topic_id, "annotated": False, "reason": "no_transcription"}

    if not llm_service:
        return {"topic_id": topic_id, "annotated": False, "reason": "llm_unavailable"}

    prompt = build_topic_annotation_prompt(
        final_title=(topic.get("final_title") or "").strip(),
        final_summary=(topic.get("final_summary") or "").strip(),
        final_description=(topic.get("final_description") or "").strip(),
        utterances=utterances,
        now_iso=_iso_now_local(),
    )

    try:
        raw_output = llm_service.generate(prompt)
        payload = _extract_json(raw_output) or {}
        facts = payload.get("facts")
        if not isinstance(facts, list):
            facts = []
    except Exception as exc:
        return {"topic_id": topic_id, "annotated": False, "reason": f"llm_error: {exc}"}

    if force:
        supabase.table(FACT_TABLE).delete().eq("topic_id", topic_id).execute()

    now_iso = _iso_now_local()
    fact_rows: List[Dict[str, Any]] = []
    for fact in facts:
        if not isinstance(fact, dict):
            continue
        fact_text = str(fact.get("fact_text") or "").strip()
        if not fact_text:
            continue

        ttl = fact.get("ttl") if isinstance(fact.get("ttl"), dict) else {}
        ttl_type = str(ttl.get("type") or "").strip() or None
        expires_at = _normalize_expires_at(ttl.get("expires_at"))

        row = {
            "topic_id": topic_id,
            "device_id": topic.get("device_id"),
            "fact_text": fact_text[:1000],
            "importance_level": importance_level,
            "entities": fact.get("entities") if isinstance(fact.get("entities"), list) else [],
            "categories": _normalize_list(fact.get("categories")),
            "intents": _normalize_list(fact.get("intents")),
            "ttl_type": ttl_type,
            "expires_at": expires_at,
            "source_cards": _filter_uuid_list(fact.get("source_cards")),
            "consolidation_status": "pending",
            "created_at": now_iso,
            "updated_at": now_iso,
        }
        fact_rows.append(row)

    if fact_rows:
        supabase.table(FACT_TABLE).insert(fact_rows).execute()

    _update_topic_status_compat(
        supabase,
        topic_id,
        {"distillation_status": "annotated", "updated_at": _iso_now_local()},
    )

    return {
        "topic_id": topic_id,
        "annotated": True,
        "fact_count": len(fact_rows),
    }
