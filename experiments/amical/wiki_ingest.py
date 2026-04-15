"""
Wiki Ingest: Compile zerotouch_facts into zerotouch_wiki_pages.

Reads annotated facts and compiles them into coherent wiki pages
using LLM-driven grouping (Karpathy wiki approach).

Usage (run from backend/ directory):
    # Batch mode (rebuild wiki from scratch):
    python3 ../experiments/amical/wiki_ingest.py \
        --device-id amical-db-test \
        --mode batch \
        --provider openai --model gpt-4.1-mini

    # Incremental mode (process new facts only, default):
    python3 ../experiments/amical/wiki_ingest.py \
        --device-id amical-db-test \
        --provider openai --model gpt-4.1-mini
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List

SCRIPT_DIR = Path(__file__).resolve().parent
BACKEND_DIR = SCRIPT_DIR.parent.parent / "backend"
DEFAULT_BACKEND_ENV = BACKEND_DIR / ".env"
DEFAULT_DEVICE_ID = "amical-db-test"

WIKI_TABLE = "zerotouch_wiki_pages"
FACTS_TABLE = "zerotouch_facts"
TOPICS_TABLE = "zerotouch_conversation_topics"


# ---------------------------------------------------------------------------
# Bootstrap
# ---------------------------------------------------------------------------

def bootstrap_backend_env(env_path: Path) -> None:
    if not env_path.exists():
        raise SystemExit(f"Backend .env not found: {env_path}")
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())
    sys.path.insert(0, str(BACKEND_DIR))


def create_supabase():
    from supabase import create_client  # type: ignore
    return create_client(
        os.environ["SUPABASE_URL"],
        os.environ["SUPABASE_SERVICE_ROLE_KEY"],
    )


def execute_with_retry(fn, label="", retries=3, delay=1.5):
    for attempt in range(retries):
        try:
            return fn()
        except Exception as exc:
            if attempt + 1 >= retries:
                raise
            print(f"  [retry {attempt+1}/{retries}] {label}: {exc}")
            time.sleep(delay)


# ---------------------------------------------------------------------------
# Data fetching
# ---------------------------------------------------------------------------

def fetch_facts(supabase, device_id: str) -> List[Dict[str, Any]]:
    """Fetch all annotated facts for the device, with topic date."""
    # Step 1: get topic IDs and dates for this device
    topic_rows = execute_with_retry(
        lambda: (
            supabase.table(TOPICS_TABLE)
            .select("id, start_at")
            .eq("device_id", device_id)
            .not_("start_at", "is", "null")
            .execute()
            .data or []
        ),
        label="fetch topics",
    )
    if not topic_rows:
        return []

    topic_dates = {t["id"]: (t.get("start_at") or "")[:10] for t in topic_rows}
    topic_ids = list(topic_dates.keys())

    # Step 2: fetch facts for those topics
    all_facts: List[Dict[str, Any]] = []
    # Supabase .in() has size limits; chunk if needed
    chunk_size = 50
    for i in range(0, len(topic_ids), chunk_size):
        chunk = topic_ids[i : i + chunk_size]
        rows = execute_with_retry(
            lambda c=chunk: (
                supabase.table(FACTS_TABLE)
                .select(
                    "id, fact_text, importance_level, ttl_type, "
                    "intents, categories, topic_id, created_at"
                )
                .in_("topic_id", c)
                .order("created_at", desc=False)
                .execute()
                .data or []
            ),
            label=f"fetch facts chunk {i}",
        )
        for row in rows:
            row["date"] = topic_dates.get(row["topic_id"], "")
        all_facts.extend(rows)

    return all_facts


def fetch_wiki_pages(supabase, device_id: str) -> List[Dict[str, Any]]:
    """Fetch existing wiki pages for the device."""
    return execute_with_retry(
        lambda: (
            supabase.table(WIKI_TABLE)
            .select("*")
            .eq("device_id", device_id)
            .order("created_at", desc=False)
            .execute()
            .data or []
        ),
        label="fetch wiki pages",
    )


def delete_wiki_pages(supabase, device_id: str) -> int:
    """Delete all wiki pages for the device."""
    result = execute_with_retry(
        lambda: (
            supabase.table(WIKI_TABLE)
            .delete()
            .eq("device_id", device_id)
            .execute()
        ),
        label="delete wiki pages",
    )
    return len(result.data) if result.data else 0


# ---------------------------------------------------------------------------
# LLM helpers
# ---------------------------------------------------------------------------

def extract_json(text: str) -> Any:
    """Extract JSON from LLM response (handles markdown wrapping)."""
    match = re.search(r"```(?:json)?\s*\n?(.*?)```", text, re.DOTALL)
    if match:
        return json.loads(match.group(1).strip())
    # Try direct parse
    text = text.strip()
    # Find first [ or {
    for i, ch in enumerate(text):
        if ch in ("[", "{"):
            return json.loads(text[i:])
    return json.loads(text)


# ---------------------------------------------------------------------------
# Prompts
# ---------------------------------------------------------------------------

BATCH_PROMPT = """\
You are compiling a personal knowledge wiki from conversation facts.

