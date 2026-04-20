"""
Wiki Query service (selector -> answerer -> filing-back).

Pipeline:
  1) Fetch zerotouch_wiki_index rows for the device.
  2) Selector LLM chooses up to max_pages candidate page_ids from the index.
  3) Fetch full pages by id and resolve project display names.
  4) Answerer LLM produces answer + outcome (derivable | synthesis | gap_or_conflict).
  5) Filing-back:
       - derivable        -> log only, no page write
       - synthesis        -> insert new kind='query_answer' page + matching index row
       - gap_or_conflict  -> log only, pointing at target_page_id
  6) Always append a zerotouch_wiki_log row with operation='query'.

Canonical wiki pages produced by Ingest are never edited by Query; only new
query_answer pages may be created.
"""

from __future__ import annotations

import json
import re
import uuid as uuid_module
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from supabase import Client

from services.prompts import (
    build_wiki_query_answerer_prompt,
    build_wiki_query_selector_prompt,
)
from services.wiki_ingestor import _derive_page_key, _normalize_machine_key
from services.workspace_registry import fetch_context_preamble

WIKI_TABLE = "zerotouch_wiki_pages"
WIKI_INDEX_TABLE = "zerotouch_wiki_index"
WIKI_LOG_TABLE = "zerotouch_wiki_log"
DEVICE_TABLE = "zerotouch_devices"
PROJECT_TABLE = "zerotouch_workspace_projects"
SUMMARY_MAX_CHARS = 120
UNASSIGNED_PROJECT_KEY = "unassigned"


def _iso_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _is_valid_uuid(value: Any) -> bool:
    try:
        uuid_module.UUID(str(value))
        return True
    except (ValueError, TypeError):
        return False


def _normalize_text(value: Any) -> Optional[str]:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


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


def _fetch_workspace_id(supabase: Client, device_id: str) -> Optional[str]:
    rows = (
        supabase.table(DEVICE_TABLE)
        .select("workspace_id")
        .eq("device_id", device_id)
        .limit(1)
        .execute()
        .data
        or []
    )
    if rows:
        value = rows[0].get("workspace_id")
        return _normalize_text(value)
    return None


def _fetch_index_rows(supabase: Client, device_id: str) -> List[Dict[str, Any]]:
    rows = (
        supabase.table(WIKI_INDEX_TABLE)
        .select(
            "page_id, title, project_id, project_key, project_name, "
            "category, page_key, kind, summary_one_liner, source_count"
        )
        .eq("device_id", device_id)
        .order("updated_at", desc=True)
        .execute()
        .data
        or []
    )
    return rows


def _fetch_full_pages(
    supabase: Client,
    device_id: str,
    page_ids: List[str],
) -> List[Dict[str, Any]]:
    if not page_ids:
        return []
    rows = (
        supabase.table(WIKI_TABLE)
        .select("id, title, body, project_id, category, page_key, kind")
        .eq("device_id", device_id)
        .in_("id", page_ids)
        .execute()
        .data
        or []
    )
    return rows


def _fetch_projects(supabase: Client, workspace_id: Optional[str]) -> List[Dict[str, Any]]:
    if not workspace_id:
        return []
    try:
        rows = (
            supabase.table(PROJECT_TABLE)
            .select("id, project_key, display_name")
            .eq("workspace_id", workspace_id)
            .execute()
            .data
            or []
        )
    except Exception:
        return []
    return rows


def _build_project_lookups(
    project_rows: List[Dict[str, Any]],
) -> tuple[Dict[str, Dict[str, Any]], Dict[str, Dict[str, Any]]]:
    by_id: Dict[str, Dict[str, Any]] = {}
    by_key: Dict[str, Dict[str, Any]] = {}
    for row in project_rows:
        row_id = _normalize_text(row.get("id"))
        row_key = _normalize_machine_key(row.get("project_key"))
        if row_id:
            by_id[row_id] = row
        if row_key:
            by_key[row_key] = row
    return by_id, by_key


def _decorate_pages_with_project(
    pages: List[Dict[str, Any]],
    projects_by_id: Dict[str, Dict[str, Any]],
) -> List[Dict[str, Any]]:
    decorated: List[Dict[str, Any]] = []
    for page in pages:
        project_id = _normalize_text(page.get("project_id"))
        project = projects_by_id.get(project_id) if project_id else None
        decorated.append({
            **page,
            "project_name": _normalize_text(project.get("display_name")) if project else None,
            "project_key": _normalize_machine_key(project.get("project_key")) if project else None,
        })
    return decorated


