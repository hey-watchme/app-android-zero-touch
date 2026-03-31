#!/usr/bin/env python3
"""Build stateful daily artifacts from current daily rollup plus prior state."""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Set

from generate_spot_wrapup import extract_json, load_env_file  # type: ignore
from stateful_daily_utils import (
    build_annotation_lookup,
    build_state_delta,
    build_stateful_daily_preview,
    build_stateful_daily_rollup,
    collect_source_spot_ids,
    ensure_list,
    ensure_str_list,
    load_daily_rollup,
    load_json,
    load_prior_context_bundle,
    load_prior_snapshot,
    load_spot_wrapups,
    normalize_string,
    resolve_output_dir,
    stable_decision_key,
    stable_knowledge_key,
    stable_task_key,
    write_json,
    write_text,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--date", required=True, help="Target date in YYYY-MM-DD")
    parser.add_argument(
        "--artifacts-root",
        default="experiments/amical/artifacts",
        help="Artifacts root containing daily-rollups",
    )
    parser.add_argument("--provider", default=None, help="Override LLM provider")
    parser.add_argument("--model", default=None, help="Override LLM model")
    parser.add_argument(
        "--disable-llm",
        action="store_true",
        help="Skip LLM planning and use deterministic stateful rollup/delta only",
    )
    return parser.parse_args()


def build_current_task_lookup(current_daily: Dict[str, Any], current_snapshot: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    snapshot_tasks = {
        normalize_string(task.get("task_key")): task
        for task in ensure_list(current_snapshot.get("active_tasks"))
        if isinstance(task, dict)
    }
    result: Dict[str, Dict[str, Any]] = {}
    for task in ensure_list(current_daily.get("open_tasks")):
        if not isinstance(task, dict):
            continue
        title = normalize_string(task.get("title"))
        key = stable_task_key(title)
        row = dict(task)
        row["task_key"] = key
        if key in snapshot_tasks:
            row["task_id"] = normalize_string(snapshot_tasks[key].get("task_id"))
        result[key] = row
    return result


def build_current_knowledge_lookup(current_daily: Dict[str, Any], current_snapshot: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    snapshot_knowledge = {
        normalize_string(knowledge.get("knowledge_key")): knowledge
        for knowledge in ensure_list(current_snapshot.get("durable_knowledge"))
        if isinstance(knowledge, dict)
    }
    result: Dict[str, Dict[str, Any]] = {}
    for knowledge in ensure_list(current_daily.get("knowledge_refs")):
        if not isinstance(knowledge, dict):
            continue
        title = normalize_string(knowledge.get("title"))
        category = normalize_string(knowledge.get("category"))
        key = stable_knowledge_key(category, title)
        row = dict(knowledge)
        row["knowledge_key"] = key
        if key in snapshot_knowledge:
            row["knowledge_id"] = normalize_string(snapshot_knowledge[key].get("knowledge_id"))
        result[key] = row
    return result


def build_valid_refs(current_daily: Dict[str, Any], annotation_lookup: Dict[str, Dict[str, Any]]) -> Set[str]:
    refs: Set[str] = set(annotation_lookup.keys())
    for collection_name in ("decisions", "open_tasks", "knowledge_refs"):
        for row in ensure_list(current_daily.get(collection_name)):
            if not isinstance(row, dict):
                continue
            for ref in ensure_str_list(row.get("source_refs")):
                refs.add(ref)
    return refs


def build_prompt(
    current_daily: Dict[str, Any],
    current_spots: Dict[str, Dict[str, Any]],
    prior_snapshot: Optional[Dict[str, Any]],
    prior_bundle: Optional[Dict[str, Any]],
) -> str:
    spot_lines: List[str] = []
    for spot_id, spot in sorted(current_spots.items()):
        spot_lines.append(
            f"- {spot_id} dataset={normalize_string(spot.get('dataset_id'))} "
            f"window={normalize_string(spot.get('start_at'))} - {normalize_string(spot.get('end_at'))} "
            f"headline={normalize_string(spot.get('headline'))} abstract={normalize_string(spot.get('abstract'))}"
        )
        for decision in ensure_list(spot.get("decisions")):
            if isinstance(decision, dict):
                refs = [f"{spot_id}:{ref}" for ref in ensure_str_list(decision.get("annotation_ids"))]
                spot_lines.append(
                    f"  decision summary={normalize_string(decision.get('summary'))} refs={','.join(refs) or '(none)'}"
                )
        for task in ensure_list(spot.get("open_tasks")):
            if isinstance(task, dict):
                ref = f"{spot_id}:{normalize_string(task.get('annotation_id'))}"
                spot_lines.append(
                    f"  task ref={ref} priority={normalize_string(task.get('priority')) or 'medium'} "
                    f"title={normalize_string(task.get('title'))} summary={normalize_string(task.get('summary'))}"
                )
        for knowledge in ensure_list(spot.get("knowledge_refs")):
            if isinstance(knowledge, dict):
                ref = f"{spot_id}:{normalize_string(knowledge.get('annotation_id'))}"
                spot_lines.append(
                    f"  knowledge ref={ref} category={normalize_string(knowledge.get('category'))} "
                    f"title={normalize_string(knowledge.get('title'))} summary={normalize_string(knowledge.get('summary'))}"
                )

    current_daily_lines: List[str] = [
        f"date={normalize_string(current_daily.get('date'))}",
        f"headline={normalize_string(current_daily.get('headline'))}",
        f"abstract={normalize_string(current_daily.get('abstract'))}",
        f"status_summary={normalize_string(current_daily.get('status_summary'))}",
    ]
    for task in ensure_list(current_daily.get("open_tasks")):
        if isinstance(task, dict):
            current_daily_lines.append(
                f"daily_task refs={','.join(ensure_str_list(task.get('source_refs'))) or '(none)'} "
                f"title={normalize_string(task.get('title'))} priority={normalize_string(task.get('priority')) or 'medium'} "
                f"summary={normalize_string(task.get('summary'))}"
            )
    for decision in ensure_list(current_daily.get("decisions")):
        if isinstance(decision, dict):
            current_daily_lines.append(
                f"daily_decision refs={','.join(ensure_str_list(decision.get('source_refs'))) or '(none)'} "
                f"summary={normalize_string(decision.get('summary'))}"
            )
    for knowledge in ensure_list(current_daily.get("knowledge_refs")):
        if isinstance(knowledge, dict):
            current_daily_lines.append(
                f"daily_knowledge refs={','.join(ensure_str_list(knowledge.get('source_refs'))) or '(none)'} "
                f"category={normalize_string(knowledge.get('category'))} "
                f"title={normalize_string(knowledge.get('title'))} summary={normalize_string(knowledge.get('summary'))}"
            )

    prior_lines: List[str] = []
    for task in ensure_list((prior_snapshot or {}).get("active_tasks"))[:20]:
        if isinstance(task, dict):
            prior_lines.append(
                f"prior_task id={normalize_string(task.get('task_id'))} key={normalize_string(task.get('task_key'))} "
                f"status={normalize_string(task.get('status'))} priority={normalize_string(task.get('priority'))} "
                f"title={normalize_string(task.get('title'))} summary={normalize_string(task.get('summary'))}"
            )
    for decision in ensure_list((prior_snapshot or {}).get("decision_log"))[:15]:
        if isinstance(decision, dict):
            prior_lines.append(
                f"prior_decision id={normalize_string(decision.get('decision_id'))} key={normalize_string(decision.get('decision_key'))} "
                f"status={normalize_string(decision.get('status'))} statement={normalize_string(decision.get('statement'))}"
            )
    for knowledge in ensure_list((prior_snapshot or {}).get("durable_knowledge"))[:20]:
        if isinstance(knowledge, dict):
            prior_lines.append(
                f"prior_knowledge id={normalize_string(knowledge.get('knowledge_id'))} key={normalize_string(knowledge.get('knowledge_key'))} "
                f"status={normalize_string(knowledge.get('status'))} category={normalize_string(knowledge.get('category'))} "
                f"title={normalize_string(knowledge.get('title'))} summary={normalize_string(knowledge.get('summary'))}"
            )

    prior_bundle_lines: List[str] = []
    if prior_bundle:
        prior_bundle_lines.append(f"bundle_date={normalize_string(prior_bundle.get('bundle_date'))}")
        prior_bundle_lines.append(f"recent_chronology_summary={normalize_string(prior_bundle.get('recent_chronology_summary'))}")
        for item in ensure_str_list(prior_bundle.get("priority_items")):
            prior_bundle_lines.append(f"priority_item={item}")

    return f"""You are producing a stateful daily reducer output for Amical conversation analysis.

Goal:
- Read the current day's spot/daily artifacts together with prior active state and prior context bundle
- Produce one readable `10_stateful_daily_rollup` payload
- Produce one actionable `11_state_delta` payload
- Keep the readable rollup and the state delta separate but consistent

Rules:
- Use ONLY the provided current-day evidence and prior state/context
- Do not invent source refs, ids, tasks, decisions, or knowledge
- Every item in `decisions_today`, `updated_knowledge`, and every mutation MUST cite current-day `source_refs`
- Prefer conservative mutations: `create`, `touch`, `update`, `close`, `confirm`, `replace`, `supersede`
- Only emit `close` / `replace` / `supersede` if the current-day evidence clearly supports it
- `continuing_priorities` should summarize carry-over pressure from prior state/context, not dump the full history
- Output Japanese prose

Output ONLY JSON:
{{
  "stateful_daily_rollup": {{
    "headline": "string",
    "abstract": "2-4 sentence Japanese summary that includes today's changes plus continuing context",
    "status_summary": "1-3 sentence current-state summary",
    "main_threads": [
      {{
        "title": "string",
        "summary": "short paragraph",
        "source_spot_ids": ["SPOT-001"]
      }}
    ],
    "decisions_today": [
      {{
        "summary": "string",
        "source_refs": ["SPOT-001:K001"]
      }}
    ],
    "continuing_priorities": ["string"],
    "updated_knowledge": [
      {{
        "title": "string",
        "summary": "string",
        "category": "string",
        "status": "new|confirmed|updated|superseded",
        "source_refs": ["SPOT-001:K001"]
      }}
    ]
  }},
  "state_delta": {{
    "task_mutations": [
      {{
        "mutation": "create|touch|update|close",
        "task_id": "optional existing task id",
        "task_key": "optional semantic key",
        "title": "string",
        "summary": "string",
        "priority": "high|medium|low",
        "task_kind": "implementation|documentation|research|decision|other",
        "source_refs": ["SPOT-001:T001"],
        "reason": "short reason"
      }}
    ],
    "decision_mutations": [
      {{
        "mutation": "create|confirm|replace|revoke",
        "decision_id": "optional existing decision id",
        "decision_key": "optional semantic key",
        "statement": "string",
        "source_refs": ["SPOT-001:K001"],
        "reason": "short reason"
      }}
    ],
    "knowledge_mutations": [
      {{
        "mutation": "create|confirm|update|supersede|outdate",
        "knowledge_id": "optional existing knowledge id",
        "knowledge_key": "optional semantic key",
        "title": "string",
        "summary": "string",
        "category": "string",
        "source_refs": ["SPOT-001:K001"],
        "reason": "short reason"
      }}
    ]
  }}
}}

# Current Day Spot Digest
{chr(10).join(spot_lines) or "- (none)"}

# Current Day Daily Rollup Digest
{chr(10).join(current_daily_lines) or "- (none)"}

# Prior Active State Snapshot
{chr(10).join(prior_lines) or "- (none)"}

# Prior Context Bundle
{chr(10).join(prior_bundle_lines) or "- (none)"}
"""


def normalize_source_refs(values: Any, valid_refs: Set[str]) -> List[str]:
    refs: List[str] = []
    for value in ensure_list(values):
        normalized = normalize_string(value)
        if normalized and normalized in valid_refs and normalized not in refs:
            refs.append(normalized)
    return refs


def normalize_main_threads(raw_rows: Any, valid_spot_ids: Set[str]) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    for index, raw in enumerate(ensure_list(raw_rows), start=1):
        if not isinstance(raw, dict):
            continue
        title = normalize_string(raw.get("title"))
        summary = normalize_string(raw.get("summary"))
        spot_ids = [spot_id for spot_id in ensure_str_list(raw.get("source_spot_ids")) if spot_id in valid_spot_ids]
        if not title or not summary or not spot_ids:
            continue
        rows.append(
            {
                "thread_id": f"thread-{index:02d}",
                "title": title,
                "summary": summary,
                "source_spot_ids": spot_ids,
            }
        )
    return rows[:6]


def normalize_decisions_today(raw_rows: Any, valid_refs: Set[str]) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    for raw in ensure_list(raw_rows):
        if not isinstance(raw, dict):
            continue
        summary = normalize_string(raw.get("summary"))
        refs = normalize_source_refs(raw.get("source_refs"), valid_refs)
        if not summary or not refs:
            continue
        rows.append(
            {
                "summary": summary,
                "source_refs": refs,
                "source_spot_ids": collect_source_spot_ids(refs),
            }
        )
    return rows[:6]


def normalize_updated_knowledge(
    raw_rows: Any,
    valid_refs: Set[str],
    current_knowledge_lookup: Dict[str, Dict[str, Any]],
) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    for raw in ensure_list(raw_rows):
        if not isinstance(raw, dict):
            continue
        title = normalize_string(raw.get("title"))
        summary = normalize_string(raw.get("summary"))
        category = normalize_string(raw.get("category"))
        refs = normalize_source_refs(raw.get("source_refs"), valid_refs)
        status = normalize_string(raw.get("status")).lower() or "confirmed"
        if status not in {"new", "confirmed", "updated", "superseded"}:
            status = "confirmed"
        if not title or not category or not refs:
            continue
        key = stable_knowledge_key(category, title)
        current = current_knowledge_lookup.get(key, {})
        rows.append(
            {
                "knowledge_id": normalize_string(current.get("knowledge_id")),
                "knowledge_key": key,
                "title": title,
                "summary": summary or normalize_string(current.get("summary")) or title,
                "category": category,
                "status": status,
                "source_refs": refs,
            }
        )
    return rows[:10]


def normalize_task_mutations(
    raw_rows: Any,
    valid_refs: Set[str],
    current_task_lookup: Dict[str, Dict[str, Any]],
    prior_task_keys: Set[str],
) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    for raw in ensure_list(raw_rows):
        if not isinstance(raw, dict):
            continue
        mutation = normalize_string(raw.get("mutation")).lower()
        if mutation not in {"create", "touch", "update", "close"}:
            continue
        title = normalize_string(raw.get("title"))
        summary = normalize_string(raw.get("summary"))
        refs = normalize_source_refs(raw.get("source_refs"), valid_refs)
        key = normalize_string(raw.get("task_key")) or stable_task_key(title)
        current = current_task_lookup.get(key, {})
        task_id = normalize_string(raw.get("task_id")) or normalize_string(current.get("task_id"))
        priority = normalize_string(raw.get("priority")).lower() or normalize_string(current.get("priority")).lower() or "medium"
        if priority not in {"high", "medium", "low"}:
            priority = "medium"
        task_kind = normalize_string(raw.get("task_kind")).lower() or normalize_string(current.get("task_kind")).lower() or "other"
        if task_kind not in {"implementation", "documentation", "research", "decision", "other"}:
            task_kind = "other"
        if mutation == "create" and key in prior_task_keys:
            mutation = "touch"
        if not refs or (not title and not task_id):
            continue
        rows.append(
            {
                "mutation": mutation,
                "task_id": task_id,
                "task_key": key,
                "title": title or normalize_string(current.get("title")),
                "summary": summary or normalize_string(current.get("summary")) or title,
                "priority": priority,
                "task_kind": task_kind,
                "source_refs": refs,
                "reason": normalize_string(raw.get("reason")) or "stateful planner judgment",
            }
        )
    return rows


def normalize_decision_mutations(raw_rows: Any, valid_refs: Set[str], prior_keys: Set[str]) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    for raw in ensure_list(raw_rows):
        if not isinstance(raw, dict):
            continue
        mutation = normalize_string(raw.get("mutation")).lower()
        if mutation not in {"create", "confirm", "replace", "revoke"}:
            continue
        statement = normalize_string(raw.get("statement"))
        refs = normalize_source_refs(raw.get("source_refs"), valid_refs)
        key = normalize_string(raw.get("decision_key")) or stable_decision_key(statement)
        if mutation == "create" and key in prior_keys:
            mutation = "confirm"
        if not statement or not refs:
            continue
        rows.append(
            {
                "mutation": mutation,
                "decision_id": normalize_string(raw.get("decision_id")),
                "decision_key": key,
                "statement": statement,
                "source_refs": refs,
                "reason": normalize_string(raw.get("reason")) or "stateful planner judgment",
            }
        )
    return rows


def normalize_knowledge_mutations(
    raw_rows: Any,
    valid_refs: Set[str],
    prior_keys: Set[str],
    current_knowledge_lookup: Dict[str, Dict[str, Any]],
) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    for raw in ensure_list(raw_rows):
        if not isinstance(raw, dict):
            continue
        mutation = normalize_string(raw.get("mutation")).lower()
        if mutation not in {"create", "confirm", "update", "supersede", "outdate"}:
            continue
        title = normalize_string(raw.get("title"))
        category = normalize_string(raw.get("category"))
        summary = normalize_string(raw.get("summary"))
        refs = normalize_source_refs(raw.get("source_refs"), valid_refs)
        key = normalize_string(raw.get("knowledge_key")) or stable_knowledge_key(category, title)
        current = current_knowledge_lookup.get(key, {})
        if mutation == "create" and key in prior_keys:
            mutation = "confirm"
        if not title or not category or not refs:
            continue
        rows.append(
            {
                "mutation": mutation,
                "knowledge_id": normalize_string(raw.get("knowledge_id")) or normalize_string(current.get("knowledge_id")),
                "knowledge_key": key,
                "title": title,
                "summary": summary or normalize_string(current.get("summary")) or title,
                "category": category,
                "source_refs": refs,
                "reason": normalize_string(raw.get("reason")) or "stateful planner judgment",
            }
        )
    return rows


def derive_task_sections(
    current_daily: Dict[str, Any],
    normalized_task_mutations: List[Dict[str, Any]],
    prior_snapshot: Optional[Dict[str, Any]],
    current_snapshot: Dict[str, Any],
) -> Dict[str, List[Dict[str, Any]]]:
    prior_keys = {
        normalize_string(task.get("task_key"))
        for task in ensure_list((prior_snapshot or {}).get("active_tasks"))
        if isinstance(task, dict)
    }
    current_snapshot_tasks = {
        normalize_string(task.get("task_key")): task
        for task in ensure_list(current_snapshot.get("active_tasks"))
        if isinstance(task, dict)
    }
    mutation_by_key = {normalize_string(row.get("task_key")): row for row in normalized_task_mutations}

    carried: List[Dict[str, Any]] = []
    created: List[Dict[str, Any]] = []
    for task in ensure_list(current_daily.get("open_tasks")):
        if not isinstance(task, dict):
            continue
        title = normalize_string(task.get("title"))
        key = stable_task_key(title)
        mutation = mutation_by_key.get(key)
        snapshot_task = current_snapshot_tasks.get(key, {})
        row = {
            "task_id": normalize_string(snapshot_task.get("task_id")),
            "task_key": key,
            "title": title,
            "summary": normalize_string(task.get("summary")),
            "priority": normalize_string(task.get("priority")).lower() or "medium",
            "source_refs": ensure_str_list(task.get("source_refs")),
        }
        if mutation and normalize_string(mutation.get("mutation")) in {"touch", "update"}:
            carried.append(row)
        elif mutation and normalize_string(mutation.get("mutation")) == "create":
            created.append(row)
        elif key in prior_keys:
            carried.append(row)
        else:
            created.append(row)
    return {"carried_over_tasks": carried, "newly_opened_tasks": created}


def build_stateful_with_llm(
    llm: Any,
    current_daily: Dict[str, Any],
    current_spots: Dict[str, Dict[str, Any]],
    current_snapshot: Dict[str, Any],
    prior_snapshot: Optional[Dict[str, Any]],
    prior_bundle: Optional[Dict[str, Any]],
) -> Optional[Dict[str, Any]]:
    prompt = build_prompt(current_daily, current_spots, prior_snapshot, prior_bundle)
    raw_output = llm.generate(prompt)
    payload = extract_json(raw_output) or {}
    daily_raw = payload.get("stateful_daily_rollup") if isinstance(payload, dict) else {}
    delta_raw = payload.get("state_delta") if isinstance(payload, dict) else {}
    if not isinstance(daily_raw, dict) or not isinstance(delta_raw, dict):
        return None

    annotation_lookup = build_annotation_lookup(current_spots)
    valid_refs = build_valid_refs(current_daily, annotation_lookup)
    valid_spot_ids = {
        normalize_string(spot.get("spot_id"))
        for spot in ensure_list(current_daily.get("source_spots"))
        if isinstance(spot, dict)
    }
    current_task_lookup = build_current_task_lookup(current_daily, current_snapshot)
    current_knowledge_lookup = build_current_knowledge_lookup(current_daily, current_snapshot)
    prior_task_keys = {
        normalize_string(task.get("task_key"))
        for task in ensure_list((prior_snapshot or {}).get("active_tasks"))
        if isinstance(task, dict)
    }
    prior_decision_keys = {
        normalize_string(decision.get("decision_key"))
        for decision in ensure_list((prior_snapshot or {}).get("decision_log"))
        if isinstance(decision, dict)
    }
    prior_knowledge_keys = {
        normalize_string(knowledge.get("knowledge_key"))
        for knowledge in ensure_list((prior_snapshot or {}).get("durable_knowledge"))
        if isinstance(knowledge, dict)
    }

    normalized_task_mutations = normalize_task_mutations(
        delta_raw.get("task_mutations"),
        valid_refs,
        current_task_lookup,
        prior_task_keys,
    )
    normalized_decision_mutations = normalize_decision_mutations(
        delta_raw.get("decision_mutations"),
        valid_refs,
        prior_decision_keys,
    )
    normalized_knowledge_mutations = normalize_knowledge_mutations(
        delta_raw.get("knowledge_mutations"),
        valid_refs,
        prior_knowledge_keys,
        current_knowledge_lookup,
    )

    task_sections = derive_task_sections(
        current_daily,
        normalized_task_mutations,
        prior_snapshot,
        current_snapshot,
    )
    updated_knowledge = normalize_updated_knowledge(
        daily_raw.get("updated_knowledge"),
        valid_refs,
        current_knowledge_lookup,
    )
    if not updated_knowledge:
        for mutation in normalized_knowledge_mutations:
            updated_knowledge.append(
                {
                    "knowledge_id": normalize_string(mutation.get("knowledge_id")),
                    "knowledge_key": normalize_string(mutation.get("knowledge_key")),
                    "title": normalize_string(mutation.get("title")),
                    "summary": normalize_string(mutation.get("summary")),
                    "category": normalize_string(mutation.get("category")),
                    "status": {
                        "create": "new",
                        "confirm": "confirmed",
                        "update": "updated",
                        "supersede": "superseded",
                        "outdate": "superseded",
                    }.get(normalize_string(mutation.get("mutation")), "confirmed"),
                    "source_refs": ensure_str_list(mutation.get("source_refs")),
                }
            )

    stateful_daily = {
        "date": normalize_string(current_daily.get("date")),
        "headline": normalize_string(daily_raw.get("headline")) or normalize_string(current_daily.get("headline")),
        "abstract": normalize_string(daily_raw.get("abstract")) or normalize_string(current_daily.get("abstract")),
        "status_summary": normalize_string(daily_raw.get("status_summary")) or normalize_string(current_daily.get("status_summary")),
        "prior_snapshot_date": normalize_string((prior_snapshot or {}).get("date")) or None,
        "prior_context_bundle_date": normalize_string((prior_bundle or {}).get("bundle_date")) or None,
        "source_dataset_ids": ensure_str_list(current_daily.get("source_dataset_ids")),
        "source_spots": ensure_list(current_daily.get("source_spots")),
        "main_threads": normalize_main_threads(daily_raw.get("main_threads"), valid_spot_ids) or ensure_list(current_daily.get("main_threads")),
        "decisions_today": normalize_decisions_today(daily_raw.get("decisions_today"), valid_refs) or ensure_list(current_daily.get("decisions")),
        "carried_over_tasks": task_sections["carried_over_tasks"],
        "newly_opened_tasks": task_sections["newly_opened_tasks"],
        "updated_knowledge": updated_knowledge[:10],
        "continuing_priorities": ensure_str_list(daily_raw.get("continuing_priorities"))[:10]
        or ensure_str_list((prior_bundle or {}).get("priority_items"))[:10],
    }

    state_delta = {
        "date": normalize_string(current_daily.get("date")),
        "generated_at": datetime.now().astimezone().isoformat(),
        "prior_snapshot_date": normalize_string((prior_snapshot or {}).get("date")) or None,
        "current_snapshot_date": normalize_string(current_snapshot.get("date")),
        "task_mutations": normalized_task_mutations,
        "decision_mutations": normalized_decision_mutations,
        "knowledge_mutations": normalized_knowledge_mutations,
    }
    return {"stateful_daily": stateful_daily, "state_delta": state_delta}


def main() -> None:
    args = parse_args()
    artifacts_root = Path(args.artifacts_root).expanduser().resolve()
    output_dir = resolve_output_dir(artifacts_root, args.date)
    current_snapshot_path = output_dir / "12_active_state_snapshot.json"
    if not current_snapshot_path.exists():
        raise SystemExit(f"Missing current snapshot: {current_snapshot_path}")

    current_daily = load_daily_rollup(output_dir)
    current_snapshot = load_json(current_snapshot_path)
    current_spots = load_spot_wrapups(artifacts_root, current_daily)
    _, prior_snapshot = load_prior_snapshot(artifacts_root, args.date)
    _, prior_bundle = load_prior_context_bundle(artifacts_root, args.date)

    llm = None
    llm_result = None
    if not args.disable_llm:
        repo_root = artifacts_root.parents[2]
        backend_dir = repo_root / "backend"
        load_env_file(backend_dir / ".env")
        sys.path.insert(0, str(backend_dir))
        from services.llm_providers import LLMFactory, get_current_llm  # type: ignore

        if args.provider or args.model:
            provider = args.provider or os.getenv("LLM_DEFAULT_PROVIDER", "openai")
            llm = LLMFactory.create(provider, args.model)
        else:
            llm = get_current_llm()

        llm_result = build_stateful_with_llm(
            llm,
            current_daily,
            current_spots,
            current_snapshot,
            prior_snapshot,
            prior_bundle,
        )
    if llm_result:
        stateful_daily = llm_result["stateful_daily"]
        state_delta = llm_result["state_delta"]
    else:
        stateful_daily = build_stateful_daily_rollup(current_daily, current_snapshot, prior_snapshot, prior_bundle)
        state_delta = build_state_delta(prior_snapshot, current_snapshot, current_daily)

    stateful_daily["generated_at"] = datetime.now().astimezone().isoformat()
    stateful_daily["input_current_snapshot_path"] = str(current_snapshot_path.resolve())
    stateful_daily["planner_model"] = normalize_string(getattr(llm, "model_name", "")) or None

    write_json(output_dir / "10_stateful_daily_rollup.json", stateful_daily)
    write_text(output_dir / "10_stateful_daily_rollup_preview.md", build_stateful_daily_preview(stateful_daily))
    write_json(output_dir / "11_state_delta.json", state_delta)

    print(
        json.dumps(
            {
                "date": args.date,
                "carried_over_task_count": len(stateful_daily.get("carried_over_tasks") or []),
                "new_task_count": len(stateful_daily.get("newly_opened_tasks") or []),
                "decision_mutation_count": len(state_delta.get("decision_mutations") or []),
                "knowledge_mutation_count": len(state_delta.get("knowledge_mutations") or []),
                "planner_model": stateful_daily.get("planner_model"),
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
