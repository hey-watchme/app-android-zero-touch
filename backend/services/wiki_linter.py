"""
Wiki Lint service.

Performs two checks against zerotouch_wiki_pages for a device:

1. Orphan detection (LLM-free)
   Finds pages with zero inbound [[page_key]] references in other pages' bodies.
   kind='query_answer' pages are excluded because they are naturally orphaned.
   Each orphan is logged individually with operation='lint', outcome='orphan'.

2. Data-gap summary (LLM-assisted)
   Looks at the most recent 50 query log entries where outcome='gap_or_conflict',
   asks the LLM to cluster them into up to 5 missing-knowledge themes, and logs
   a single summary row with operation='lint', outcome='data_gap_summary'.

Lint never writes to zerotouch_wiki_pages (no INSERT/UPDATE/DELETE on that table).
All output is appended to zerotouch_wiki_log only.
"""

from __future__ import annotations

import json
import re
import uuid as uuid_module
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from supabase import Client

from services.prompts import build_wiki_lint_gap_prompt

WIKI_TABLE = "zerotouch_wiki_pages"
WIKI_LOG_TABLE = "zerotouch_wiki_log"
_INBOUND_REF_RE = re.compile(r"\[\[([^\]]+)\]\]")
_GAP_QUERY_LIMIT = 50
_MAX_GAP_THEMES = 5


def _iso_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _is_valid_uuid(value: Any) -> bool:
    try:
        uuid_module.UUID(str(value))
        return True
    except (ValueError, TypeError):
        return False


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


def _split_model_name(model_name: Optional[str]) -> tuple[Optional[str], Optional[str]]:
    if not model_name:
        return None, None
    parts = str(model_name).split("/", 1)
    provider = parts[0] or None
    model = parts[1] if len(parts) > 1 else None
    return provider, model


def _fetch_all_pages(supabase: Client, device_id: str) -> List[Dict[str, Any]]:
    rows = (
        supabase.table(WIKI_TABLE)
        .select("id, title, page_key, body, project_id, category, kind")
        .eq("device_id", device_id)
        .execute()
        .data
        or []
    )
    return rows


def _fetch_gap_log_entries(supabase: Client, device_id: str) -> List[Dict[str, Any]]:
    rows = (
        supabase.table(WIKI_LOG_TABLE)
        .select("id, question, reasoning")
        .eq("device_id", device_id)
        .eq("operation", "query")
        .eq("outcome", "gap_or_conflict")
        .order("created_at", desc=True)
        .limit(_GAP_QUERY_LIMIT)
        .execute()
        .data
        or []
    )
    return rows


def _insert_lint_log(
    supabase: Client,
    *,
    device_id: str,
    reasoning: str,
    outcome: str,
    target_page_id: Optional[str] = None,
    meta: Optional[Dict[str, Any]] = None,
    llm_provider: Optional[str] = None,
    llm_model: Optional[str] = None,
) -> Optional[str]:
    row: Dict[str, Any] = {
        "device_id": device_id,
        "operation": "lint",
        "reasoning": reasoning,
        "outcome": outcome,
        "meta": meta or {},
        "source_page_ids": [],
    }
    if target_page_id and _is_valid_uuid(target_page_id):
        row["target_page_id"] = target_page_id
    if llm_provider:
        row["llm_provider"] = llm_provider
    if llm_model:
        row["llm_model"] = llm_model

    try:
        result = supabase.table(WIKI_LOG_TABLE).insert(row).execute()
        inserted = result.data or []
        if inserted and inserted[0].get("id"):
            return str(inserted[0]["id"])
    except Exception:
        pass
    return None


def _detect_orphans(
    supabase: Client,
    device_id: str,
    pages: List[Dict[str, Any]],
) -> List[Dict[str, Any]]:
    """
    Detect pages with no inbound [[page_key]] references from other pages.

    Pages with kind='query_answer' are excluded because they are naturally
    standalone (not expected to be linked from canon pages).

    Returns a list of orphan metadata dicts (page_id, title, page_key,
    project_key, category) after logging each to zerotouch_wiki_log.
    """
    # Build set of all page_keys that appear as [[...]] refs in other pages' bodies.
    all_refs: set[str] = set()
    for page in pages:
        body = page.get("body") or ""
        for match in _INBOUND_REF_RE.finditer(body):
            ref_key = match.group(1).strip()
            if ref_key:
                all_refs.add(ref_key)

    orphans: List[Dict[str, Any]] = []
    for page in pages:
        kind = page.get("kind") or ""
        if kind == "query_answer":
            continue
        page_key = (page.get("page_key") or "").strip()
        if not page_key:
            continue
        if page_key in all_refs:
            continue

        page_id = str(page.get("id") or "")
        title = page.get("title") or "(no title)"
        project_key = page.get("project_id") or ""
        category = page.get("category") or ""

        _insert_lint_log(
            supabase=supabase,
            device_id=device_id,
            reasoning=(
                f"Orphaned page: no inbound [[{page_key}]] references found in any other page body."
            ),
            outcome="orphan",
            target_page_id=page_id if _is_valid_uuid(page_id) else None,
            meta={
                "lint_type": "orphan",
                "page_key": page_key,
                "title": title,
            },
        )
        orphans.append({
            "page_id": page_id,
            "title": title,
            "page_key": page_key,
            "project_key": project_key,
            "category": category,
        })

    return orphans


