#!/usr/bin/env python3
"""Aggregate spot wrap-ups into a day-level rollup."""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Set, Tuple

from generate_spot_wrapup import (  # type: ignore
    extract_json,
    load_env_file,
    load_json,
    normalize_string,
    resolve_window_bound,
    write_json,
    write_text,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--date",
        help="Target date in YYYY-MM-DD. When set, matching spot datasets are auto-discovered.",
    )
    parser.add_argument(
        "--dataset-dir",
        action="append",
        default=[],
        help="Explicit spot dataset directory. Can be passed multiple times.",
    )
    parser.add_argument(
        "--artifacts-root",
        default="experiments/amical/artifacts",
        help="Artifacts root for auto-discovery and daily output placement.",
    )
    parser.add_argument("--provider", default=None, help="Override LLM provider")
    parser.add_argument("--model", default=None, help="Override LLM model")
    return parser.parse_args()


def ensure_list(values: Any) -> List[str]:
    if not isinstance(values, list):
        return []
    result: List[str] = []
    for value in values:
        normalized = normalize_string(value)
        if normalized:
            result.append(normalized)
    return result


def parse_iso_datetime(value: str) -> Optional[datetime]:
    text = normalize_string(value)
    if not text:
        return None
    try:
        return datetime.fromisoformat(text.replace("Z", "+00:00"))
    except Exception:
        return None


def load_spot_wrapup(dataset_dir: Path) -> Optional[Dict[str, Any]]:
    preferred = dataset_dir / "07_spot_wrapup.json"
    legacy = dataset_dir / "07_daily_wrapup.json"
    if preferred.exists():
        return load_json(preferred)
    if legacy.exists():
        return load_json(legacy)
    return None


def collect_dataset_dirs(args: argparse.Namespace) -> Tuple[List[Path], str]:
    artifacts_root = Path(args.artifacts_root).expanduser().resolve()
    explicit_dirs = [Path(value).expanduser().resolve() for value in args.dataset_dir]
    if explicit_dirs:
        date_value = args.date or infer_date_from_dirs(explicit_dirs)
        return explicit_dirs, date_value
    if not args.date:
        raise SystemExit("Provide either --date or at least one --dataset-dir")

    selected: List[Path] = []
    for child in sorted(artifacts_root.iterdir()):
        if not child.is_dir() or child.name == "daily-rollups":
            continue
        manifest_path = child / "00_source_window.json"
        if not manifest_path.exists():
            continue
        manifest = load_json(manifest_path)
        window_start = resolve_window_bound(manifest, "window_start", "start_at")
        if not window_start:
            continue
        start_dt = parse_iso_datetime(window_start)
        if not start_dt or start_dt.date().isoformat() != args.date:
            continue
        if load_spot_wrapup(child) is None:
            continue
        selected.append(child)
    if not selected:
        raise SystemExit(f"No spot wrap-ups found for date {args.date} under {artifacts_root}")
    return selected, args.date


def infer_date_from_dirs(dataset_dirs: List[Path]) -> str:
    inferred: List[str] = []
    for dataset_dir in dataset_dirs:
        manifest = load_json(dataset_dir / "00_source_window.json")
        value = resolve_window_bound(manifest, "window_start", "start_at")
        parsed = parse_iso_datetime(value or "")
        if parsed:
            inferred.append(parsed.date().isoformat())
    inferred = sorted(set(inferred))
    if len(inferred) != 1:
        raise SystemExit("Explicit --dataset-dir values span multiple dates; pass --date explicitly")
    return inferred[0]


