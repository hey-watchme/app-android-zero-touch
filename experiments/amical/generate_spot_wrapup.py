#!/usr/bin/env python3
"""Generate a readable spot wrap-up document from canonical Amical artifacts."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Set


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--dataset-dir",
        required=True,
        help="Artifact directory containing 03/05/06 outputs",
    )
    parser.add_argument("--provider", default=None, help="Override LLM provider")
    parser.add_argument("--model", default=None, help="Override LLM model")
    return parser.parse_args()


def load_env_file(path: Path) -> None:
    if not path.exists():
        return
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line or line.startswith("export "):
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())


def load_json(path: Path) -> Dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def load_jsonl(path: Path) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def write_json(path: Path, payload: Any) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_text(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def extract_json(text: str) -> Optional[Dict[str, Any]]:
    cleaned = (text or "").strip()
    if not cleaned:
        return None
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?", "", cleaned).strip()
        cleaned = cleaned.rstrip("`").strip()
    candidates = [cleaned]
    match = re.search(r"\{[\s\S]*\}", cleaned)
    if match:
        candidates.append(match.group(0))
    for candidate in candidates:
        try:
            payload = json.loads(candidate)
            if isinstance(payload, dict):
                return payload
        except Exception:
            continue
    return None


def normalize_string(value: Any) -> str:
    return " ".join(str(value or "").split()).strip()


def resolve_window_bound(manifest: Dict[str, Any], *keys: str) -> Optional[str]:
    for key in keys:
        value = normalize_string(manifest.get(key))
        if value:
            return value
    return None


def shorten(text: str, limit: int = 160) -> str:
    normalized = normalize_string(text)
    if len(normalized) <= limit:
        return normalized
    return normalized[: limit - 1].rstrip() + "…"


def build_topic_lookup(topics: List[Dict[str, Any]]) -> Dict[str, Dict[str, Any]]:
    return {str(topic.get("topic_id")): topic for topic in topics if topic.get("topic_id")}


def build_items_by_topic(items: List[Dict[str, Any]]) -> Dict[str, List[Dict[str, Any]]]:
    grouped: Dict[str, List[Dict[str, Any]]] = {}
    for item in items:
        topic_id = str(item.get("topic_id") or "").strip()
        if not topic_id:
            continue
        grouped.setdefault(topic_id, []).append(item)
    return grouped


def build_annotation_catalog(canonical_items: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    tasks = [item for item in canonical_items if item.get("item_type") == "task"]
    knowledge = [item for item in canonical_items if item.get("item_type") == "knowledge"]
    catalog: List[Dict[str, Any]] = []

    for index, item in enumerate(tasks, start=1):
        catalog.append(
            {
                "annotation_id": f"T{index:03d}",
                "canonical_id": item.get("canonical_id"),
                "item_type": "task",
                "title": normalize_string(item.get("title")),
                "summary": normalize_string(item.get("summary")),
                "category": None,
                "priority": item.get("priority"),
                "task_kind": item.get("task_kind"),
                "source_topic_ids": item.get("source_topic_ids") or [],
                "evidence_utterance_ids": item.get("evidence_utterance_ids") or [],
            }
        )

    for index, item in enumerate(knowledge, start=1):
        catalog.append(
            {
                "annotation_id": f"K{index:03d}",
                "canonical_id": item.get("canonical_id"),
                "item_type": "knowledge",
                "title": normalize_string(item.get("title")),
                "summary": normalize_string(item.get("summary")),
                "category": normalize_string(item.get("category")),
                "priority": None,
                "task_kind": None,
                "source_topic_ids": item.get("source_topic_ids") or [],
                "evidence_utterance_ids": item.get("evidence_utterance_ids") or [],
            }
        )

    return catalog


def build_prompt(
    manifest: Dict[str, Any],
    topics: List[Dict[str, Any]],
    distilled_items: List[Dict[str, Any]],
    annotation_catalog: List[Dict[str, Any]],
) -> str:
    items_by_topic = build_items_by_topic(distilled_items)

    topic_lines: List[str] = []
    for topic in topics:
        topic_id = str(topic.get("topic_id") or "").strip()
        if not topic_id:
            continue
        topic_items = items_by_topic.get(topic_id, [])
        if not topic_items:
            continue
        task_count = sum(1 for item in topic_items if item.get("item_type") == "task")
        knowledge_count = sum(1 for item in topic_items if item.get("item_type") == "knowledge")
        topic_lines.append(
            f"- {topic_id} [{topic.get('start_at')} - {topic.get('end_at')}] "
            f"items={len(topic_items)} task={task_count} knowledge={knowledge_count} "
            f"preview={shorten(topic.get('preview_text') or '', 200)}"
        )
    topic_digest = "\n".join(topic_lines) or "- (none)"

    annotation_lines: List[str] = []
    for annotation in annotation_catalog:
        base = (
            f"- {annotation['annotation_id']} type={annotation['item_type']} "
            f"title={annotation['title']} summary={annotation['summary']}"
        )
        if annotation["item_type"] == "task":
            base += (
                f" priority={annotation.get('priority') or 'medium'}"
                f" kind={annotation.get('task_kind') or 'other'}"
            )
        else:
            base += f" category={annotation.get('category') or '(none)'}"
        base += (
            f" canonical_id={annotation.get('canonical_id') or '(none)'}"
            f" topics={','.join(annotation.get('source_topic_ids') or []) or '(none)'}"
        )
        annotation_lines.append(base)
    annotation_digest = "\n".join(annotation_lines) or "- (none)"

    return f"""You are creating a highly readable wrap-up memo from an ambient conversation dataset.

