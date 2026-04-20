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
from services.workspace_registry import ensure_workspace_projects, fetch_context_preamble

FACT_TABLE = "zerotouch_facts"
WIKI_TABLE = "zerotouch_wiki_pages"
WIKI_INDEX_TABLE = "zerotouch_wiki_index"
WIKI_LOG_TABLE = "zerotouch_wiki_log"
MIN_IMPORTANCE = 3
UNASSIGNED_PROJECT_KEY = "unassigned"
SUMMARY_MAX_CHARS = 120


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
        .select("id, project_id, page_key, title, body, category, kind, version, source_fact_ids")
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


def _normalize_text(value: Any) -> Optional[str]:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _normalize_machine_key(value: Any) -> Optional[str]:
    text = _normalize_text(value)
    if not text:
        return None

    chars: List[str] = []
    previous_sep = False
    for raw_char in text.casefold():
        if raw_char.isalnum():
            chars.append(raw_char)
            previous_sep = False
            continue
        if raw_char in {" ", "-", "_", "/", "&", ":"}:
            if not previous_sep:
                chars.append("-")
                previous_sep = True

    key = "".join(chars).strip("-")
    return key or None


def _derive_page_key(project_key: Optional[str], category: Optional[str], title: str) -> Optional[str]:
    parts = [
        _normalize_machine_key(project_key) if project_key and project_key != UNASSIGNED_PROJECT_KEY else None,
        _normalize_machine_key(category),
        _normalize_machine_key(title),
    ]
    joined = "-".join([part for part in parts if part])
    return joined or None


def _build_project_maps(project_rows: List[Dict[str, Any]]) -> tuple[Dict[str, Dict[str, Any]], Dict[str, Dict[str, Any]]]:
    projects_by_id: Dict[str, Dict[str, Any]] = {}
    projects_by_key: Dict[str, Dict[str, Any]] = {}
    for row in project_rows:
        project_id = _normalize_text(row.get("id"))
        project_key = _normalize_machine_key(row.get("project_key"))
        if project_id:
            projects_by_id[project_id] = row
        if project_key:
            projects_by_key[project_key] = row
    return projects_by_id, projects_by_key


def _decorate_existing_pages(
    existing_pages: List[Dict[str, Any]],
    projects_by_id: Dict[str, Dict[str, Any]],
) -> List[Dict[str, Any]]:
    decorated: List[Dict[str, Any]] = []
    for page in existing_pages:
        project_id = _normalize_text(page.get("project_id"))
        project = projects_by_id.get(project_id) if project_id else None
        decorated.append({
            **page,
            "project_key": _normalize_machine_key(project.get("project_key")) if project else None,
        })
    return decorated


def _derive_summary_one_liner(provided: Any, body: str, title: str) -> str:
    # Prefer an explicitly provided summary, falling back to heuristic from body or title.
    text = _normalize_text(provided)
    if text:
        return text[:SUMMARY_MAX_CHARS]

    source = body or title or ""
    # Collapse whitespace and newlines before clipping.
    normalized = re.sub(r"\s+", " ", source).strip()
    if not normalized:
        return (title or "(no summary)")[:SUMMARY_MAX_CHARS]
    return normalized[:SUMMARY_MAX_CHARS]


def _upsert_index_row(
    supabase: Client,
    page_id: str,
    device_id: str,
    workspace_id: Optional[str],
    title: str,
    project_id: Optional[str],
    project_key: Optional[str],
    project_name: Optional[str],
    category: Optional[str],
    page_key: Optional[str],
    kind: Optional[str],
    summary_one_liner: str,
    source_count: int,
    now_iso: str,
) -> None:
    # Upsert one row into zerotouch_wiki_index keyed by page_id.
    row = {
        "page_id": page_id,
        "device_id": device_id,
        "title": title,
        "project_id": project_id,
        "project_key": project_key,
        "project_name": project_name,
        "category": category,
        "page_key": page_key,
        "kind": kind,
        "summary_one_liner": summary_one_liner,
        "source_count": source_count,
        "updated_at": now_iso,
    }
    if workspace_id:
        row["workspace_id"] = workspace_id

    existing = (
        supabase.table(WIKI_INDEX_TABLE)
        .select("page_id")
        .eq("page_id", page_id)
        .limit(1)
        .execute()
        .data
        or []
    )
    if existing:
        supabase.table(WIKI_INDEX_TABLE).update(row).eq("page_id", page_id).execute()
    else:
        supabase.table(WIKI_INDEX_TABLE).insert(row).execute()


def _build_existing_page_map(existing_pages: List[Dict]) -> Dict[str, Dict]:
    mapping: Dict[str, Dict] = {}
    for page in existing_pages:
        page_key = _normalize_machine_key(page.get("page_key"))
        if page_key:
            mapping[page_key] = page
    return mapping


