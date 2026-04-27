"""
Action Converter (Slice 1).

Transforms a finalized Topic + its Facts + workspace context into one or more
Action Candidates and writes them to zerotouch_action_candidates.

For Slice 1 we support a single domain (knowledge_worker) and a single intent
(email_draft). The structure here is intentionally generic so that adding more
domains / intents only requires extending services/domain_schemas/*.
"""

from __future__ import annotations

import json
import re
from datetime import datetime
from typing import Any, Dict, List, Optional

from supabase import Client

from services.domain_schemas import knowledge_worker
from services.workspace_registry import fetch_context_preamble


SESSION_TABLE = "zerotouch_sessions"
TOPIC_TABLE = "zerotouch_conversation_topics"
FACT_TABLE = "zerotouch_facts"
ACTION_TABLE = "zerotouch_action_candidates"

DEFAULT_DOMAIN = "knowledge_worker"


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


def _filter_uuid_list(values: Any) -> List[str]:
    if not isinstance(values, list):
        return []
    pattern = re.compile(
        r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )
    seen = set()
    result: List[str] = []
    for value in values:
        text = str(value or "").strip()
        if pattern.match(text) and text not in seen:
            seen.add(text)
            result.append(text)
    return result


def _collect_utterances(supabase: Client, topic_id: str) -> List[Dict[str, Any]]:
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


def _collect_facts(supabase: Client, topic_id: str) -> List[Dict[str, Any]]:
    rows = (
        supabase.table(FACT_TABLE)
        .select("id, fact_text, entities, categories, intents, importance_level")
        .eq("topic_id", topic_id)
        .order("created_at", desc=False)
        .limit(50)
        .execute()
        .data
        or []
    )
    return rows


def _existing_pending_candidates(
    supabase: Client, topic_id: str, intent_type: str
) -> List[Dict[str, Any]]:
    rows = (
        supabase.table(ACTION_TABLE)
        .select("id, status")
        .eq("topic_id", topic_id)
        .eq("intent_type", intent_type)
        .eq("status", "pending")
        .limit(10)
        .execute()
        .data
        or []
    )
    return rows


def _supersede_pending(
    supabase: Client, topic_id: str, intent_type: str
) -> int:
    now = _iso_now_local()
    response = (
        supabase.table(ACTION_TABLE)
        .update({"status": "rejected", "review_state": {"reason": "superseded_by_regeneration", "at": now}, "updated_at": now})
        .eq("topic_id", topic_id)
        .eq("intent_type", intent_type)
        .eq("status", "pending")
        .execute()
    )
    data = response.data or []
    return len(data)


def convert_topic(
    *,
    supabase: Client,
    topic_id: str,
    llm_service,
    force: bool = False,
    domain: str = DEFAULT_DOMAIN,
) -> Dict[str, Any]:
    """
    Generate Action Candidates for one finalized topic.

    Returns a status dict with `created` (count) and `candidate_ids`.
    """
    if domain != knowledge_worker.DOMAIN:
        return {"topic_id": topic_id, "ok": False, "reason": f"unsupported_domain:{domain}"}

    if not llm_service:
        return {"topic_id": topic_id, "ok": False, "reason": "llm_unavailable"}

    topic = (
        supabase.table(TOPIC_TABLE)
        .select(
            "id, device_id, workspace_id, final_title, final_summary, final_description, "
            "importance_level, status"
        )
        .eq("id", topic_id)
        .single()
        .execute()
        .data
    )
    if not topic:
        return {"topic_id": topic_id, "ok": False, "reason": "topic_not_found"}

    utterances = _collect_utterances(supabase, topic_id)
    if not utterances:
        return {"topic_id": topic_id, "ok": False, "reason": "no_transcription"}

    facts = _collect_facts(supabase, topic_id)

    enabled_intents = knowledge_worker.list_intents()
    superseded_count = 0
    if force:
        for intent_type in enabled_intents:
            superseded_count += _supersede_pending(supabase, topic_id, intent_type)
    else:
        # Idempotency: if any enabled intent already has pending candidates,
        # return them instead of regenerating.
        for intent_type in enabled_intents:
            existing = _existing_pending_candidates(supabase, topic_id, intent_type)
            if existing:
                return {
                    "topic_id": topic_id,
                    "ok": True,
                    "reused": True,
                    "candidate_ids": [row["id"] for row in existing],
                    "created": 0,
                }

    context_preamble = fetch_context_preamble(
        supabase=supabase,
        workspace_id=(topic.get("workspace_id") or ""),
        device_id=(topic.get("device_id") or ""),
    )

    prompt = knowledge_worker.build_converter_prompt(
        final_title=(topic.get("final_title") or "").strip(),
        final_summary=(topic.get("final_summary") or "").strip(),
        final_description=(topic.get("final_description") or "").strip(),
        utterances=utterances,
        facts=facts,
        now_iso=_iso_now_local(),
        context_preamble=context_preamble,
    )

    try:
        raw_output = llm_service.generate(prompt)
    except Exception as exc:
        return {"topic_id": topic_id, "ok": False, "reason": f"llm_error: {exc}"}

    payload_obj = _extract_json(raw_output) or {}
    candidates_raw = payload_obj.get("candidates")
    if not isinstance(candidates_raw, list):
        candidates_raw = []

    valid_session_ids = {str(row.get("id")) for row in utterances if row.get("id")}

    rows_to_insert: List[Dict[str, Any]] = []
    now_iso = _iso_now_local()
    for cand in candidates_raw:
        if not isinstance(cand, dict):
            continue
        intent_type = str(cand.get("intent_type") or "").strip()
        if intent_type not in enabled_intents:
            continue

        intent_def = knowledge_worker.get_intent_def(intent_type)
        if not intent_def:
            continue

        normalized_payload = knowledge_worker.normalize_payload(intent_type, cand.get("payload") or {})
        if not normalized_payload:
            continue

        try:
            confidence_raw = cand.get("confidence")
            confidence = float(confidence_raw) if confidence_raw is not None else None
        except Exception:
            confidence = None
        if confidence is not None:
            confidence = max(0.0, min(1.0, confidence))

        source_session_ids = _filter_uuid_list(cand.get("source_session_ids"))
        source_session_ids = [sid for sid in source_session_ids if sid in valid_session_ids]

        sources = {
            "session_ids": source_session_ids,
            "fact_ids": [str(f.get("id")) for f in facts if f.get("id")],
            "topic_id": topic_id,
            "source_quote": normalized_payload.get("source_quote"),
        }

        title = str(cand.get("title") or "").strip() or normalized_payload.get("subject", "")[:60]
        summary = str(cand.get("summary") or "").strip()

        row = {
            "topic_id": topic_id,
            "device_id": topic.get("device_id"),
            "workspace_id": topic.get("workspace_id"),
            "domain": knowledge_worker.DOMAIN,
            "intent_type": intent_type,
            "title": title[:200] if title else None,
            "summary": summary[:500] if summary else None,
            "payload": normalized_payload,
            "sources": sources,
            "destination": intent_def.get("destination"),
            "status": "pending",
            "confidence": confidence,
            "requires_review": bool(intent_def.get("requires_review", True)),
            "review_state": {},
            "provider": getattr(llm_service, "model_name", None),
            "model": getattr(llm_service, "model_name", None),
            "generation_metadata": {
                "force": force,
                "superseded_count": superseded_count,
            },
            "created_at": now_iso,
            "updated_at": now_iso,
        }
        rows_to_insert.append(row)

    if not rows_to_insert:
        return {
            "topic_id": topic_id,
            "ok": True,
            "created": 0,
            "candidate_ids": [],
            "superseded": superseded_count,
        }

    inserted = (
        supabase.table(ACTION_TABLE)
        .insert(rows_to_insert)
        .execute()
        .data
        or []
    )

    return {
        "topic_id": topic_id,
        "ok": True,
        "created": len(inserted),
        "candidate_ids": [row.get("id") for row in inserted if row.get("id")],
        "superseded": superseded_count,
    }