def build_spot_index(dataset_dirs: List[Path]) -> List[Dict[str, Any]]:
    spots: List[Dict[str, Any]] = []
    for index, dataset_dir in enumerate(sorted(dataset_dirs), start=1):
        manifest = load_json(dataset_dir / "00_source_window.json")
        wrapup = load_spot_wrapup(dataset_dir)
        if wrapup is None:
            continue
        spot_id = f"SPOT-{index:03d}"
        annotations = wrapup.get("annotations")
        if not isinstance(annotations, list):
            annotations = []
        open_tasks = wrapup.get("open_tasks")
        if not isinstance(open_tasks, list):
            open_tasks = []
        knowledge_refs = wrapup.get("knowledge_refs")
        if not isinstance(knowledge_refs, list):
            knowledge_refs = []
        decisions = wrapup.get("decisions")
        if not isinstance(decisions, list):
            decisions = []
        sections = wrapup.get("narrative_sections")
        if not isinstance(sections, list):
            sections = []

        spots.append(
            {
                "spot_id": spot_id,
                "dataset_id": normalize_string(wrapup.get("dataset_id") or manifest.get("dataset_id")),
                "dataset_dir": str(dataset_dir),
                "start_at": resolve_window_bound(wrapup, "start_at") or resolve_window_bound(manifest, "window_start"),
                "end_at": resolve_window_bound(wrapup, "end_at") or resolve_window_bound(manifest, "window_end"),
                "headline": normalize_string(wrapup.get("headline")),
                "abstract": normalize_string(wrapup.get("abstract")),
                "status_summary": normalize_string(wrapup.get("status_summary")),
                "sections": sections,
                "decisions": decisions,
                "open_tasks": open_tasks,
                "knowledge_refs": knowledge_refs,
                "annotations": annotations,
            }
        )
    return spots


def build_prompt(date_value: str, spots: List[Dict[str, Any]]) -> str:
    spot_lines: List[str] = []
    source_ref_lines: List[str] = []

    for spot in spots:
        spot_lines.append(
            f"- {spot['spot_id']} dataset={spot['dataset_id']} "
            f"window={spot['start_at']} - {spot['end_at']} "
            f"headline={spot['headline']} abstract={spot['abstract']} "
            f"status={spot['status_summary']}"
        )
        for section in spot.get("sections") or []:
            if not isinstance(section, dict):
                continue
            spot_lines.append(
                f"  section title={normalize_string(section.get('title'))} "
                f"summary={normalize_string(section.get('summary'))}"
            )

        for decision in spot.get("decisions") or []:
            if not isinstance(decision, dict):
                continue
            refs = ensure_list(decision.get("annotation_ids"))
            spot_lines.append(
                f"  decision summary={normalize_string(decision.get('summary'))} "
                f"refs={','.join(f'{spot['spot_id']}:{ref}' for ref in refs) or '(none)'}"
            )

        for task in spot.get("open_tasks") or []:
            if not isinstance(task, dict):
                continue
            annotation_id = normalize_string(task.get("annotation_id"))
            source_ref = f"{spot['spot_id']}:{annotation_id}" if annotation_id else "(none)"
            spot_lines.append(
                f"  task ref={source_ref} priority={normalize_string(task.get('priority')) or 'medium'} "
                f"title={normalize_string(task.get('title'))} summary={normalize_string(task.get('summary'))}"
            )

        for ref in spot.get("knowledge_refs") or []:
            if not isinstance(ref, dict):
                continue
            annotation_id = normalize_string(ref.get("annotation_id"))
            source_ref = f"{spot['spot_id']}:{annotation_id}" if annotation_id else "(none)"
            spot_lines.append(
                f"  knowledge ref={source_ref} category={normalize_string(ref.get('category'))} "
                f"title={normalize_string(ref.get('title'))} summary={normalize_string(ref.get('summary'))}"
            )

        for annotation in spot.get("annotations") or []:
            if not isinstance(annotation, dict):
                continue
            annotation_id = normalize_string(annotation.get("annotation_id"))
            if not annotation_id:
                continue
            source_ref_lines.append(
                f"- {spot['spot_id']}:{annotation_id} type={normalize_string(annotation.get('item_type'))} "
                f"title={normalize_string(annotation.get('title'))} "
                f"summary={normalize_string(annotation.get('summary'))} "
                f"category={normalize_string(annotation.get('category')) or '(none)'} "
                f"priority={normalize_string(annotation.get('priority')) or '(none)'} "
                f"task_kind={normalize_string(annotation.get('task_kind')) or '(none)'}"
            )

    spot_digest = "\n".join(spot_lines) or "- (none)"
    source_ref_digest = "\n".join(source_ref_lines) or "- (none)"

    return f"""You are aggregating multiple spot wrap-ups into one daily rollup.

Goal:
- Summarize the day at a higher level than each spot
- Merge repeated themes across spots
- Keep daily tasks selective and actionable
- Preserve links back to spot-level evidence through source refs

Rules:
- Use ONLY the provided spot summaries and source refs
- Do not invent work, decisions, or knowledge
- Prefer 3-6 main threads, up to 6 decisions, up to 10 open tasks, up to 10 knowledge refs
- Open tasks should merge duplicates across spots when they clearly refer to the same work
- Knowledge refs should capture durable themes worth revisiting after the day ends
- Do NOT embed source refs inside prose; use structured arrays only
- Output Japanese

Output ONLY JSON:
{{
  "headline": "string",
  "abstract": "2-4 sentence Japanese summary",
  "status_summary": "1-3 sentence current-state summary",
  "main_threads": [
    {{
      "title": "string",
      "summary": "short paragraph",
      "source_spot_ids": ["SPOT-001"]
    }}
  ],
  "decisions": [
    {{
      "summary": "explicit decision or stable conclusion",
      "source_refs": ["SPOT-001:K001", "SPOT-002:T003"]
    }}
  ],
  "open_tasks": [
    {{
      "title": "string",
      "summary": "string",
      "priority": "high|medium|low",
      "source_refs": ["SPOT-001:T001"]
    }}
  ],
  "knowledge_refs": [
    {{
      "title": "string",
      "summary": "string",
      "category": "string",
      "source_refs": ["SPOT-001:K001"]
    }}
  ]
}}

# Target Date
date: {date_value}

# Spot Digest
{spot_digest}

# Source Ref Catalog
{source_ref_digest}
"""


