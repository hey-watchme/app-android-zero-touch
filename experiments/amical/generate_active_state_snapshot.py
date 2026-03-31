#!/usr/bin/env python3
"""Build a file-based active state snapshot from daily and spot Amical artifacts."""

from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

from stateful_daily_utils import (
    build_annotation_lookup,
    build_context_bundle,
    build_day_anchor,
    collect_annotation_values,
    collect_source_spot_ids,
    derive_task_kind,
    ensure_list,
    ensure_str_list,
    find_previous_date,
    load_daily_rollup,
    load_json,
    load_prior_snapshot,
    load_spot_wrapups,
    merge_unique_strings,
    normalize_string,
    recent_rollup_context,
    resolve_output_dir,
    stable_decision_key,
    stable_knowledge_key,
    stable_task_key,
    write_json,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--date", required=True, help="Target date in YYYY-MM-DD")
    parser.add_argument(
        "--artifacts-root",
        default="experiments/amical/artifacts",
        help="Artifacts root containing dataset folders and daily-rollups",
    )
    return parser.parse_args()


def merge_task(
    previous: Optional[Dict[str, Any]],
    current: Dict[str, Any],
) -> Dict[str, Any]:
    source_refs = merge_unique_strings(
        ensure_str_list((previous or {}).get("source_refs")),
        ensure_str_list(current.get("source_refs")),
    )
    evidence_refs = merge_unique_strings(
        ensure_str_list((previous or {}).get("evidence_refs")),
        ensure_str_list(current.get("evidence_refs")),
    )
    canonical_ids = merge_unique_strings(
        ensure_str_list((previous or {}).get("canonical_ids")),
        ensure_str_list(current.get("canonical_ids")),
    )
    source_spot_ids = merge_unique_strings(
        ensure_str_list((previous or {}).get("source_spot_ids")),
        ensure_str_list(current.get("source_spot_ids")),
    )

    return {
        "task_id": normalize_string((previous or {}).get("task_id")) or normalize_string(current.get("task_id")),
        "task_key": normalize_string((previous or {}).get("task_key")) or normalize_string(current.get("task_key")),
        "title": normalize_string(current.get("title")) or normalize_string((previous or {}).get("title")),
        "summary": normalize_string(current.get("summary")) or normalize_string((previous or {}).get("summary")),
        "status": "open",
        "priority": normalize_string(current.get("priority")) or normalize_string((previous or {}).get("priority")) or "medium",
        "task_kind": normalize_string(current.get("task_kind")) or normalize_string((previous or {}).get("task_kind")) or "other",
        "first_seen_at": normalize_string((previous or {}).get("first_seen_at")) or normalize_string(current.get("first_seen_at")),
        "last_seen_at": normalize_string(current.get("last_seen_at")) or normalize_string((previous or {}).get("last_seen_at")),
        "closed_at": None,
        "superseded_by": None,
        "source_refs": source_refs,
        "source_spot_ids": source_spot_ids,
        "evidence_refs": evidence_refs,
        "canonical_ids": canonical_ids,
        "notes": normalize_string(current.get("notes")) or normalize_string((previous or {}).get("notes")) or None,
    }


def merge_decision(
    previous: Optional[Dict[str, Any]],
    current: Dict[str, Any],
) -> Dict[str, Any]:
    return {
        "decision_id": normalize_string((previous or {}).get("decision_id")) or normalize_string(current.get("decision_id")),
        "decision_key": normalize_string((previous or {}).get("decision_key")) or normalize_string(current.get("decision_key")),
        "statement": normalize_string(current.get("statement")) or normalize_string((previous or {}).get("statement")),
        "status": "active",
        "decided_at": normalize_string((previous or {}).get("decided_at")) or normalize_string(current.get("decided_at")),
        "last_confirmed_at": normalize_string(current.get("last_confirmed_at")) or normalize_string((previous or {}).get("last_confirmed_at")),
        "superseded_by": None,
        "source_refs": merge_unique_strings(
            ensure_str_list((previous or {}).get("source_refs")),
            ensure_str_list(current.get("source_refs")),
        ),
        "source_spot_ids": merge_unique_strings(
            ensure_str_list((previous or {}).get("source_spot_ids")),
            ensure_str_list(current.get("source_spot_ids")),
        ),
        "confidence": normalize_string((previous or {}).get("confidence")) or normalize_string(current.get("confidence")) or "medium",
        "notes": normalize_string(current.get("notes")) or normalize_string((previous or {}).get("notes")) or None,
    }


def merge_knowledge(
    previous: Optional[Dict[str, Any]],
    current: Dict[str, Any],
) -> Dict[str, Any]:
    return {
        "knowledge_id": normalize_string((previous or {}).get("knowledge_id")) or normalize_string(current.get("knowledge_id")),
        "knowledge_key": normalize_string((previous or {}).get("knowledge_key")) or normalize_string(current.get("knowledge_key")),
        "title": normalize_string(current.get("title")) or normalize_string((previous or {}).get("title")),
        "summary": normalize_string(current.get("summary")) or normalize_string((previous or {}).get("summary")),
        "category": normalize_string(current.get("category")) or normalize_string((previous or {}).get("category")),
        "status": "current",
        "valid_from": normalize_string((previous or {}).get("valid_from")) or normalize_string(current.get("valid_from")),
        "valid_to": None,
        "last_confirmed_at": normalize_string(current.get("last_confirmed_at")) or normalize_string((previous or {}).get("last_confirmed_at")),
        "superseded_by": None,
        "source_refs": merge_unique_strings(
            ensure_str_list((previous or {}).get("source_refs")),
            ensure_str_list(current.get("source_refs")),
        ),
        "source_spot_ids": merge_unique_strings(
            ensure_str_list((previous or {}).get("source_spot_ids")),
            ensure_str_list(current.get("source_spot_ids")),
        ),
        "related_task_ids": merge_unique_strings(
            ensure_str_list((previous or {}).get("related_task_ids")),
            ensure_str_list(current.get("related_task_ids")),
        ),
        "canonical_ids": merge_unique_strings(
            ensure_str_list((previous or {}).get("canonical_ids")),
            ensure_str_list(current.get("canonical_ids")),
        ),
        "evidence_refs": merge_unique_strings(
            ensure_str_list((previous or {}).get("evidence_refs")),
            ensure_str_list(current.get("evidence_refs")),
        ),
        "notes": normalize_string(current.get("notes")) or normalize_string((previous or {}).get("notes")) or None,
    }


def build_current_task(row: Dict[str, Any], anchor_at: str, annotation_lookup: Dict[str, Dict[str, Any]]) -> Dict[str, Any]:
    title = normalize_string(row.get("title"))
    source_refs = ensure_str_list(row.get("source_refs"))
    return {
        "task_id": f"task-{stable_task_key(title).split(':', 1)[1]}",
        "task_key": stable_task_key(title),
        "title": title,
        "summary": normalize_string(row.get("summary")) or title,
        "status": "open",
        "priority": normalize_string(row.get("priority")).lower() or "medium",
        "task_kind": derive_task_kind(annotation_lookup, source_refs),
        "first_seen_at": anchor_at,
        "last_seen_at": anchor_at,
        "closed_at": None,
        "superseded_by": None,
        "source_refs": source_refs,
        "source_spot_ids": collect_source_spot_ids(source_refs),
        "evidence_refs": collect_annotation_values(annotation_lookup, source_refs, "evidence_utterance_ids"),
        "canonical_ids": collect_annotation_values(annotation_lookup, source_refs, "canonical_id"),
        "notes": None,
    }


def build_current_decision(row: Dict[str, Any], anchor_at: str) -> Dict[str, Any]:
    statement = normalize_string(row.get("summary"))
    source_refs = ensure_str_list(row.get("source_refs"))
    decision_key = stable_decision_key(statement)
    return {
        "decision_id": f"decision-{decision_key.split(':', 1)[1]}",
        "decision_key": decision_key,
        "statement": statement,
        "status": "active",
        "decided_at": anchor_at,
        "last_confirmed_at": anchor_at,
        "superseded_by": None,
        "source_refs": source_refs,
        "source_spot_ids": collect_source_spot_ids(source_refs),
        "confidence": "medium",
        "notes": None,
    }


def build_current_knowledge(
    row: Dict[str, Any],
    anchor_at: str,
    annotation_lookup: Dict[str, Dict[str, Any]],
) -> Dict[str, Any]:
    title = normalize_string(row.get("title"))
    category = normalize_string(row.get("category"))
    source_refs = ensure_str_list(row.get("source_refs"))
    knowledge_key = stable_knowledge_key(category, title)
    return {
        "knowledge_id": f"knowledge-{knowledge_key.split(':', 1)[1]}",
        "knowledge_key": knowledge_key,
        "title": title,
        "summary": normalize_string(row.get("summary")) or title,
        "category": category,
        "status": "current",
        "valid_from": anchor_at,
        "valid_to": None,
        "last_confirmed_at": anchor_at,
        "superseded_by": None,
        "source_refs": source_refs,
        "source_spot_ids": collect_source_spot_ids(source_refs),
        "related_task_ids": [],
        "canonical_ids": collect_annotation_values(annotation_lookup, source_refs, "canonical_id"),
        "evidence_refs": collect_annotation_values(annotation_lookup, source_refs, "evidence_utterance_ids"),
        "notes": None,
    }


def main() -> None:
    args = parse_args()
    artifacts_root = Path(args.artifacts_root).expanduser().resolve()
    output_dir = resolve_output_dir(artifacts_root, args.date)
    daily_rollup = load_daily_rollup(output_dir)
    spot_wrapups = load_spot_wrapups(artifacts_root, daily_rollup)
    annotation_lookup = build_annotation_lookup(spot_wrapups)
    prior_date, prior_snapshot = load_prior_snapshot(artifacts_root, args.date)
    anchor_at = build_day_anchor(daily_rollup)

    active_tasks = {
        normalize_string(task.get("task_key")): dict(task)
        for task in ensure_list((prior_snapshot or {}).get("active_tasks"))
        if isinstance(task, dict) and normalize_string(task.get("task_key"))
    }
    for row in ensure_list(daily_rollup.get("open_tasks")):
        if not isinstance(row, dict):
            continue
        current = build_current_task(row, anchor_at, annotation_lookup)
        key = normalize_string(current.get("task_key"))
        active_tasks[key] = merge_task(active_tasks.get(key), current)

    decision_log = {
        normalize_string(decision.get("decision_key")): dict(decision)
        for decision in ensure_list((prior_snapshot or {}).get("decision_log"))
        if isinstance(decision, dict) and normalize_string(decision.get("decision_key"))
    }
    for row in ensure_list(daily_rollup.get("decisions")):
        if not isinstance(row, dict):
            continue
        current = build_current_decision(row, anchor_at)
        key = normalize_string(current.get("decision_key"))
        decision_log[key] = merge_decision(decision_log.get(key), current)

    durable_knowledge = {
        normalize_string(knowledge.get("knowledge_key")): dict(knowledge)
        for knowledge in ensure_list((prior_snapshot or {}).get("durable_knowledge"))
        if isinstance(knowledge, dict) and normalize_string(knowledge.get("knowledge_key"))
    }
    for row in ensure_list(daily_rollup.get("knowledge_refs")):
        if not isinstance(row, dict):
            continue
        current = build_current_knowledge(row, anchor_at, annotation_lookup)
        key = normalize_string(current.get("knowledge_key"))
        durable_knowledge[key] = merge_knowledge(durable_knowledge.get(key), current)

    snapshot = {
        "date": args.date,
        "generated_at": datetime.now().astimezone().isoformat(),
        "source_daily_rollup_path": str((output_dir / "08_daily_rollup.json").resolve()),
        "prior_snapshot_date": prior_date,
        "state_version": 1,
        "active_tasks": sorted(
            active_tasks.values(),
            key=lambda row: (
                {"high": 0, "medium": 1, "low": 2}.get(normalize_string(row.get("priority")).lower(), 1),
                normalize_string(row.get("title")),
            ),
        ),
        "decision_log": sorted(
            decision_log.values(),
            key=lambda row: normalize_string(row.get("statement")),
        ),
        "durable_knowledge": sorted(
            durable_knowledge.values(),
            key=lambda row: (normalize_string(row.get("category")), normalize_string(row.get("title"))),
        ),
    }
    snapshot["context_bundle"] = build_context_bundle(snapshot, recent_rollup_context(artifacts_root, args.date))

    write_json(output_dir / "12_active_state_snapshot.json", snapshot)
    print(
        json.dumps(
            {
                "date": args.date,
                "prior_snapshot_date": prior_date,
                "active_task_count": len(snapshot["active_tasks"]),
                "decision_count": len(snapshot["decision_log"]),
                "durable_knowledge_count": len(snapshot["durable_knowledge"]),
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