def list_action_candidates(
    *,
    supabase: Client,
    device_id: Optional[str] = None,
    topic_id: Optional[str] = None,
    status: Optional[str] = None,
    intent_type: Optional[str] = None,
    limit: int = 50,
) -> List[Dict[str, Any]]:
    query = supabase.table(ACTION_TABLE).select("*")
    if device_id:
        query = query.eq("device_id", device_id)
    if topic_id:
        query = query.eq("topic_id", topic_id)
    if status:
        query = query.eq("status", status)
    if intent_type:
        query = query.eq("intent_type", intent_type)
    rows = (
        query.order("created_at", desc=True)
        .limit(max(1, min(limit, 200)))
        .execute()
        .data
        or []
    )
    return rows


def review_action_candidate(
    *,
    supabase: Client,
    candidate_id: str,
    action: str,
    edits: Optional[Dict[str, Any]] = None,
    notes: Optional[str] = None,
    reviewer: Optional[str] = None,
) -> Dict[str, Any]:
    """
    Apply a review action: approve | reject | edit.

    For Slice 1 we do not export to any external system. We just update the
    status and append to review_state.
    """
    action = (action or "").strip().lower()
    if action not in {"approve", "reject", "edit"}:
        return {"ok": False, "reason": f"invalid_action:{action}"}

    existing = (
        supabase.table(ACTION_TABLE)
        .select("id, payload, review_state, status")
        .eq("id", candidate_id)
        .single()
        .execute()
        .data
    )
    if not existing:
        return {"ok": False, "reason": "candidate_not_found"}

    now = _iso_now_local()
    history = []
    prior_state = existing.get("review_state") or {}
    if isinstance(prior_state, dict):
        prior_history = prior_state.get("history")
        if isinstance(prior_history, list):
            history = list(prior_history)

    history.append(
        {
            "action": action,
            "at": now,
            "reviewer": reviewer,
            "notes": notes,
        }
    )

    update: Dict[str, Any] = {
        "review_state": {
            **(prior_state if isinstance(prior_state, dict) else {}),
            "history": history,
            "last_action": action,
            "last_action_at": now,
        },
        "updated_at": now,
    }

    if action == "approve":
        update["status"] = "approved"
    elif action == "reject":
        update["status"] = "rejected"
    elif action == "edit":
        normalized: Optional[Dict[str, Any]] = None
        existing_intent = existing.get("intent_type") or "email_draft"
        if isinstance(edits, dict):
            base_payload = existing.get("payload") if isinstance(existing.get("payload"), dict) else {}
            merged = {**(base_payload or {}), **edits}
            normalized = knowledge_worker.normalize_payload(existing_intent, merged)
        if not normalized:
            return {"ok": False, "reason": "invalid_edits"}
        update["payload"] = normalized
        update["status"] = existing.get("status") or "pending"

    response = (
        supabase.table(ACTION_TABLE)
        .update(update)
        .eq("id", candidate_id)
        .execute()
    )
    rows = response.data or []
    if not rows:
        return {"ok": False, "reason": "update_failed"}
    return {"ok": True, "candidate": rows[0]}