def _derive_summary(provided: Any, answer: str) -> str:
    text = _normalize_text(provided)
    if text:
        return text[:SUMMARY_MAX_CHARS]
    normalized = re.sub(r"\s+", " ", answer or "").strip()
    if not normalized:
        return "(no summary)"
    return normalized[:SUMMARY_MAX_CHARS]


def _unique_page_key(
    supabase: Client,
    device_id: str,
    base_key: str,
) -> str:
    # Ensure the page_key is unique for this device. If collision exists, suffix a short UUID.
    existing = (
        supabase.table(WIKI_TABLE)
        .select("id")
        .eq("device_id", device_id)
        .eq("page_key", base_key)
        .limit(1)
        .execute()
        .data
        or []
    )
    if not existing:
        return base_key
    suffix = uuid_module.uuid4().hex[:6]
    return f"{base_key}-{suffix}"


def _build_query_answer_body(
    question: str,
    answer: str,
    source_pages: List[Dict[str, Any]],
) -> str:
    # Compose a markdown-ish body that keeps the question, the answer, and
    # a list of source pages for traceability.
    lines: List[str] = []
    lines.append("# 質問")
    lines.append("")
    lines.append(question.strip() or "(empty)")
    lines.append("")
    lines.append("# 回答")
    lines.append("")
    lines.append((answer or "").strip() or "(empty)")
    lines.append("")
    lines.append("# 参照元")
    lines.append("")
    if source_pages:
        for page in source_pages:
            title = _normalize_text(page.get("title")) or "(no title)"
            page_key = _normalize_text(page.get("page_key")) or "-"
            project_name = _normalize_text(page.get("project_name")) or "unassigned"
            lines.append(f"- [[{page_key}]] {title} ({project_name})")
    else:
        lines.append("- (none)")
    return "\n".join(lines)


def _insert_log(
    supabase: Client,
    *,
    device_id: str,
    workspace_id: Optional[str],
    operation: str,
    question: Optional[str],
    answer: Optional[str],
    confidence: Optional[str],
    outcome: Optional[str],
    reasoning: Optional[str],
    source_page_ids: List[str],
    target_page_id: Optional[str],
    result_page_id: Optional[str],
    llm_provider: Optional[str],
    llm_model: Optional[str],
    meta: Optional[Dict[str, Any]] = None,
) -> Optional[str]:
    row: Dict[str, Any] = {
        "device_id": device_id,
        "operation": operation,
        "source_page_ids": [pid for pid in source_page_ids if _is_valid_uuid(pid)],
        "meta": meta or {},
    }
    if workspace_id:
        row["workspace_id"] = workspace_id
    if question is not None:
        row["question"] = question
    if answer is not None:
        row["answer"] = answer
    if confidence is not None:
        row["confidence"] = confidence
    if outcome is not None:
        row["outcome"] = outcome
    if reasoning is not None:
        row["reasoning"] = reasoning
    if target_page_id and _is_valid_uuid(target_page_id):
        row["target_page_id"] = target_page_id
    if result_page_id and _is_valid_uuid(result_page_id):
        row["result_page_id"] = result_page_id
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
        return None
    return None


def _split_model_name(model_name: Optional[str]) -> tuple[Optional[str], Optional[str]]:
    if not model_name:
        return None, None
    parts = str(model_name).split("/", 1)
    provider = parts[0] or None
    model = parts[1] if len(parts) > 1 else None
    return provider, model