def normalize_id_list(values: Any, allowed: Set[str]) -> List[str]:
    if not isinstance(values, list):
        return []
    result: List[str] = []
    for value in values:
        normalized = normalize_string(value)
        if normalized and normalized in allowed and normalized not in result:
            result.append(normalized)
    return result


def refs_to_spot_ids(refs: Iterable[str]) -> List[str]:
    result: List[str] = []
    for ref in refs:
        if ":" not in ref:
            continue
        spot_id = ref.split(":", 1)[0]
        if spot_id not in result:
            result.append(spot_id)
    return result


def normalize_threads(raw_threads: Any, valid_spot_ids: Set[str]) -> List[Dict[str, Any]]:
    if not isinstance(raw_threads, list):
        return []
    rows: List[Dict[str, Any]] = []
    for index, raw in enumerate(raw_threads, start=1):
        if not isinstance(raw, dict):
            continue
        title = normalize_string(raw.get("title"))
        summary = normalize_string(raw.get("summary"))
        source_spot_ids = normalize_id_list(raw.get("source_spot_ids"), valid_spot_ids)
        if not title or not summary or not source_spot_ids:
            continue
        rows.append(
            {
                "thread_id": f"thread-{index:02d}",
                "title": title,
                "summary": summary,
                "source_spot_ids": source_spot_ids,
            }
        )
    return rows[:6]


def normalize_decisions(raw_rows: Any, valid_refs: Set[str]) -> List[Dict[str, Any]]:
    if not isinstance(raw_rows, list):
        return []
    rows: List[Dict[str, Any]] = []
    for raw in raw_rows:
        if not isinstance(raw, dict):
            continue
        summary = normalize_string(raw.get("summary"))
        source_refs = normalize_id_list(raw.get("source_refs"), valid_refs)
        if not summary or not source_refs:
            continue
        rows.append(
            {
                "summary": summary,
                "source_refs": source_refs,
                "source_spot_ids": refs_to_spot_ids(source_refs),
            }
        )
    return rows[:6]