Goal:
- Produce a concise human-readable spot wrap-up for this time window
- Separate narrative summary, explicit tasks, and durable knowledge references
- Keep it useful as a daily/periodic review document, not as a raw dump
- Use annotations so a human can trace each claim back to canonical facts/items

Rules:
- Use ONLY the provided topics and annotation catalog
- Do not invent decisions, tasks, or knowledge
- Prefer clarity and compression over completeness
- Treat tasks as optional: include only meaningful actionable items
- Treat knowledge refs as reusable context worth revisiting later
- Narrative sections should cluster the major threads, not repeat every item
- Keep counts tight: 3-6 narrative sections, up to 5 decisions, up to 8 open tasks, up to 8 knowledge refs
- Every narrative section must cite annotation ids and topic ids
- Every decision must cite annotation ids
- Every open task entry must point to exactly one task annotation id
- Every knowledge_ref entry must point to exactly one knowledge annotation id
- Do NOT embed topic ids or annotation ids inside prose fields; use the structured arrays only
- Output Japanese

Output ONLY JSON:
{{
  "headline": "string",
  "abstract": "2-4 sentence Japanese summary",
  "status_summary": "1-3 sentence current-state summary",
  "narrative_sections": [
    {{
      "title": "string",
      "summary": "short paragraph",
      "source_topic_ids": ["topic-001"],
      "annotation_ids": ["T001", "K001"]
    }}
  ],
  "decisions": [
    {{
      "summary": "explicit decision or stable conclusion",
      "annotation_ids": ["K001", "T003"]
    }}
  ],
  "open_tasks": [
    {{
      "annotation_id": "T001",
      "why_now": "why this matters now"
    }}
  ],
  "knowledge_refs": [
    {{
      "annotation_id": "K001",
      "why_relevant": "why this should be revisited later"
    }}
  ]
}}

# Window Metadata
dataset_id: {manifest.get('dataset_id')}
start: {resolve_window_bound(manifest, 'start_at', 'window_start')}
end: {resolve_window_bound(manifest, 'end_at', 'window_end')}
candidate_count: {manifest.get('candidate_count')}
raw_corpus_count: {manifest.get('raw_corpus_count')}

# Topic Digest
{topic_digest}

