"""
Reprocess topics with context injection (Phase 1 + Phase 2).

This script re-runs scoring and annotation for a given device's topics,
now with context_preamble injected into prompts from zerotouch_context_profiles.

Usage:
    cd backend
    python3 ../experiments/amical/reprocess_with_context.py \
        --device-id virtual-amical-2026-03-28-import \
        [--provider openai] [--model gpt-4.1-mini] \
        [--phase scoring|annotation|both] \
        [--dry-run]

What it does:
    1. Fetch all topics for the device
    2. For each topic: re-run Phase 1 (scoring) with force=True
    3. For Lv.3+ topics: re-run Phase 2 (annotation) with force=True
    4. Context is automatically fetched from zerotouch_context_profiles via workspace_id
"""

from __future__ import annotations

import argparse
import os
import sys
import time
from typing import Optional

# Run from backend/ directory so that service imports work
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)) + "/../../backend")

from dotenv import load_dotenv
load_dotenv(os.path.join(os.path.dirname(__file__), "../../backend/.env"))

from supabase import create_client, Client
from services.topic_scorer import score_topic
from services.topic_annotator import annotate_topic
from services.llm_providers import LLMFactory, LLMProvider

TOPIC_TABLE = "zerotouch_conversation_topics"


def get_supabase() -> Client:
    url = os.environ["SUPABASE_URL"]
    key = os.environ["SUPABASE_SERVICE_ROLE_KEY"]
    return create_client(url, key)


def get_llm_service(provider: str, model: str) -> LLMProvider:
    return LLMFactory.create(provider=provider, model=model)


def fetch_topics(supabase: Client, device_id: str) -> list[dict]:
    rows = (
        supabase.table(TOPIC_TABLE)
        .select("id, final_title, importance_level, distillation_status, workspace_id")
        .eq("device_id", device_id)
        .order("created_at", desc=False)
        .execute()
        .data
        or []
    )
    return rows


def run_reprocess(
    device_id: str,
    provider: str,
    model: str,
    phase: str,
    dry_run: bool,
) -> None:
    supabase = get_supabase()
    llm = get_llm_service(provider, model) if not dry_run else None

    topics = fetch_topics(supabase, device_id)
    print(f"[reprocess] Found {len(topics)} topics for device={device_id}")

    score_done = score_skipped = score_failed = 0
    annotate_done = annotate_skipped = annotate_failed = 0

    for i, topic in enumerate(topics):
        topic_id = topic["id"]
        title = (topic.get("final_title") or "(untitled)")[:50]
        importance = topic.get("importance_level")

        # Phase 1: Scoring
        if phase in ("scoring", "both"):
            if dry_run:
                print(f"  [dry-run] Would re-score topic {i+1}/{len(topics)}: {title}")
                score_done += 1
            else:
                print(f"  [scoring] {i+1}/{len(topics)}: {title}")
                result = score_topic(
                    supabase=supabase,
                    topic_id=topic_id,
                    llm_service=llm,
                    force=True,
                )
                if result.get("scored"):
                    new_level = result.get("importance_level")
                    print(f"    -> Lv.{new_level} (was Lv.{importance}) [{result.get('method')}]")
                    score_done += 1
                    importance = new_level  # use new score for annotation decision
                elif result.get("reason") == "llm_error":
                    print(f"    -> ERROR: {result.get('reason')}")
                    score_failed += 1
                else:
                    print(f"    -> skipped: {result.get('reason')}")
                    score_skipped += 1
                time.sleep(0.3)  # rate limit

        # Phase 2: Annotation (Lv.3+ only)
        if phase in ("annotation", "both"):
            if importance is None or importance < 3:
                annotate_skipped += 1
                continue

            if dry_run:
                print(f"  [dry-run] Would re-annotate topic {i+1}/{len(topics)}: {title} (Lv.{importance})")
                annotate_done += 1
            else:
                print(f"  [annotate] {i+1}/{len(topics)}: {title} (Lv.{importance})")
                result = annotate_topic(
                    supabase=supabase,
                    topic_id=topic_id,
                    llm_service=llm,
                    force=True,
                )
                if result.get("annotated"):
                    print(f"    -> {result.get('fact_count')} facts")
                    annotate_done += 1
                elif result.get("reason") == "llm_error":
                    print(f"    -> ERROR: {result.get('reason')}")
                    annotate_failed += 1
                else:
                    print(f"    -> skipped: {result.get('reason')}")
                    annotate_skipped += 1
                time.sleep(0.3)

    print()
    print("=" * 50)
    print(f"[reprocess] Complete")
    if phase in ("scoring", "both"):
        print(f"  Scoring:    done={score_done}, skipped={score_skipped}, failed={score_failed}")
    if phase in ("annotation", "both"):
        print(f"  Annotation: done={annotate_done}, skipped={annotate_skipped}, failed={annotate_failed}")
    if dry_run:
        print("  (dry-run: no actual changes made)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Reprocess topics with context injection")
    parser.add_argument("--device-id", required=True, help="Device ID to reprocess")
    parser.add_argument("--provider", default="openai", help="LLM provider (default: openai)")
    parser.add_argument("--model", default="gpt-4.1-mini", help="LLM model (default: gpt-4.1-mini)")
    parser.add_argument(
        "--phase",
        choices=["scoring", "annotation", "both"],
        default="both",
        help="Which phase to run (default: both)",
    )
    parser.add_argument("--dry-run", action="store_true", help="Show what would be done without making changes")
    args = parser.parse_args()

    run_reprocess(
        device_id=args.device_id,
        provider=args.provider,
        model=args.model,
        phase=args.phase,
        dry_run=args.dry_run,
    )