def normalize_open_tasks(raw_rows: Any, valid_refs: Set[str]) -> List[Dict[str, Any]]:
    if not isinstance(raw_rows, list):
        return []
    rows: List[Dict[str, Any]] = []
    for raw in raw_rows:
        if not isinstance(raw, dict):
            continue
        title = normalize_string(raw.get("title"))
        summary = normalize_string(raw.get("summary"))
        priority = normalize_string(raw.get("priority")).lower() or "medium"
        if priority not in {"high", "medium", "low"}:
            priority = "medium"
        source_refs = normalize_id_list(raw.get("source_refs"), valid_refs)
        if not title or not summary or not source_refs:
            continue
        rows.append(
            {
                "title": title,
                "summary": summary,
                "priority": priority,
                "source_refs": source_refs,
                "source_spot_ids": refs_to_spot_ids(source_refs),
            }
        )
    return dedupe_rows(rows, key_fields=("title",))[:10]


def normalize_knowledge_refs(raw_rows: Any, valid_refs: Set[str]) -> List[Dict[str, Any]]:
    if not isinstance(raw_rows, list):
        return []
    rows: List[Dict[str, Any]] = []
    for raw in raw_rows:
        if not isinstance(raw, dict):
            continue
        title = normalize_string(raw.get("title"))
        summary = normalize_string(raw.get("summary"))
        category = normalize_string(raw.get("category"))
        source_refs = normalize_id_list(raw.get("source_refs"), valid_refs)
        if not title or not summary or not category or not source_refs:
            continue
        rows.append(
            {
                "title": title,
                "summary": summary,
                "category": category,
                "source_refs": source_refs,
                "source_spot_ids": refs_to_spot_ids(source_refs),
            }
        )
    return dedupe_rows(rows, key_fields=("title", "category"))[:10]


def dedupe_rows(rows: List[Dict[str, Any]], key_fields: Tuple[str, ...]) -> List[Dict[str, Any]]:
    seen: Set[Tuple[str, ...]] = set()
    result: List[Dict[str, Any]] = []
    for row in rows:
        key = tuple(normalize_string(row.get(field)).lower() for field in key_fields)
        if key in seen:
            continue
        seen.add(key)
        result.append(row)
    return result


