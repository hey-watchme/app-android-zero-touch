#!/usr/bin/env python3
"""Utilities for file-based Amical stateful daily artifacts."""

from __future__ import annotations

import hashlib
import json
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple


def normalize_string(value: Any) -> str:
    return " ".join(str(value or "").split()).strip()


def ensure_list(values: Any) -> List[Any]:
    return values if isinstance(values, list) else []


def ensure_str_list(values: Any) -> List[str]:
    result: List[str] = []
    for value in ensure_list(values):
        normalized = normalize_string(value)
        if normalized and normalized not in result:
            result.append(normalized)
    return result


def merge_unique_strings(*collections: Iterable[str]) -> List[str]:
    merged: List[str] = []
    for collection in collections:
        for value in collection:
            normalized = normalize_string(value)
            if normalized and normalized not in merged:
                merged.append(normalized)
    return merged


def load_json(path: Path) -> Dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: Any) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_text(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def parse_iso_datetime(value: str) -> Optional[datetime]:
    text = normalize_string(value)
    if not text:
        return None
    try:
        return datetime.fromisoformat(text.replace("Z", "+00:00"))
    except Exception:
        return None


def parse_iso_date(value: str) -> Optional[datetime]:
    text = normalize_string(value)
    if not text:
        return None
    try:
        return datetime.fromisoformat(f"{text}T00:00:00+00:00")
    except Exception:
        return None


def hash_key(*parts: str, length: int = 12) -> str:
    digest = hashlib.sha1("|".join(normalize_string(part).lower() for part in parts).encode("utf-8")).hexdigest()
    return digest[:length]


def stable_task_key(title: str) -> str:
    return f"task:{hash_key(title)}"


def stable_decision_key(statement: str) -> str:
    return f"decision:{hash_key(statement)}"


def stable_knowledge_key(category: str, title: str) -> str:
    return f"knowledge:{hash_key(category, title)}"


def resolve_output_dir(artifacts_root: Path, date_value: str) -> Path:
    return artifacts_root / "daily-rollups" / date_value


def load_daily_rollup(output_dir: Path) -> Dict[str, Any]:
    return load_json(output_dir / "08_daily_rollup.json")


def list_daily_dates(artifacts_root: Path) -> List[str]:
    daily_root = artifacts_root / "daily-rollups"
    if not daily_root.exists():
        return []
    dates: List[str] = []
    for child in sorted(daily_root.iterdir()):
        if child.is_dir() and (child / "08_daily_rollup.json").exists():
            dates.append(child.name)
    return dates


def find_previous_date(artifacts_root: Path, date_value: str) -> Optional[str]:
    previous: Optional[str] = None
    for candidate in list_daily_dates(artifacts_root):
        if candidate < date_value:
            previous = candidate
        if candidate >= date_value:
            break
    return previous


def load_prior_snapshot(artifacts_root: Path, date_value: str) -> Tuple[Optional[str], Optional[Dict[str, Any]]]:
    previous_date = find_previous_date(artifacts_root, date_value)
    if not previous_date:
        return None, None
    snapshot_path = resolve_output_dir(artifacts_root, previous_date) / "12_active_state_snapshot.json"
    if not snapshot_path.exists():
        return previous_date, None
    return previous_date, load_json(snapshot_path)


def load_prior_context_bundle(artifacts_root: Path, date_value: str) -> Tuple[Optional[str], Optional[Dict[str, Any]]]:
    previous_date = find_previous_date(artifacts_root, date_value)
    if not previous_date:
        return None, None
    bundle_path = resolve_output_dir(artifacts_root, previous_date) / "09_context_bundle.json"
    if not bundle_path.exists():
        return previous_date, None
    return previous_date, load_json(bundle_path)


def build_day_anchor(rollup: Dict[str, Any]) -> str:
    timestamps: List[datetime] = []
    for spot in ensure_list(rollup.get("source_spots")):
        if not isinstance(spot, dict):
            continue
        parsed = parse_iso_datetime(normalize_string(spot.get("end_at")))
        if parsed:
            timestamps.append(parsed)
    if timestamps:
        return max(timestamps).astimezone().isoformat()

    parsed_date = parse_iso_date(normalize_string(rollup.get("date")))
    if parsed_date:
        return parsed_date.isoformat()
    return datetime.now().astimezone().isoformat()


def recent_rollup_context(artifacts_root: Path, date_value: str, limit: int = 7) -> List[Dict[str, str]]:
    entries: List[Dict[str, str]] = []
    for candidate in list_daily_dates(artifacts_root):
        if candidate > date_value:
            break
        rollup = load_daily_rollup(resolve_output_dir(artifacts_root, candidate))
        entries.append(
            {
                "date": candidate,
                "headline": normalize_string(rollup.get("headline")),
                "abstract": normalize_string(rollup.get("abstract")),
                "status_summary": normalize_string(rollup.get("status_summary")),
            }
        )
    return entries[-limit:]


def load_spot_wrapups(artifacts_root: Path, rollup: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    result: Dict[str, Dict[str, Any]] = {}
    for spot in ensure_list(rollup.get("source_spots")):
        if not isinstance(spot, dict):
            continue
        spot_id = normalize_string(spot.get("spot_id"))
        dataset_id = normalize_string(spot.get("dataset_id"))
        if not spot_id or not dataset_id:
            continue
        wrapup_path = artifacts_root / dataset_id / "07_spot_wrapup.json"
        if wrapup_path.exists():
            result[spot_id] = load_json(wrapup_path)
    return result


def build_annotation_lookup(spot_wrapups: Dict[str, Dict[str, Any]]) -> Dict[str, Dict[str, Any]]:
    lookup: Dict[str, Dict[str, Any]] = {}
    for spot_id, wrapup in spot_wrapups.items():
        for annotation in ensure_list(wrapup.get("annotations")):
            if not isinstance(annotation, dict):
                continue
            annotation_id = normalize_string(annotation.get("annotation_id"))
            if not annotation_id:
                continue
            key = f"{spot_id}:{annotation_id}"
            row = dict(annotation)
            row["spot_id"] = spot_id
            lookup[key] = row

        for task in ensure_list(wrapup.get("open_tasks")):
            if not isinstance(task, dict):
                continue
            annotation_id = normalize_string(task.get("annotation_id"))
            if not annotation_id:
                continue
            key = f"{spot_id}:{annotation_id}"
            base = dict(lookup.get(key, {}))
            base.update(task)
            base["item_type"] = "task"
            base["spot_id"] = spot_id
            lookup[key] = base

        for knowledge in ensure_list(wrapup.get("knowledge_refs")):
            if not isinstance(knowledge, dict):
                continue
            annotation_id = normalize_string(knowledge.get("annotation_id"))
            if not annotation_id:
                continue
            key = f"{spot_id}:{annotation_id}"
            base = dict(lookup.get(key, {}))
            base.update(knowledge)
            base["item_type"] = "knowledge"
            base["spot_id"] = spot_id
            lookup[key] = base
    return lookup


def derive_task_kind(annotation_lookup: Dict[str, Dict[str, Any]], source_refs: List[str]) -> str:
    for ref in source_refs:
        annotation = annotation_lookup.get(ref) or {}
        task_kind = normalize_string(annotation.get("task_kind")).lower()
        if task_kind in {"implementation", "documentation", "research", "decision", "other"}:
            return task_kind
    return "other"


def collect_annotation_values(annotation_lookup: Dict[str, Dict[str, Any]], source_refs: List[str], field: str) -> List[str]:
    values: List[str] = []
    for ref in source_refs:
        annotation = annotation_lookup.get(ref) or {}
        raw_value = annotation.get(field)
        if isinstance(raw_value, list):
            values = merge_unique_strings(values, [normalize_string(item) for item in raw_value])
        else:
            normalized = normalize_string(raw_value)
            if normalized and normalized not in values:
                values.append(normalized)
    return values


def collect_source_spot_ids(source_refs: Iterable[str]) -> List[str]:
    spot_ids: List[str] = []
    for ref in source_refs:
        normalized = normalize_string(ref)
        if ":" not in normalized:
            continue
        spot_id = normalized.split(":", 1)[0]
        if spot_id and spot_id not in spot_ids:
            spot_ids.append(spot_id)
    return spot_ids


def build_context_bundle(snapshot: Dict[str, Any], recent_rollups: List[Dict[str, str]]) -> Dict[str, Any]:
    active_tasks = [
        task
        for task in ensure_list(snapshot.get("active_tasks"))
        if isinstance(task, dict) and normalize_string(task.get("status")) in {"open", "in_progress"}
    ]
    active_tasks.sort(
        key=lambda task: (
            {"high": 0, "medium": 1, "low": 2}.get(normalize_string(task.get("priority")).lower(), 1),
            normalize_string(task.get("last_seen_at")),
        )
    )

    active_decisions = [
        decision
        for decision in ensure_list(snapshot.get("decision_log"))
        if isinstance(decision, dict) and normalize_string(decision.get("status")) == "active"
    ]
    active_knowledge = [
        knowledge
        for knowledge in ensure_list(snapshot.get("durable_knowledge"))
        if isinstance(knowledge, dict) and normalize_string(knowledge.get("status")) == "current"
    ]

    chronology_lines: List[str] = []
    for entry in recent_rollups:
        chronology_lines.append(
            f"{entry['date']}: {entry['headline']} {entry['status_summary'] or entry['abstract']}".strip()
        )

    priority_items: List[str] = []
    for task in active_tasks[:5]:
        priority_items.append(f"task: {normalize_string(task.get('title'))}")
    for decision in active_decisions[:3]:
        priority_items.append(f"decision: {normalize_string(decision.get('statement'))}")
    for knowledge in active_knowledge[:3]:
        priority_items.append(
            f"knowledge: [{normalize_string(knowledge.get('category'))}] {normalize_string(knowledge.get('title'))}"
        )

    return {
        "bundle_date": normalize_string(snapshot.get("date")),
        "recent_chronology_summary": "\n".join(chronology_lines).strip(),
        "recent_daily_context": recent_rollups,
        "active_task_refs": [normalize_string(task.get("task_id")) for task in active_tasks[:12]],
        "active_decision_refs": [normalize_string(decision.get("decision_id")) for decision in active_decisions[:8]],
        "active_knowledge_refs": [normalize_string(knowledge.get("knowledge_id")) for knowledge in active_knowledge[:8]],
        "priority_items": priority_items,
    }


def build_state_delta(
    prior_snapshot: Optional[Dict[str, Any]],
    current_snapshot: Dict[str, Any],
    current_daily: Dict[str, Any],
) -> Dict[str, Any]:
    previous_tasks = {
        normalize_string(task.get("task_key")): task
        for task in ensure_list((prior_snapshot or {}).get("active_tasks"))
        if isinstance(task, dict)
    }
    current_tasks = {
        normalize_string(task.get("task_key")): task
        for task in ensure_list(current_snapshot.get("active_tasks"))
        if isinstance(task, dict)
    }
    previous_decisions = {
        normalize_string(decision.get("decision_key")): decision
        for decision in ensure_list((prior_snapshot or {}).get("decision_log"))
        if isinstance(decision, dict)
    }
    current_decisions = {
        normalize_string(decision.get("decision_key")): decision
        for decision in ensure_list(current_snapshot.get("decision_log"))
        if isinstance(decision, dict)
    }
    previous_knowledge = {
        normalize_string(knowledge.get("knowledge_key")): knowledge
        for knowledge in ensure_list((prior_snapshot or {}).get("durable_knowledge"))
        if isinstance(knowledge, dict)
    }
    current_knowledge = {
        normalize_string(knowledge.get("knowledge_key")): knowledge
        for knowledge in ensure_list(current_snapshot.get("durable_knowledge"))
        if isinstance(knowledge, dict)
    }

    task_mutations: List[Dict[str, Any]] = []
    for task in ensure_list(current_daily.get("open_tasks")):
        if not isinstance(task, dict):
            continue
        key = stable_task_key(normalize_string(task.get("title")))
        if not key:
            continue
        current = current_tasks.get(key)
        if not current:
            continue
        previous = previous_tasks.get(key)
        mutation = "create" if previous is None else "touch"
        if previous and normalize_string(previous.get("status")) in {"done", "superseded", "dropped"}:
            mutation = "reopen"
        task_mutations.append(
            {
                "mutation": mutation,
                "task_id": normalize_string(current.get("task_id")),
                "task_key": key,
                "title": normalize_string(current.get("title")),
                "status": normalize_string(current.get("status")),
                "source_refs": ensure_str_list(task.get("source_refs")),
                "reason": "seen_in_daily_open_tasks",
            }
        )

    decision_mutations: List[Dict[str, Any]] = []
    for decision in ensure_list(current_daily.get("decisions")):
        if not isinstance(decision, dict):
            continue
        key = stable_decision_key(normalize_string(decision.get("summary")))
        if not key:
            continue
        current = current_decisions.get(key)
        if not current:
            continue
        mutation = "create" if key not in previous_decisions else "confirm"
        decision_mutations.append(
            {
                "mutation": mutation,
                "decision_id": normalize_string(current.get("decision_id")),
                "decision_key": key,
                "statement": normalize_string(current.get("statement")),
                "status": normalize_string(current.get("status")),
                "source_refs": ensure_str_list(decision.get("source_refs")),
            }
        )

    knowledge_mutations: List[Dict[str, Any]] = []
    for knowledge in ensure_list(current_daily.get("knowledge_refs")):
        if not isinstance(knowledge, dict):
            continue
        key = stable_knowledge_key(
            normalize_string(knowledge.get("category")),
            normalize_string(knowledge.get("title")),
        )
        if not key:
            continue
        current = current_knowledge.get(key)
        if not current:
            continue
        previous = previous_knowledge.get(key)
        mutation = "create" if previous is None else "confirm"
        if previous and normalize_string(previous.get("summary")) != normalize_string(current.get("summary")):
            mutation = "update"
        knowledge_mutations.append(
            {
                "mutation": mutation,
                "knowledge_id": normalize_string(current.get("knowledge_id")),
                "knowledge_key": key,
                "title": normalize_string(current.get("title")),
                "category": normalize_string(current.get("category")),
                "status": normalize_string(current.get("status")),
                "source_refs": ensure_str_list(knowledge.get("source_refs")),
            }
        )

    return {
        "date": normalize_string(current_snapshot.get("date")),
        "generated_at": datetime.now().astimezone().isoformat(),
        "prior_snapshot_date": normalize_string((prior_snapshot or {}).get("date")) or None,
        "current_snapshot_date": normalize_string(current_snapshot.get("date")),
        "task_mutations": task_mutations,
        "decision_mutations": decision_mutations,
        "knowledge_mutations": knowledge_mutations,
    }


def build_stateful_daily_rollup(
    current_daily: Dict[str, Any],
    current_snapshot: Dict[str, Any],
    prior_snapshot: Optional[Dict[str, Any]],
    prior_bundle: Optional[Dict[str, Any]],
) -> Dict[str, Any]:
    previous_tasks = {
        normalize_string(task.get("task_key"))
        for task in ensure_list((prior_snapshot or {}).get("active_tasks"))
        if isinstance(task, dict)
    }
    previous_knowledge = {
        normalize_string(knowledge.get("knowledge_key"))
        for knowledge in ensure_list((prior_snapshot or {}).get("durable_knowledge"))
        if isinstance(knowledge, dict)
    }

    current_task_lookup = {
        normalize_string(task.get("task_key")): task
        for task in ensure_list(current_snapshot.get("active_tasks"))
        if isinstance(task, dict)
    }
    current_knowledge_lookup = {
        normalize_string(knowledge.get("knowledge_key")): knowledge
        for knowledge in ensure_list(current_snapshot.get("durable_knowledge"))
        if isinstance(knowledge, dict)
    }

    carried_over_tasks: List[Dict[str, Any]] = []
    newly_opened_tasks: List[Dict[str, Any]] = []
    for task in ensure_list(current_daily.get("open_tasks")):
        if not isinstance(task, dict):
            continue
        title = normalize_string(task.get("title"))
        key = stable_task_key(title)
        snapshot_task = current_task_lookup.get(key, {})
        row = {
            "task_id": normalize_string(snapshot_task.get("task_id")),
            "task_key": key,
            "title": title,
            "summary": normalize_string(task.get("summary")),
            "priority": normalize_string(task.get("priority")).lower() or "medium",
            "source_refs": ensure_str_list(task.get("source_refs")),
        }
        if key in previous_tasks:
            carried_over_tasks.append(row)
        else:
            newly_opened_tasks.append(row)

    updated_knowledge: List[Dict[str, Any]] = []
    for knowledge in ensure_list(current_daily.get("knowledge_refs")):
        if not isinstance(knowledge, dict):
            continue
        title = normalize_string(knowledge.get("title"))
        category = normalize_string(knowledge.get("category"))
        key = stable_knowledge_key(category, title)
        snapshot_knowledge = current_knowledge_lookup.get(key, {})
        updated_knowledge.append(
            {
                "knowledge_id": normalize_string(snapshot_knowledge.get("knowledge_id")),
                "knowledge_key": key,
                "title": title,
                "summary": normalize_string(knowledge.get("summary")),
                "category": category,
                "status": "confirmed" if key in previous_knowledge else "new",
                "source_refs": ensure_str_list(knowledge.get("source_refs")),
            }
        )

    continuing_count = len(carried_over_tasks)
    new_count = len(newly_opened_tasks)
    prior_priorities = ensure_str_list((prior_bundle or {}).get("priority_items"))
    status_summary = normalize_string(current_daily.get("status_summary"))
    if continuing_count:
        status_summary = (
            f"{status_summary} 前日までの継続タスク {continuing_count} 件を引き継ぎつつ、"
            f"新規タスク {new_count} 件が追加された。"
        ).strip()

    abstract = normalize_string(current_daily.get("abstract"))
    if prior_priorities:
        abstract = f"{abstract} 前日までの重要文脈として、{prior_priorities[0]} を含む継続状態を参照している。".strip()

    return {
        "date": normalize_string(current_daily.get("date")),
        "headline": normalize_string(current_daily.get("headline")),
        "abstract": abstract,
        "status_summary": status_summary,
        "prior_snapshot_date": normalize_string((prior_snapshot or {}).get("date")) or None,
        "prior_context_bundle_date": normalize_string((prior_bundle or {}).get("bundle_date")) or None,
        "source_dataset_ids": ensure_str_list(current_daily.get("source_dataset_ids")),
        "source_spots": ensure_list(current_daily.get("source_spots")),
        "main_threads": ensure_list(current_daily.get("main_threads")),
        "decisions_today": ensure_list(current_daily.get("decisions")),
        "carried_over_tasks": carried_over_tasks,
        "newly_opened_tasks": newly_opened_tasks,
        "updated_knowledge": updated_knowledge,
        "continuing_priorities": prior_priorities,
    }


def build_stateful_daily_preview(payload: Dict[str, Any]) -> str:
    lines: List[str] = []
    lines.append(f"# Stateful Daily Rollup: {payload['date']}")
    lines.append("")
    lines.append(f"## {payload['headline']}")
    lines.append("")
    lines.append(payload["abstract"])
    lines.append("")
    lines.append("### Current Status")
    lines.append("")
    lines.append(payload["status_summary"])
    lines.append("")

    priorities = ensure_str_list(payload.get("continuing_priorities"))
    lines.append("## Continuing Priorities")
    lines.append("")
    if not priorities:
        lines.append("- (none)")
    else:
        for item in priorities:
            lines.append(f"- {item}")
    lines.append("")

    lines.append("## Main Threads")
    lines.append("")
    for thread in ensure_list(payload.get("main_threads")):
        if not isinstance(thread, dict):
            continue
        lines.append(f"### {normalize_string(thread.get('title'))}")
        lines.append("")
        lines.append(normalize_string(thread.get("summary")))
        lines.append("")

    def render_tasks(header: str, rows: List[Dict[str, Any]]) -> None:
        lines.append(header)
        lines.append("")
        if not rows:
            lines.append("- (none)")
            lines.append("")
            return
        for row in rows:
            lines.append(f"- [{normalize_string(row.get('priority'))}] {normalize_string(row.get('title'))}")
            summary = normalize_string(row.get("summary"))
            if summary:
                lines.append(f"  summary: {summary}")
        lines.append("")

    render_tasks("## Carried-over Tasks", ensure_list(payload.get("carried_over_tasks")))
    render_tasks("## Newly Opened Tasks", ensure_list(payload.get("newly_opened_tasks")))

    lines.append("## Decisions Today")
    lines.append("")
    decisions = ensure_list(payload.get("decisions_today"))
    if not decisions:
        lines.append("- (none)")
        lines.append("")
    else:
        for decision in decisions:
            if isinstance(decision, dict):
                lines.append(f"- {normalize_string(decision.get('summary'))}")
        lines.append("")

    lines.append("## Updated Knowledge")
    lines.append("")
    knowledge_rows = ensure_list(payload.get("updated_knowledge"))
    if not knowledge_rows:
        lines.append("- (none)")
        lines.append("")
    else:
        for row in knowledge_rows:
            if isinstance(row, dict):
                lines.append(
                    f"- [{normalize_string(row.get('category'))}] {normalize_string(row.get('title'))} ({normalize_string(row.get('status'))})"
                )
                lines.append(f"  summary: {normalize_string(row.get('summary'))}")
        lines.append("")

    return "\n".join(lines).rstrip() + "\n"
