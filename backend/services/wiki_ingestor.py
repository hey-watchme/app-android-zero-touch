"""
Phase 3: Wiki Ingest service.

Reads Facts (Lv.3+) for a device, calls LLM to integrate them into wiki pages,
and upserts the result into zerotouch_wiki_pages.
"""

import json
import re
import uuid as uuid_module
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from supabase import Client

from services.prompts import build_wiki_ingest_prompt
from services.workspace_registry import fetch_context_preamble

FACT_TABLE = "zerotouch_facts"
WIKI_TABLE = "zerotouch_wiki_pages"
MIN_IMPORTANCE = 3


def _iso_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _extract_json(text: str) -> Optional[Dict[str, Any]]:
    if not text:
        return None
    cleaned = re.sub(r"```(?:json)?\s*", "", text).replace("```", "").strip()
    match = re.search(r"\{[\s\S]*\}", cleaned)
    if not match:
        return None
    try:
        return json.loads(match.group(0))
    except json.JSONDecodeError:
        return None


def _fetch_facts(supabase: Client, device_id: str, min_importance: int) -> List[Dict]:
    rows = (
        supabase.table(FACT_TABLE)
        .select("id, topic_id, fact_text, importance_level, categories, ttl_type")
        .eq("device_id", device_id)
        .gte("importance_level", min_importance)
        .order("created_at", desc=False)
        .execute()
        .data
        or []
    )
    return rows


def _fetch_existing_pages(supabase: Client, device_id: str) -> List[Dict]:
    rows = (
        supabase.table(WIKI_TABLE)
        .select("id, title, body, theme, kind, version, source_fact_ids")
        .eq("device_id", device_id)
        .execute()
        .data
        or []
    )
    return rows


def _fetch_workspace_id(supabase: Client, device_id: str) -> Optional[str]:
    rows = (
        supabase.table("zerotouch_devices")
        .select("workspace_id")
        .eq("device_id", device_id)
        .limit(1)
        .execute()
        .data
        or []
    )
    if rows:
        return rows[0].get("workspace_id")
    return None


def _is_valid_uuid(value: str) -> bool:
    try:
        uuid_module.UUID(str(value))
        return True
    except ValueError:
        return False


def _build_existing_page_map(existing_pages: List[Dict]) -> Dict[str, Dict]:
    return {page["title"].strip().lower(): page for page in existing_pages}


def _upsert_wiki_pages(
    supabase: Client,
    device_id: str,
    workspace_id: Optional[str],
    llm_pages: List[Dict],
    existing_map: Dict[str, Dict],
) -> List[Dict]:
    now = _iso_now()
    results = []

    for page_data in llm_pages:
        title = (page_data.get("title") or "").strip()
        body = (page_data.get("body") or "").strip()
        theme = page_data.get("theme") or None
        kind = page_data.get("kind") or None
        source_fact_ids = [
            str(fid) for fid in (page_data.get("source_fact_ids") or [])
            if fid and _is_valid_uuid(str(fid))
        ]

        if not title or not body:
            continue

        existing = existing_map.get(title.lower())

        if existing:
            new_version = (existing.get("version") or 1) + 1
            merged_fact_ids = list(
                {str(fid) for fid in (existing.get("source_fact_ids") or []) if fid and _is_valid_uuid(str(fid))}
                | set(source_fact_ids)
            )
            supabase.table(WIKI_TABLE).update({
                "body": body,
                "theme": theme,
                "kind": kind,
                "version": new_version,
                "source_fact_ids": merged_fact_ids,
                "last_ingest_at": now,
                "updated_at": now,
            }).eq("id", existing["id"]).execute()
            results.append({"title": title, "action": "updated", "version": new_version})
        else:
            row = {
                "device_id": device_id,
                "title": title,
                "body": body,
                "theme": theme,
                "kind": kind,
                "status": "active",
                "version": 1,
                "source_fact_ids": source_fact_ids,
                "last_ingest_at": now,
                "created_at": now,
                "updated_at": now,
            }
            if workspace_id:
                row["workspace_id"] = workspace_id
            supabase.table(WIKI_TABLE).insert(row).execute()
            results.append({"title": title, "action": "created", "version": 1})

    return results


def ingest_wiki(
    supabase: Client,
    device_id: str,
    llm_service=None,
    min_importance: int = MIN_IMPORTANCE,
) -> Dict[str, Any]:
    """
    Run Phase 3 Wiki Ingest for a device.

    Fetches all Facts (Lv.3+), calls LLM to integrate them into wiki pages,
    and upserts the result into zerotouch_wiki_pages.

    Returns:
        dict with keys: device_id, facts_used, pages_result, success, reason
    """
    if llm_service is None:
        from services.llm_providers import get_current_llm
        llm_service = get_current_llm()

    facts = _fetch_facts(supabase, device_id, min_importance)
    if not facts:
        return {
            "device_id": device_id,
            "success": False,
            "reason": f"no_facts_lv{min_importance}_or_above",
            "facts_used": 0,
            "pages_result": [],
        }

    existing_pages = _fetch_existing_pages(supabase, device_id)
    workspace_id = _fetch_workspace_id(supabase, device_id)
    context_preamble = fetch_context_preamble(supabase, workspace_id) if workspace_id else ""

    prompt = build_wiki_ingest_prompt(
        facts=facts,
        existing_pages=existing_pages,
        context_preamble=context_preamble,
    )

    try:
        raw_output = llm_service.generate(prompt)
    except Exception as exc:
        return {
            "device_id": device_id,
            "success": False,
            "reason": f"llm_error: {str(exc)[:200]}",
            "facts_used": len(facts),
            "pages_result": [],
        }

    payload = _extract_json(raw_output)
    if not payload or "pages" not in payload:
        return {
            "device_id": device_id,
            "success": False,
            "reason": "llm_output_parse_failed",
            "facts_used": len(facts),
            "pages_result": [],
            "raw_output_preview": (raw_output or "")[:300],
        }

    llm_pages = payload.get("pages") or []
    existing_map = _build_existing_page_map(existing_pages)

    try:
        pages_result = _upsert_wiki_pages(
            supabase=supabase,
            device_id=device_id,
            workspace_id=workspace_id,
            llm_pages=llm_pages,
            existing_map=existing_map,
        )
    except Exception as exc:
        return {
            "device_id": device_id,
            "success": False,
            "reason": f"upsert_error: {str(exc)[:200]}",
            "facts_used": len(facts),
            "pages_result": [],
        }

    return {
        "device_id": device_id,
        "success": True,
        "facts_used": len(facts),
        "pages_result": pages_result,
    }