def build_preview(report: Dict[str, Any], rollup: Dict[str, Any]) -> str:
    lines: List[str] = []
    lines.append(f"# Daily Rollup Preview: {report['date']}")
    lines.append("")
    lines.append(f"- `spot_count`: {report['spot_count']}")
    lines.append(f"- `thread_count`: {report['thread_count']}")
    lines.append(f"- `decision_count`: {report['decision_count']}")
    lines.append(f"- `open_task_count`: {report['open_task_count']}")
    lines.append(f"- `knowledge_ref_count`: {report['knowledge_ref_count']}")
    lines.append(f"- `llm_provider`: {report['llm_provider']}")
    lines.append(f"- `llm_model`: {report['llm_model']}")
    lines.append("")
    lines.append(f"## {rollup['headline']}")
    lines.append("")
    lines.append(rollup["abstract"])
    lines.append("")
    lines.append("### Current Status")
    lines.append("")
    lines.append(rollup["status_summary"])
    lines.append("")
    lines.append("## Main Threads")
    lines.append("")
    for thread in rollup["main_threads"]:
        lines.append(f"### {thread['title']}")
        lines.append("")
        lines.append(thread["summary"])
        lines.append("")
        lines.append(f"- spots: {', '.join(thread['source_spot_ids'])}")
        lines.append("")
    lines.append("## Decisions / Stable Conclusions")
    lines.append("")
    if not rollup["decisions"]:
        lines.append("- (none)")
        lines.append("")
    for decision in rollup["decisions"]:
        lines.append(f"- {decision['summary']}")
        lines.append(f"  refs: {', '.join(decision['source_refs'])}")
    lines.append("")
    lines.append("## Open Tasks")
    lines.append("")
    if not rollup["open_tasks"]:
        lines.append("- (none)")
        lines.append("")
    for task in rollup["open_tasks"]:
        lines.append(f"- [{task['priority']}] {task['title']}")
        lines.append(f"  summary: {task['summary']}")
        lines.append(f"  refs: {', '.join(task['source_refs'])}")
    lines.append("")
    lines.append("## Knowledge References")
    lines.append("")
    if not rollup["knowledge_refs"]:
        lines.append("- (none)")
        lines.append("")
    for ref in rollup["knowledge_refs"]:
        lines.append(f"- [{ref['category']}] {ref['title']}")
        lines.append(f"  summary: {ref['summary']}")
        lines.append(f"  refs: {', '.join(ref['source_refs'])}")
    lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def main() -> None:
    args = parse_args()
    artifacts_root = Path(args.artifacts_root).expanduser().resolve()
    dataset_dirs, date_value = collect_dataset_dirs(args)
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

    spots = build_spot_index(dataset_dirs)
    valid_spot_ids = {spot["spot_id"] for spot in spots}
    valid_refs = {
        f"{spot['spot_id']}:{normalize_string(annotation.get('annotation_id'))}"
        for spot in spots
        for annotation in spot.get("annotations") or []
        if normalize_string(annotation.get("annotation_id"))
    }

    prompt = build_prompt(date_value, spots)
    raw_output = llm.generate(prompt)
    payload = extract_json(raw_output) or {}

    headline = normalize_string(payload.get("headline")) or f"{date_value} daily rollup"
    abstract = normalize_string(payload.get("abstract")) or "この日の spot wrap-up 群から主要な論点、タスク、知識参照を日次粒度へ集約した。"
    status_summary = normalize_string(payload.get("status_summary")) or "この日の議論を spot より粗い粒度で読み返せるように再構成した。"
    main_threads = normalize_threads(payload.get("main_threads"), valid_spot_ids)
    decisions = normalize_decisions(payload.get("decisions"), valid_refs)
    open_tasks = normalize_open_tasks(payload.get("open_tasks"), valid_refs)
    knowledge_refs = normalize_knowledge_refs(payload.get("knowledge_refs"), valid_refs)

    rollup = {
        "date": date_value,
        "headline": headline,
        "abstract": abstract,
        "status_summary": status_summary,
        "source_dataset_ids": [spot["dataset_id"] for spot in spots],
        "source_spots": [
            {
                "spot_id": spot["spot_id"],
                "dataset_id": spot["dataset_id"],
                "start_at": spot["start_at"],
                "end_at": spot["end_at"],
                "headline": spot["headline"],
            }
            for spot in spots
        ],
        "main_threads": main_threads,
        "decisions": decisions,
        "open_tasks": open_tasks,
        "knowledge_refs": knowledge_refs,
    }

    provider_name, model_name = llm.model_name.split("/", 1) if "/" in llm.model_name else ("unknown", llm.model_name)
    report = {
        "date": date_value,
        "generated_at": datetime.now().astimezone().isoformat(),
        "spot_count": len(spots),
        "thread_count": len(main_threads),
        "decision_count": len(decisions),
        "open_task_count": len(open_tasks),
        "knowledge_ref_count": len(knowledge_refs),
        "llm_provider": provider_name,
        "llm_model": model_name,
    }

    output_dir = artifacts_root / "daily-rollups" / date_value
    output_dir.mkdir(parents=True, exist_ok=True)
    write_json(output_dir / "08_daily_rollup.json", rollup)
    write_json(output_dir / "08_daily_rollup_report.json", report)
    write_text(output_dir / "08_daily_rollup_preview.md", build_preview(report, rollup))

    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