def _detect_data_gaps(
    supabase: Client,
    device_id: str,
    llm_service,
    provider_name: Optional[str],
    model_name: Optional[str],
    context_preamble: str = "",
) -> tuple[List[Dict[str, Any]], Optional[str]]:
    """
    Cluster recent gap_or_conflict query log entries into missing-knowledge themes.

    Returns (gaps_list, log_id).  gaps_list is empty when there are no gap entries
    or when LLM parsing fails.  log_id is the inserted zerotouch_wiki_log row id.
    """
    gap_entries = _fetch_gap_log_entries(supabase, device_id)
    if not gap_entries:
        return [], None

    prompt_entries = [
        {
            "question": e.get("question") or "",
            "reasoning": e.get("reasoning") or "",
        }
        for e in gap_entries
    ]

    prompt = build_wiki_lint_gap_prompt(
        gap_entries=prompt_entries,
        context_preamble=context_preamble,
    )

    try:
        raw_output = llm_service.generate(prompt)
    except Exception as exc:
        return [], None

    payload = _extract_json(raw_output)
    gaps: List[Dict[str, Any]] = []
    if payload:
        raw_gaps = payload.get("gaps") or []
        for g in raw_gaps[:_MAX_GAP_THEMES]:
            if isinstance(g, dict) and g.get("theme"):
                gaps.append({
                    "theme": str(g.get("theme") or ""),
                    "description": str(g.get("description") or ""),
                    "related_questions": list(g.get("related_questions") or []),
                })

    log_id = _insert_lint_log(
        supabase=supabase,
        device_id=device_id,
        reasoning="Summary of recurring data gaps detected from gap_or_conflict query logs.",
        outcome="data_gap_summary",
        meta={
            "lint_type": "data_gap",
            "gap_count": len(gaps),
            "gaps": gaps,
        },
        llm_provider=provider_name,
        llm_model=model_name,
    )

    return gaps, log_id


def lint_wiki(
    supabase: Client,
    device_id: str,
    llm_service=None,
) -> Dict[str, Any]:
    """
    Run the Lint pipeline for a device_id.

    Performs orphan detection (LLM-free) and data-gap analysis (LLM-assisted).
    Results are appended to zerotouch_wiki_log only; wiki pages are never modified.

    Returns:
        dict with success, orphan_pages, orphan_count, data_gaps, data_gap_log_id,
        created_at.  On failure: {"success": False, "reason": "..."}.
    """
    if llm_service is None:
        from services.llm_providers import get_current_llm
        llm_service = get_current_llm()

    provider_name, model_name = _split_model_name(
        getattr(llm_service, "model_name", None)
    )

    try:
        pages = _fetch_all_pages(supabase, device_id)
    except Exception as exc:
        return {
            "success": False,
            "reason": f"page_fetch_error: {str(exc)[:200]}",
            "device_id": device_id,
        }

    try:
        orphan_pages = _detect_orphans(supabase, device_id, pages)
    except Exception as exc:
        return {
            "success": False,
            "reason": f"orphan_detection_error: {str(exc)[:200]}",
            "device_id": device_id,
        }

    try:
        data_gaps, data_gap_log_id = _detect_data_gaps(
            supabase=supabase,
            device_id=device_id,
            llm_service=llm_service,
            provider_name=provider_name,
            model_name=model_name,
        )
    except Exception as exc:
        data_gaps = []
        data_gap_log_id = None

    return {
        "success": True,
        "device_id": device_id,
        "orphan_pages": orphan_pages,
        "orphan_count": len(orphan_pages),
        "data_gaps": data_gaps,
        "data_gap_log_id": data_gap_log_id,
        "created_at": _iso_now(),
    }