Each fact was extracted from ambient recordings of a founder/engineer
who works across multiple projects.

Group related facts into wiki pages. Each page = a coherent knowledge unit.

## Classification

### Theme (domain):
- wealthpark: WealthPark product, business, team activities
- watchme: WatchMe/ZeroTouch development, audio analysis
- personal: Personal matters, life admin, career
- tools: Software tools, productivity apps
- ideas: New project ideas, brainstorming
- creative: Creative work, design, photography, video

### Kind (knowledge type):
- decision: A specific choice or determination
- rule: An operational rule or policy to follow
- insight: Knowledge, observation, or lesson learned
- procedure: Steps or process documentation
- task: An ongoing or planned action item

## Facts

{facts_json}

## Output

Return ONLY a JSON array. No markdown fences, no explanation.
[
  {{
    "title": "concise descriptive Japanese title",
    "theme": "theme_value",
    "kind": "kind_value",
    "body": "Synthesized readable Japanese text integrating all grouped facts. Write prose, not bullet lists.",
    "fact_ids": ["uuid1", "uuid2"]
  }}
]

Rules:
- Group closely related facts into one page
- A standalone fact gets its own page
- Every fact_id must appear in exactly one page
- Titles and bodies in Japanese
- Body should synthesize and provide context, not just restate facts"""


INCREMENTAL_PROMPT = """\
You are updating a personal knowledge wiki with new facts.

## Existing wiki pages

{existing_pages_json}

## New facts

{new_facts_json}

## Task

For each new fact, decide: MERGE into an existing page, or CREATE a new page.
New facts that relate to each other can be grouped into one CREATE.

Return ONLY a JSON array. No markdown fences.
[
  {{
    "action": "merge",
    "page_id": "existing-page-uuid",
    "fact_ids": ["new-fact-uuid"],
    "updated_body": "Full updated page body integrating the new facts. Japanese."
  }}
  OR
  {{
    "action": "create",
    "fact_ids": ["uuid1", "uuid2"],
    "title": "Japanese title",
    "theme": "theme_value",
    "kind": "kind_value",
    "body": "Synthesized Japanese text."
  }}
]

Theme: wealthpark, watchme, personal, tools, ideas, creative
Kind: decision, rule, insight, procedure, task