def query_wiki(
    supabase: Client,
    device_id: str,
    question: str,
    llm_service=None,
    max_pages: int = 5,
) -> Dict[str, Any]:
    """
    Run the Query pipeline for a device_id + question.

    Returns a dict with success flag, answer, outcome, filing_back info, etc.
    On failure, returns {"success": False, "reason": "..."} without raising.
    """
    if llm_service is None:
        from services.llm_providers import get_current_llm
        llm_service = get_current_llm()

    question_text = _normalize_text(question) or ""
    if not question_text:
        return {"success": False, "reason": "empty_question", "device_id": device_id}

    provider_name, model_name = _split_model_name(getattr(llm_service, "model_name", None))

    # Step 1: fetch index rows.
    try:
        index_rows = _fetch_index_rows(supabase, device_id)
    except Exception as exc:
        return {
            "success": False,
            "reason": f"index_fetch_error: {str(exc)[:200]}",
            "device_id": device_id,
        }
    if not index_rows:
        return {"success": False, "reason": "no_index_rows", "device_id": device_id}

    # Step 2: workspace + context preamble.
    workspace_id = _fetch_workspace_id(supabase, device_id)
    context_preamble = (
        fetch_context_preamble(supabase, workspace_id, device_id=device_id)
        if workspace_id else ""
    )

    # Step 3: selector LLM call.
    selector_prompt = build_wiki_query_selector_prompt(
        question=question_text,
        index_rows=index_rows,
        context_preamble=context_preamble,
    )
    try:
        selector_raw = llm_service.generate(selector_prompt)
    except Exception as exc:
        return {
            "success": False,
            "reason": f"selector_llm_error: {str(exc)[:200]}",
            "device_id": device_id,
        }

    selector_payload = _extract_json(selector_raw) or {}
    raw_selected = selector_payload.get("selected_page_ids") or []
    valid_index_ids = {str(row.get("page_id")) for row in index_rows if row.get("page_id")}
    selected_page_ids = [
        str(pid) for pid in raw_selected
        if str(pid) in valid_index_ids and _is_valid_uuid(pid)
    ]
    if max_pages and len(selected_page_ids) > max_pages:
        selected_page_ids = selected_page_ids[:max_pages]

    if not selected_page_ids:
        return {
            "success": False,
            "reason": "selector_returned_no_pages",
            "device_id": device_id,
            "selector_reasoning": selector_payload.get("reasoning"),
        }

    # Step 4: fetch full pages + decorate with project name.
    try:
        full_pages_raw = _fetch_full_pages(supabase, device_id, selected_page_ids)
    except Exception as exc:
        return {
            "success": False,
            "reason": f"page_fetch_error: {str(exc)[:200]}",
            "device_id": device_id,
        }
    project_rows = _fetch_projects(supabase, workspace_id)
    projects_by_id, projects_by_key = _build_project_lookups(project_rows)
    full_pages = _decorate_pages_with_project(full_pages_raw, projects_by_id)
    if not full_pages:
        return {
            "success": False,
            "reason": "selected_pages_not_found",
            "device_id": device_id,
            "selected_page_ids": selected_page_ids,
        }

    # Step 5: answerer LLM call.
    answerer_prompt = build_wiki_query_answerer_prompt(
        question=question_text,
        full_pages=full_pages,
        context_preamble=context_preamble,
    )
    try:
        answerer_raw = llm_service.generate(answerer_prompt)
    except Exception as exc:
        return {
            "success": False,
            "reason": f"answerer_llm_error: {str(exc)[:200]}",
            "device_id": device_id,
        }

    answerer_payload = _extract_json(answerer_raw)
    if not answerer_payload:
        return {
            "success": False,
            "reason": "answerer_parse_failed",
            "device_id": device_id,
            "raw_output_preview": (answerer_raw or "")[:300],
        }

    answer_text = _normalize_text(answerer_payload.get("answer")) or ""
    confidence = _normalize_text(answerer_payload.get("confidence"))
    outcome_raw = _normalize_text(answerer_payload.get("outcome"))
    valid_outcomes = {"derivable", "synthesis", "gap_or_conflict"}
    outcome = outcome_raw if outcome_raw in valid_outcomes else "derivable"
    reasoning = _normalize_text(answerer_payload.get("reasoning"))
    raw_sources = answerer_payload.get("source_page_ids") or []
    reported_source_ids = [
        str(pid) for pid in raw_sources
        if _is_valid_uuid(pid) and str(pid) in {str(p.get("id")) for p in full_pages}
    ]
    if not reported_source_ids:
        reported_source_ids = [str(p.get("id")) for p in full_pages if p.get("id")]
    target_page_id_raw = answerer_payload.get("target_page_id")
    target_page_id = (
        str(target_page_id_raw)
        if target_page_id_raw and _is_valid_uuid(target_page_id_raw)
        and str(target_page_id_raw) in {str(p.get("id")) for p in full_pages}
        else None
    )
    suggested_new_page = answerer_payload.get("suggested_new_page") or {}
    if not isinstance(suggested_new_page, dict):
        suggested_new_page = {}

    # Step 6: filing-back.
    filing_back: Dict[str, Any] = {
        "action": "none",
        "new_page_id": None,
        "target_page_id": None,
    }
    result_page_id: Optional[str] = None

    if outcome == "synthesis":
        suggested_project_key = _normalize_machine_key(
            suggested_new_page.get("project_key")
        ) or UNASSIGNED_PROJECT_KEY
        project = projects_by_key.get(suggested_project_key)
        new_project_id = _normalize_text(project.get("id")) if project else None
        new_category = _normalize_text(suggested_new_page.get("category")) or "query_answer"
        new_title = _normalize_text(suggested_new_page.get("title")) or f"Q: {question_text[:60]}"

        base_key = _derive_page_key(
            project_key=suggested_project_key,
            category=new_category,
            title=new_title,
        ) or _normalize_machine_key(new_title) or f"query-answer-{uuid_module.uuid4().hex[:8]}"
        page_key = _unique_page_key(supabase, device_id, base_key)

        new_body = _build_query_answer_body(
            question=question_text,
            answer=answer_text,
            source_pages=full_pages,
        )
        new_summary = _derive_summary(suggested_new_page.get("summary_one_liner"), answer_text)
        now_iso = _iso_now()

        new_row: Dict[str, Any] = {
            "device_id": device_id,
            "project_id": new_project_id,
            "category": new_category,
            "page_key": page_key,
            "title": new_title,
            "body": new_body,
            "kind": "query_answer",
            "status": "active",
            "version": 1,
            "source_fact_ids": [],
            "last_ingest_at": now_iso,
            "created_at": now_iso,
            "updated_at": now_iso,
        }
        if workspace_id:
            new_row["workspace_id"] = workspace_id

        try:
            insert_result = supabase.table(WIKI_TABLE).insert(new_row).execute()
            inserted = insert_result.data or []
            if inserted and inserted[0].get("id"):
                result_page_id = str(inserted[0]["id"])
        except Exception as exc:
            return {
                "success": False,
                "reason": f"new_page_insert_error: {str(exc)[:200]}",
                "device_id": device_id,
            }

        if result_page_id:
            # Add corresponding index row so future queries can surface this synthesis.
            try:
                index_row = {
                    "page_id": result_page_id,
                    "device_id": device_id,
                    "title": new_title,
                    "project_id": new_project_id,
                    "project_key": suggested_project_key,
                    "project_name": _normalize_text(project.get("display_name")) if project else None,
                    "category": new_category,
                    "page_key": page_key,
                    "kind": "query_answer",
                    "summary_one_liner": new_summary,
                    "source_count": 0,
                    "updated_at": now_iso,
                }
                if workspace_id:
                    index_row["workspace_id"] = workspace_id
                supabase.table(WIKI_INDEX_TABLE).insert(index_row).execute()
            except Exception:
                # Index insert must not break the main flow.
                pass

            filing_back = {
                "action": "new_page",
                "new_page_id": result_page_id,
                "target_page_id": None,
            }
    elif outcome == "gap_or_conflict":
        filing_back = {
            "action": "flag",
            "new_page_id": None,
            "target_page_id": target_page_id,
        }
    # outcome == 'derivable' keeps filing_back.action == 'none'.

    # Step 7: write log row.
    log_id = _insert_log(
        supabase=supabase,
        device_id=device_id,
        workspace_id=workspace_id,
        operation="query",
        question=question_text,
        answer=answer_text,
        confidence=confidence,
        outcome=outcome,
        reasoning=reasoning,
        source_page_ids=reported_source_ids,
        target_page_id=target_page_id if outcome == "gap_or_conflict" else None,
        result_page_id=result_page_id,
        llm_provider=provider_name,
        llm_model=model_name,
        meta={
            "selected_page_ids": selected_page_ids,
            "selector_reasoning": selector_payload.get("reasoning"),
            "max_pages": max_pages,
            "index_size": len(index_rows),
        },
    )

    # Build response source_pages summary (lightweight metadata only).
    source_pages_summary: List[Dict[str, Any]] = []
    for page in full_pages:
        pid = str(page.get("id") or "")
        if pid in reported_source_ids:
            source_pages_summary.append({
                "id": pid,
                "title": _normalize_text(page.get("title")),
                "project_name": _normalize_text(page.get("project_name")),
                "category": _normalize_text(page.get("category")),
                "page_key": _normalize_text(page.get("page_key")),
                "kind": _normalize_text(page.get("kind")),
            })

    return {
        "success": True,
        "query_id": log_id,
        "device_id": device_id,
        "question": question_text,
        "answer": answer_text,
        "confidence": confidence,
        "outcome": outcome,
        "reasoning": reasoning,
        "source_pages": source_pages_summary,
        "selected_page_ids": selected_page_ids,
        "filing_back": filing_back,
        "created_at": _iso_now(),
    }