def _upsert_wiki_pages(
    supabase: Client,
    device_id: str,
    workspace_id: Optional[str],
    llm_pages: List[Dict],
    existing_map: Dict[str, Dict],
    projects_by_key: Dict[str, Dict[str, Any]],
) -> List[Dict]:
    now = _iso_now()
    results = []

    for page_data in llm_pages:
        project_key = _normalize_machine_key(page_data.get("project_key")) or UNASSIGNED_PROJECT_KEY
        category = _normalize_text(page_data.get("category"))
        title = (page_data.get("title") or "").strip()
        body = (page_data.get("body") or "").strip()
        kind = page_data.get("kind") or None
        page_key = _normalize_machine_key(page_data.get("page_key")) or _derive_page_key(
            project_key=project_key,
            category=category,
            title=title,
        )
        source_fact_ids = [
            str(fid) for fid in (page_data.get("source_fact_ids") or [])
            if fid and _is_valid_uuid(str(fid))
        ]

        if not title or not body or not category or not page_key:
            continue

        project = projects_by_key.get(project_key)
        project_id = _normalize_text(project.get("id")) if project else None
        project_name = _normalize_text(project.get("display_name")) if project else None
        summary_one_liner = _derive_summary_one_liner(
            provided=page_data.get("summary_one_liner"),
            body=body,
            title=title,
        )
        existing = existing_map.get(page_key)

        if existing:
            new_version = (existing.get("version") or 1) + 1
            merged_fact_ids = list(
                {str(fid) for fid in (existing.get("source_fact_ids") or []) if fid and _is_valid_uuid(str(fid))}
                | set(source_fact_ids)
            )
            supabase.table(WIKI_TABLE).update({
                "title": title,
                "body": body,
                "project_id": project_id,
                "category": category,
                "page_key": page_key,
                "kind": kind,
                "version": new_version,
                "source_fact_ids": merged_fact_ids,
                "last_ingest_at": now,
                "updated_at": now,
            }).eq("id", existing["id"]).execute()
            page_id = str(existing["id"])
            source_count = len(merged_fact_ids)
            _upsert_index_row(
                supabase=supabase,
                page_id=page_id,
                device_id=device_id,
                workspace_id=workspace_id,
                title=title,
                project_id=project_id,
                project_key=project_key,
                project_name=project_name,
                category=category,
                page_key=page_key,
                kind=kind,
                summary_one_liner=summary_one_liner,
                source_count=source_count,
                now_iso=now,
            )
            results.append({
                "title": title,
                "page_key": page_key,
                "action": "updated",
                "version": new_version,
                "page_id": page_id,
            })
        else:
            row = {
                "device_id": device_id,
                "project_id": project_id,
                "category": category,
                "page_key": page_key,
                "title": title,
                "body": body,
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
            insert_result = supabase.table(WIKI_TABLE).insert(row).execute()
            inserted_rows = insert_result.data or []
            page_id = str(inserted_rows[0]["id"]) if inserted_rows and inserted_rows[0].get("id") else None
            source_count = len(source_fact_ids)
            if page_id:
                _upsert_index_row(
                    supabase=supabase,
                    page_id=page_id,
                    device_id=device_id,
                    workspace_id=workspace_id,
                    title=title,
                    project_id=project_id,
                    project_key=project_key,
                    project_name=project_name,
                    category=category,
                    page_key=page_key,
                    kind=kind,
                    summary_one_liner=summary_one_liner,
                    source_count=source_count,
                    now_iso=now,
                )
            results.append({
                "title": title,
                "page_key": page_key,
                "action": "created",
                "version": 1,
                "page_id": page_id,
            })

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
    project_rows = ensure_workspace_projects(supabase, workspace_id) if workspace_id else []
    projects_by_id, projects_by_key = _build_project_maps(project_rows)
    context_preamble = fetch_context_preamble(
        supabase,
        workspace_id,
        device_id=device_id,
    ) if workspace_id else ""

    prompt = build_wiki_ingest_prompt(
        facts=facts,
        existing_pages=_decorate_existing_pages(existing_pages, projects_by_id),
        available_projects=project_rows,
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
            projects_by_key=projects_by_key,
        )
    except Exception as exc:
        return {
            "device_id": device_id,
            "success": False,
            "reason": f"upsert_error: {str(exc)[:200]}",
            "facts_used": len(facts),
            "pages_result": [],
        }

    # Record ingest operation in the audit log.
    try:
        pages_created = sum(1 for r in pages_result if r.get("action") == "created")
        pages_updated = sum(1 for r in pages_result if r.get("action") == "updated")
        log_row: Dict[str, Any] = {
            "device_id": device_id,
            "operation": "ingest",
            "meta": {
                "facts_used": len(facts),
                "pages_created": pages_created,
                "pages_updated": pages_updated,
            },
        }
        if workspace_id:
            log_row["workspace_id"] = workspace_id
        provider_name = getattr(llm_service, "model_name", None)
        if provider_name:
            # model_name is like "openai/gpt-..." so split into provider/model.
            parts = str(provider_name).split("/", 1)
            log_row["llm_provider"] = parts[0]
            log_row["llm_model"] = parts[1] if len(parts) > 1 else None
        supabase.table(WIKI_LOG_TABLE).insert(log_row).execute()
    except Exception:
        # Log insert must not break the ingest response.
        pass

    return {
        "device_id": device_id,
        "success": True,
        "facts_used": len(facts),
        "pages_result": pages_result,
    }