# Annotation Catalog
{annotation_digest}
"""


def unique_preserve_order(values: Iterable[str]) -> List[str]:
    seen: Set[str] = set()
    result: List[str] = []
    for value in values:
        if value not in seen:
            seen.add(value)
            result.append(value)
    return result


def normalize_id_list(values: Any, allowed: Set[str]) -> List[str]:
    if not isinstance(values, list):
        return []
    result: List[str] = []
    for value in values:
        normalized = normalize_string(value)
        if normalized and normalized in allowed:
            result.append(normalized)
    return unique_preserve_order(result)


def normalize_sections(raw_sections: Any, valid_topic_ids: Set[str], valid_annotation_ids: Set[str]) -> List[Dict[str, Any]]:
    if not isinstance(raw_sections, list):
        return []
    sections: List[Dict[str, Any]] = []
    for index, raw in enumerate(raw_sections, start=1):
        if not isinstance(raw, dict):
            continue
        title = normalize_string(raw.get("title"))
        summary = normalize_string(raw.get("summary"))
        source_topic_ids = normalize_id_list(raw.get("source_topic_ids"), valid_topic_ids)
        annotation_ids = normalize_id_list(raw.get("annotation_ids"), valid_annotation_ids)
        if not title or not summary or not source_topic_ids or not annotation_ids:
            continue
        sections.append(
            {
                "section_id": f"section-{index:02d}",
                "title": title,
                "summary": summary,
                "source_topic_ids": source_topic_ids,
                "annotation_ids": annotation_ids,
            }
        )
    return sections[:6]


def normalize_decisions(raw_decisions: Any, valid_annotation_ids: Set[str]) -> List[Dict[str, Any]]:
    if not isinstance(raw_decisions, list):
        return []
    decisions: List[Dict[str, Any]] = []
    for raw in raw_decisions:
        if not isinstance(raw, dict):
            continue
        summary = normalize_string(raw.get("summary"))
        annotation_ids = normalize_id_list(raw.get("annotation_ids"), valid_annotation_ids)
        if not summary or not annotation_ids:
            continue
        decisions.append({"summary": summary, "annotation_ids": annotation_ids})
    return decisions[:5]


def normalize_open_tasks(raw_tasks: Any, annotation_lookup: Dict[str, Dict[str, Any]]) -> List[Dict[str, Any]]:
    if not isinstance(raw_tasks, list):
        return []
    tasks: List[Dict[str, Any]] = []
    for raw in raw_tasks:
        if not isinstance(raw, dict):
            continue
        annotation_id = normalize_string(raw.get("annotation_id"))
        annotation = annotation_lookup.get(annotation_id)
        if not annotation or annotation.get("item_type") != "task":
            continue
        why_now = normalize_string(raw.get("why_now"))
        tasks.append(
            {
                "annotation_id": annotation_id,
                "title": annotation["title"],
                "summary": annotation["summary"],
                "priority": annotation.get("priority"),
                "task_kind": annotation.get("task_kind"),
                "canonical_id": annotation.get("canonical_id"),
                "source_topic_ids": annotation.get("source_topic_ids") or [],
                "evidence_utterance_ids": annotation.get("evidence_utterance_ids") or [],
                "why_now": why_now or annotation["summary"],
            }
        )
    return unique_by_annotation(tasks)[:8]


def normalize_knowledge_refs(raw_refs: Any, annotation_lookup: Dict[str, Dict[str, Any]]) -> List[Dict[str, Any]]:
    if not isinstance(raw_refs, list):
        return []
    refs: List[Dict[str, Any]] = []
    for raw in raw_refs:
        if not isinstance(raw, dict):
            continue
        annotation_id = normalize_string(raw.get("annotation_id"))
        annotation = annotation_lookup.get(annotation_id)
        if not annotation or annotation.get("item_type") != "knowledge":
            continue
        why_relevant = normalize_string(raw.get("why_relevant"))
        refs.append(
            {
                "annotation_id": annotation_id,
                "title": annotation["title"],
                "summary": annotation["summary"],
                "category": annotation.get("category"),
                "canonical_id": annotation.get("canonical_id"),
                "source_topic_ids": annotation.get("source_topic_ids") or [],
                "evidence_utterance_ids": annotation.get("evidence_utterance_ids") or [],
                "why_relevant": why_relevant or annotation["summary"],
            }
        )
    return unique_by_annotation(refs)[:8]


def unique_by_annotation(rows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    seen: Set[str] = set()
    result: List[Dict[str, Any]] = []
    for row in rows:
        annotation_id = str(row.get("annotation_id") or "")
        if annotation_id and annotation_id not in seen:
            seen.add(annotation_id)
            result.append(row)
    return result


def build_preview(report: Dict[str, Any], wrapup: Dict[str, Any], annotation_lookup: Dict[str, Dict[str, Any]]) -> str:
    lines: List[str] = []
    lines.append(f"# Spot Wrap-Up Preview: {report['dataset_id']}")
    lines.append("")
    lines.append(f"- `window_start`: {report['start_at']}")
    lines.append(f"- `window_end`: {report['end_at']}")
    lines.append(f"- `section_count`: {report['section_count']}")
    lines.append(f"- `decision_count`: {report['decision_count']}")
    lines.append(f"- `open_task_count`: {report['open_task_count']}")
    lines.append(f"- `knowledge_ref_count`: {report['knowledge_ref_count']}")
    lines.append(f"- `llm_provider`: {report['llm_provider']}")
    lines.append(f"- `llm_model`: {report['llm_model']}")
    lines.append("")
    lines.append(f"## {wrapup['headline']}")
    lines.append("")
    lines.append(wrapup["abstract"])
    lines.append("")
    lines.append("### Current Status")
    lines.append("")
    lines.append(wrapup["status_summary"])
    lines.append("")
    lines.append("## Main Threads")
    lines.append("")
    for section in wrapup["narrative_sections"]:
        lines.append(f"### {section['title']}")
        lines.append("")
        lines.append(section["summary"])
        lines.append("")
        lines.append(f"- topics: {', '.join(section['source_topic_ids'])}")
        lines.append(f"- annotations: {', '.join(section['annotation_ids'])}")
        lines.append("")

    lines.append("## Decisions / Stable Conclusions")
    lines.append("")
    if not wrapup["decisions"]:
        lines.append("- (none)")
        lines.append("")
    for decision in wrapup["decisions"]:
        lines.append(f"- {decision['summary']}")
        lines.append(f"  annotations: {', '.join(decision['annotation_ids'])}")
    lines.append("")

    lines.append("## Open Tasks")
    lines.append("")
    if not wrapup["open_tasks"]:
        lines.append("- (none)")
        lines.append("")
    for task in wrapup["open_tasks"]:
        lines.append(f"- [{task['priority']}] {task['title']}")
        lines.append(f"  kind: {task['task_kind']}")
        lines.append(f"  why_now: {task['why_now']}")
        lines.append(f"  annotation: {task['annotation_id']}")
    lines.append("")

    lines.append("## Knowledge References")
    lines.append("")
    if not wrapup["knowledge_refs"]:
        lines.append("- (none)")
        lines.append("")
    for ref in wrapup["knowledge_refs"]:
        lines.append(f"- [{ref['category']}] {ref['title']}")
        lines.append(f"  why_relevant: {ref['why_relevant']}")
        lines.append(f"  annotation: {ref['annotation_id']}")
    lines.append("")

    lines.append("## Annotation Index")
    lines.append("")
    for annotation_id in sorted(annotation_lookup):
        annotation = annotation_lookup[annotation_id]
        if annotation["item_type"] == "task":
            detail = f"priority={annotation.get('priority')} kind={annotation.get('task_kind')}"
        else:
            detail = f"category={annotation.get('category')}"
        lines.append(
            f"- `{annotation_id}` {annotation['item_type']} {annotation['title']} "
            f"({detail}; canonical={annotation.get('canonical_id')})"
        )
    lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def main() -> None:
    args = parse_args()
    dataset_dir = Path(args.dataset_dir).expanduser().resolve()
    repo_root = dataset_dir.parents[3]
    backend_dir = repo_root / "backend"
    load_env_file(backend_dir / ".env")

    sys.path.insert(0, str(backend_dir))
    from services.llm_providers import LLMFactory, get_current_llm  # type: ignore

    manifest = load_json(dataset_dir / "00_source_window.json")
    topics = load_jsonl(dataset_dir / "03_topics.jsonl")
    distilled_items = load_jsonl(dataset_dir / "05_distilled_items.jsonl")
    canonical_items = load_jsonl(dataset_dir / "06_canonical_items.jsonl")

    if args.provider or args.model:
        provider = args.provider or os.getenv("LLM_DEFAULT_PROVIDER", "openai")
        llm = LLMFactory.create(provider, args.model)
    else:
        llm = get_current_llm()

    annotation_catalog = build_annotation_catalog(canonical_items)
    annotation_lookup = {row["annotation_id"]: row for row in annotation_catalog}
    prompt = build_prompt(manifest, topics, distilled_items, annotation_catalog)
    raw_output = llm.generate(prompt)
    payload = extract_json(raw_output) or {}

    valid_topic_ids = set(build_topic_lookup(topics))
    valid_annotation_ids = set(annotation_lookup)

    headline = normalize_string(payload.get("headline")) or f"{manifest.get('dataset_id')} wrap-up"
    abstract = normalize_string(payload.get("abstract"))
    status_summary = normalize_string(payload.get("status_summary"))
    narrative_sections = normalize_sections(
        payload.get("narrative_sections"),
        valid_topic_ids,
        valid_annotation_ids,
    )
    decisions = normalize_decisions(payload.get("decisions"), valid_annotation_ids)
    open_tasks = normalize_open_tasks(payload.get("open_tasks"), annotation_lookup)
    knowledge_refs = normalize_knowledge_refs(payload.get("knowledge_refs"), annotation_lookup)

    if not abstract:
        abstract = "この時間窓の会話から、主要な論点、実行タスク、再利用知識を読み物として整理した。"
    if not status_summary:
        status_summary = "会話ログから素材は十分に取れており、次は読みやすい wrap-up と自然言語アクセスの層を重ねる段階にある。"

    wrapup = {
        "dataset_id": manifest.get("dataset_id"),
        "start_at": resolve_window_bound(manifest, "start_at", "window_start"),
        "end_at": resolve_window_bound(manifest, "end_at", "window_end"),
        "headline": headline,
        "abstract": abstract,
        "status_summary": status_summary,
        "narrative_sections": narrative_sections,
        "decisions": decisions,
        "open_tasks": open_tasks,
        "knowledge_refs": knowledge_refs,
        "annotations": annotation_catalog,
    }

    provider_name, model_name = llm.model_name.split("/", 1) if "/" in llm.model_name else ("unknown", llm.model_name)
    report = {
        "dataset_id": manifest.get("dataset_id"),
        "generated_at": datetime.now().astimezone().isoformat(),
        "start_at": resolve_window_bound(manifest, "start_at", "window_start"),
        "end_at": resolve_window_bound(manifest, "end_at", "window_end"),
        "annotation_count": len(annotation_catalog),
        "section_count": len(narrative_sections),
        "decision_count": len(decisions),
        "open_task_count": len(open_tasks),
        "knowledge_ref_count": len(knowledge_refs),
        "llm_provider": provider_name,
        "llm_model": model_name,
    }

    write_json(dataset_dir / "07_spot_wrapup.json", wrapup)
    write_json(dataset_dir / "07_spot_wrapup_report.json", report)
    write_text(dataset_dir / "07_spot_wrapup_preview.md", build_preview(report, wrapup, annotation_lookup))

    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