Rules:
- Merge if clearly about the same topic as an existing page
- Create if genuinely new
- Multiple related new facts can share one create/merge action
- Every new fact_id must appear exactly once
- Write in Japanese"""


# ---------------------------------------------------------------------------
# Ingest modes
# ---------------------------------------------------------------------------

def batch_ingest(
    facts: List[Dict],
    llm,
    supabase,
    device_id: str,
    dry_run: bool,
) -> List[Dict]:
    """Compile all facts into wiki pages from scratch."""
    facts_for_prompt = [
        {
            "id": f["id"],
            "fact_text": f["fact_text"],
            "date": f["date"],
            "ttl_type": f["ttl_type"],
            "intents": f["intents"],
            "categories": f["categories"],
            "importance_level": f["importance_level"],
        }
        for f in facts
    ]

    prompt = BATCH_PROMPT.format(
        facts_json=json.dumps(facts_for_prompt, ensure_ascii=False, indent=2)
    )

    print(f"  [LLM] Sending {len(facts)} facts for batch compilation...")
    response_text = llm.generate(prompt)

    try:
        pages = extract_json(response_text)
    except (json.JSONDecodeError, ValueError) as exc:
        print(f"  [ERROR] Failed to parse LLM response: {exc}")
        print(f"  Response (first 500 chars): {response_text[:500]}")
        raise

    print(f"  [LLM] Compiled into {len(pages)} wiki pages")

    # Validate: every fact should appear exactly once
    all_fact_ids = {f["id"] for f in facts}
    assigned: set[str] = set()
    for page in pages:
        for fid in page.get("fact_ids", []):
            if fid in assigned:
                print(f"  [WARN] Duplicate assignment: {fid[:8]}...")
            assigned.add(fid)

    missing = all_fact_ids - assigned
    if missing:
        print(f"  [WARN] {len(missing)} facts not assigned to any page")

    if dry_run:
        for p in pages:
            fcount = len(p.get("fact_ids", []))
            print(f"  [dry-run] {p.get('kind','?'):10s} | {p.get('theme','?'):12s} | {p['title']} ({fcount} facts)")
        return pages

    # Insert into DB
    now_iso = datetime.now(timezone.utc).isoformat()
    inserted = []
    for page in pages:
        payload = {
            "id": str(uuid.uuid4()),
            "device_id": device_id,
            "title": page["title"],
            "body": page["body"],
            "theme": page.get("theme", "general"),
            "kind": page.get("kind", "insight"),
            "source_fact_ids": page.get("fact_ids", []),
            "status": "active",
            "version": 1,
            "last_ingest_at": now_iso,
            "created_at": now_iso,
            "updated_at": now_iso,
        }
        execute_with_retry(
            lambda p=payload: supabase.table(WIKI_TABLE).insert(p).execute(),
            label=f"insert '{page['title'][:30]}'",
        )
        inserted.append(payload)
        fcount = len(page.get("fact_ids", []))
        print(
            f"  [wiki] {page.get('kind','?'):10s} | "
            f"{page.get('theme','?'):12s} | "
            f"{page['title']} ({fcount} facts)"
        )

    return inserted


def incremental_ingest(
    new_facts: List[Dict],
    existing_pages: List[Dict],
    llm,
    supabase,
    device_id: str,
    dry_run: bool,
) -> None:
    """Process only new facts against existing wiki pages."""
    existing_for_prompt = [
        {
            "id": p["id"],
            "title": p["title"],
            "theme": p["theme"],
            "kind": p["kind"],
            "body": (p["body"][:300] + "...") if len(p["body"]) > 300 else p["body"],
            "fact_count": len(p.get("source_fact_ids") or []),
        }
        for p in existing_pages
    ]

    new_for_prompt = [
        {
            "id": f["id"],
            "fact_text": f["fact_text"],
            "date": f["date"],
            "ttl_type": f["ttl_type"],
            "intents": f["intents"],
            "categories": f["categories"],
        }
        for f in new_facts
    ]

    prompt = INCREMENTAL_PROMPT.format(
        existing_pages_json=json.dumps(existing_for_prompt, ensure_ascii=False, indent=2),
        new_facts_json=json.dumps(new_for_prompt, ensure_ascii=False, indent=2),
    )

    print(
        f"  [LLM] Processing {len(new_facts)} new facts "
        f"against {len(existing_pages)} existing pages..."
    )
    response_text = llm.generate(prompt)

    try:
        actions = extract_json(response_text)
    except (json.JSONDecodeError, ValueError) as exc:
        print(f"  [ERROR] Failed to parse LLM response: {exc}")
        print(f"  Response (first 500 chars): {response_text[:500]}")
        raise

    now_iso = datetime.now(timezone.utc).isoformat()
    merge_count = 0
    create_count = 0

    for action in actions:
        act_type = action.get("action", "")

        if act_type == "merge":
            page_id = action["page_id"]
            fact_ids = action.get("fact_ids", [action["fact_id"]] if "fact_id" in action else [])
            updated_body = action["updated_body"]

            existing = next((p for p in existing_pages if p["id"] == page_id), None)
            if not existing:
                print(f"  [WARN] merge target not found: {page_id[:8]}...")
                continue

            if dry_run:
                print(f"  [dry-run merge] {len(fact_ids)} facts -> {existing['title'][:40]}")
            else:
                updated_fact_ids = list(
                    set((existing.get("source_fact_ids") or []) + fact_ids)
                )
                execute_with_retry(
                    lambda pid=page_id, body=updated_body, fids=updated_fact_ids, ver=existing["version"]: (
                        supabase.table(WIKI_TABLE)
                        .update({
                            "body": body,
                            "source_fact_ids": fids,
                            "version": ver + 1,
                            "last_ingest_at": now_iso,
                            "updated_at": now_iso,
                        })
                        .eq("id", pid)
                        .execute()
                    ),
                    label=f"update '{existing['title'][:30]}'",
                )
                # Update local copy for subsequent actions in this batch
                existing["source_fact_ids"] = updated_fact_ids
                existing["body"] = updated_body
                existing["version"] += 1
                print(f"  [merge] {len(fact_ids)} facts -> {existing['title'][:40]} (v{existing['version']})")
            merge_count += 1

        elif act_type == "create":
            fact_ids = action.get("fact_ids", [])
            if dry_run:
                print(f"  [dry-run create] {action['title']} ({len(fact_ids)} facts)")
            else:
                payload = {
                    "id": str(uuid.uuid4()),
                    "device_id": device_id,
                    "title": action["title"],
                    "body": action["body"],
                    "theme": action.get("theme", "general"),
                    "kind": action.get("kind", "insight"),
                    "source_fact_ids": fact_ids,
                    "status": "active",
                    "version": 1,
                    "last_ingest_at": now_iso,
                    "created_at": now_iso,
                    "updated_at": now_iso,
                }
                execute_with_retry(
                    lambda p=payload: supabase.table(WIKI_TABLE).insert(p).execute(),
                    label=f"create '{action['title'][:30]}'",
                )
                # Add to existing_pages for subsequent actions
                existing_pages.append(payload)
                print(
                    f"  [create] {action.get('kind','?'):10s} | "
                    f"{action.get('theme','?'):12s} | "
                    f"{action['title']} ({len(fact_ids)} facts)"
                )
            create_count += 1

    print(f"\n  Merged: {merge_count} | Created: {create_count}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Wiki Ingest: compile facts into wiki pages",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--device-id", default=DEFAULT_DEVICE_ID)
    parser.add_argument(
        "--mode",
        choices=["batch", "incremental"],
        default="incremental",
        help="batch = rebuild from scratch; incremental = process new facts only (default)",
    )
    parser.add_argument("--provider", default="openai")
    parser.add_argument("--model", default="gpt-4.1-mini")
    parser.add_argument("--backend-env", default=str(DEFAULT_BACKEND_ENV))
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    bootstrap_backend_env(Path(args.backend_env))

    from services.llm_providers import LLMFactory  # type: ignore

    supabase = create_supabase()
    llm = LLMFactory.create(provider=args.provider, model=args.model)

    facts = fetch_facts(supabase, args.device_id)
    existing_pages = fetch_wiki_pages(supabase, args.device_id)

    print(json.dumps({
        "mode": args.mode,
        "device_id": args.device_id,
        "total_facts": len(facts),
        "existing_wiki_pages": len(existing_pages),
        "model": f"{args.provider}/{args.model}",
        "dry_run": args.dry_run,
    }, ensure_ascii=False, indent=2))

    if args.mode == "batch":
        if not args.dry_run and existing_pages:
            deleted = delete_wiki_pages(supabase, args.device_id)
            print(f"  [cleanup] Deleted {deleted} existing wiki pages")

        pages = batch_ingest(facts, llm, supabase, args.device_id, args.dry_run)

        print(f"\n{'=' * 50}")
        print(json.dumps({
            "mode": "batch",
            "facts_processed": len(facts),
            "wiki_pages_created": len(pages),
            "dry_run": args.dry_run,
        }, ensure_ascii=False, indent=2))

    else:  # incremental
        ingested_ids: set[str] = set()
        for page in existing_pages:
            for fid in (page.get("source_fact_ids") or []):
                ingested_ids.add(fid)

        new_facts = [f for f in facts if f["id"] not in ingested_ids]

        if not new_facts:
            print("  No new facts to ingest. All facts already in wiki pages.")
            return

        print(f"  {len(new_facts)} new facts to process")
        incremental_ingest(
            new_facts, existing_pages, llm, supabase, args.device_id, args.dry_run
        )


if __name__ == "__main__":
    main()
