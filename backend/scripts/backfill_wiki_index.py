"""
Backfill zerotouch_wiki_index from existing zerotouch_wiki_pages.

Usage:
  set -a && source .env && set +a
  python3 scripts/backfill_wiki_index.py --device-id amical-db-test

For each wiki page of the target device, this script clears any existing
index row and inserts a fresh one. summary_one_liner is derived from the
first sentence of body (or title as a fallback) and clipped to 120 chars.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from supabase import Client, create_client


WIKI_TABLE = "zerotouch_wiki_pages"
WIKI_INDEX_TABLE = "zerotouch_wiki_index"
PROJECT_TABLE = "zerotouch_workspace_projects"
SUMMARY_MAX_CHARS = 120


def _iso_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _normalize_text(value: Any) -> Optional[str]:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _derive_summary(body: Optional[str], title: Optional[str]) -> str:
    # Use the first sentence from body (split on Japanese or ASCII period),
    # or fall back to title, and clip to SUMMARY_MAX_CHARS.
    source = _normalize_text(body)
    if source:
        normalized = re.sub(r"\s+", " ", source).strip()
        # Split on first Japanese period or ASCII period.
        parts = re.split(r"(?<=[。．.])", normalized, maxsplit=1)
        first = parts[0].strip() if parts else normalized
        if not first:
            first = normalized
        return first[:SUMMARY_MAX_CHARS]
    title_text = _normalize_text(title) or "(no summary)"
    return title_text[:SUMMARY_MAX_CHARS]


def _build_project_lookup(project_rows: List[Dict[str, Any]]) -> Dict[str, Dict[str, Any]]:
    lookup: Dict[str, Dict[str, Any]] = {}
    for row in project_rows:
        row_id = _normalize_text(row.get("id"))
        if row_id:
            lookup[row_id] = row
    return lookup


def _fetch_pages(supabase: Client, device_id: str) -> List[Dict[str, Any]]:
    rows = (
        supabase.table(WIKI_TABLE)
        .select(
            "id, device_id, workspace_id, title, body, project_id, "
            "category, page_key, kind, source_fact_ids, updated_at"
        )
        .eq("device_id", device_id)
        .execute()
        .data
        or []
    )
    return rows


def _fetch_projects(supabase: Client, workspace_ids: List[str]) -> List[Dict[str, Any]]:
    if not workspace_ids:
        return []
    try:
        rows = (
            supabase.table(PROJECT_TABLE)
            .select("id, workspace_id, project_key, display_name")
            .in_("workspace_id", list(set(workspace_ids)))
            .execute()
            .data
            or []
        )
    except Exception as exc:
        print(f"[backfill] Failed to load projects: {exc}", file=sys.stderr)
        return []
    return rows


def backfill(supabase: Client, device_id: str) -> int:
    pages = _fetch_pages(supabase, device_id)
    if not pages:
        print(f"[backfill] No wiki pages found for device_id={device_id}")
        return 0

    workspace_ids = [
        str(p.get("workspace_id")) for p in pages if _normalize_text(p.get("workspace_id"))
    ]
    project_rows = _fetch_projects(supabase, workspace_ids)
    projects_by_id = _build_project_lookup(project_rows)

    now_iso = _iso_now()
    inserted = 0
    for page in pages:
        page_id = _normalize_text(page.get("id"))
        if not page_id:
            continue

        project_id = _normalize_text(page.get("project_id"))
        project = projects_by_id.get(project_id) if project_id else None
        project_key = _normalize_text(project.get("project_key")) if project else None
        project_name = _normalize_text(project.get("display_name")) if project else None

        body = page.get("body") or ""
        title = _normalize_text(page.get("title")) or "(no title)"
        summary = _derive_summary(body, title)
        source_count = len(page.get("source_fact_ids") or [])

        row: Dict[str, Any] = {
            "page_id": page_id,
            "device_id": device_id,
            "title": title,
            "project_id": project_id,
            "project_key": project_key,
            "project_name": project_name,
            "category": _normalize_text(page.get("category")),
            "page_key": _normalize_text(page.get("page_key")),
            "kind": _normalize_text(page.get("kind")),
            "summary_one_liner": summary,
            "source_count": source_count,
            "updated_at": now_iso,
        }
        workspace_id = _normalize_text(page.get("workspace_id"))
        if workspace_id:
            row["workspace_id"] = workspace_id

        try:
            supabase.table(WIKI_INDEX_TABLE).delete().eq("page_id", page_id).execute()
            supabase.table(WIKI_INDEX_TABLE).insert(row).execute()
            inserted += 1
            print(f"[backfill] Indexed page_id={page_id} title={title[:40]}")
        except Exception as exc:
            print(f"[backfill] Failed for page_id={page_id}: {exc}", file=sys.stderr)

    print(f"[backfill] Backfilled {inserted} rows")
    return inserted


def main() -> int:
    parser = argparse.ArgumentParser(description="Backfill zerotouch_wiki_index")
    parser.add_argument("--device-id", required=True, help="Target device_id")
    args = parser.parse_args()

    url = os.getenv("SUPABASE_URL")
    key = os.getenv("SUPABASE_SERVICE_ROLE_KEY")
    if not url or not key:
        print("[backfill] SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY is not set", file=sys.stderr)
        return 1

    supabase = create_client(url, key)
    backfill(supabase, args.device_id)
    return 0


if __name__ == "__main__":
    sys.exit(main())
