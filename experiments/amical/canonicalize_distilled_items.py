#!/usr/bin/env python3
"""Merge distilled items into canonical tasks and categorized knowledge."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--dataset-dir",
        required=True,
        help="Artifact directory containing 05_distilled_items.jsonl",
    )
    parser.add_argument("--provider", default=None, help="Override LLM provider")
    parser.add_argument("--model", default=None, help="Override LLM model")
    return parser.parse_args()


def load_env_file(path: Path) -> None:
    if not path.exists():
        return
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
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


def write_jsonl(path: Path, rows: Iterable[Dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")


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


def build_prompt(items: List[Dict[str, Any]]) -> str:
    lines: List[str] = []
    for item in items:
        lines.append(
            f"- id={item['item_id']} type={item['item_type']} topic={item['topic_id']} "
            f"summary={item['summary']} rationale={item.get('rationale') or '(none)'} "
            f"confidence={item.get('confidence') or 'medium'} "
            f"evidence={','.join(item.get('evidence_utterance_ids') or []) or '(none)'}"
        )
    joined = "\n".join(lines) or "- (none)"

    return f"""You are consolidating distilled conversation items into a current-window canonical layer.

Your goal:
- Merge duplicate or near-duplicate items
- Keep separate items when they represent distinct actions or distinct reusable knowledge
- Produce two outputs:
  1. canonical tasks
  2. categorized canonical knowledge

Rules:
- Use ONLY the provided items
- Do not invent missing work or missing knowledge
- Merge items when they clearly refer to the same underlying action or same reusable insight
- Keep canonical outputs concise and reusable
- Tasks should read like a clear actionable to-do
- Knowledge should read like a reusable insight or principle
- Every canonical output must include source_item_ids from the provided list
- For knowledge, assign a short category label in Japanese
- Categories should be practical, such as UI, 設計, パイプライン, 運用, コスト, etc.
- Output Japanese text

Output ONLY JSON:
{{
  "canonical_tasks": [
    {{
      "title": "string",
      "summary": "string",
      "task_kind": "implementation|documentation|research|decision|other",
      "priority": "high|medium|low",
      "source_item_ids": ["topic-001__item_001"]
    }}
  ],
  "canonical_knowledge": [
    {{
      "category": "string",
      "title": "string",
      "summary": "string",
      "source_item_ids": ["topic-001__item_005"]
    }}
  ]
}}

# Distilled Items
{joined}
"""


def normalize_string(value: Any) -> str:
    return " ".join(str(value or "").split()).strip()


def index_items(items: List[Dict[str, Any]]) -> Dict[str, Dict[str, Any]]:
    return {item["item_id"]: item for item in items}


def collect_sources(
    source_item_ids: List[str],
    item_lookup: Dict[str, Dict[str, Any]],
) -> Dict[str, List[str]]:
    topic_ids: List[str] = []
    utterance_ids: List[str] = []
    for item_id in source_item_ids:
        item = item_lookup.get(item_id)
        if not item:
            continue
        topic_id = str(item.get("topic_id") or "").strip()
        if topic_id and topic_id not in topic_ids:
            topic_ids.append(topic_id)
        for utterance_id in item.get("evidence_utterance_ids") or []:
            utterance_id = str(utterance_id).strip()
            if utterance_id and utterance_id not in utterance_ids:
                utterance_ids.append(utterance_id)
    return {
        "source_topic_ids": topic_ids,
        "evidence_utterance_ids": utterance_ids,
    }


def normalize_task(
    raw: Dict[str, Any],
    index: int,
    item_lookup: Dict[str, Dict[str, Any]],
) -> Optional[Dict[str, Any]]:
    title = normalize_string(raw.get("title"))
    summary = normalize_string(raw.get("summary"))
    if not title:
        return None
    source_item_ids = raw.get("source_item_ids")
    if not isinstance(source_item_ids, list):
        source_item_ids = []
    source_item_ids = [str(value).strip() for value in source_item_ids if str(value).strip() in item_lookup]
    if not source_item_ids:
        return None
    task_kind = normalize_string(raw.get("task_kind")).lower() or "other"
    if task_kind not in {"implementation", "documentation", "research", "decision", "other"}:
        task_kind = "other"
    priority = normalize_string(raw.get("priority")).lower() or "medium"
    if priority not in {"high", "medium", "low"}:
        priority = "medium"
    sources = collect_sources(source_item_ids, item_lookup)
    return {
        "canonical_id": f"canon-task-{index:03d}",
        "item_type": "task",
        "title": title,
        "summary": summary or title,
        "category": None,
        "task_kind": task_kind,
        "priority": priority,
        "source_item_ids": source_item_ids,
        "source_topic_ids": sources["source_topic_ids"],
        "evidence_utterance_ids": sources["evidence_utterance_ids"],
    }


def normalize_knowledge(
    raw: Dict[str, Any],
    index: int,
    item_lookup: Dict[str, Dict[str, Any]],
) -> Optional[Dict[str, Any]]:
    title = normalize_string(raw.get("title"))
    summary = normalize_string(raw.get("summary"))
    category = normalize_string(raw.get("category"))
    if not title or not category:
        return None
    source_item_ids = raw.get("source_item_ids")
    if not isinstance(source_item_ids, list):
        source_item_ids = []
    source_item_ids = [str(value).strip() for value in source_item_ids if str(value).strip() in item_lookup]
    if not source_item_ids:
        return None
    sources = collect_sources(source_item_ids, item_lookup)
    return {
        "canonical_id": f"canon-knowledge-{index:03d}",
        "item_type": "knowledge",
        "title": title,
        "summary": summary or title,
        "category": category,
        "task_kind": None,
        "priority": None,
        "source_item_ids": source_item_ids,
        "source_topic_ids": sources["source_topic_ids"],
        "evidence_utterance_ids": sources["evidence_utterance_ids"],
    }


def build_preview(report: Dict[str, Any], tasks: List[Dict[str, Any]], knowledge: List[Dict[str, Any]]) -> str:
    lines: List[str] = []
    lines.append(f"# Canonical Preview: {report['dataset_id']}")
    lines.append("")
    lines.append(f"- `canonical_task_count`: {report['canonical_task_count']}")
    lines.append(f"- `canonical_knowledge_count`: {report['canonical_knowledge_count']}")
    lines.append(f"- `knowledge_categories`: {', '.join(report['knowledge_categories']) or '(none)'}")
    lines.append(f"- `llm_provider`: {report['llm_provider']}")
    lines.append(f"- `llm_model`: {report['llm_model']}")
    lines.append("")
    lines.append("## Task List")
    lines.append("")
    if not tasks:
        lines.append("- (none)")
        lines.append("")
    for task in tasks:
        lines.append(f"- [{task['priority']}] {task['title']}")
        lines.append(f"  kind: {task['task_kind']}")
        lines.append(f"  summary: {task['summary']}")
        lines.append(f"  sources: {', '.join(task['source_item_ids'])}")
    lines.append("")
    lines.append("## Knowledge by Category")
    lines.append("")
    if not knowledge:
        lines.append("- (none)")
        lines.append("")
    categories = sorted({item["category"] for item in knowledge if item.get("category")})
    for category in categories:
        lines.append(f"### {category}")
        lines.append("")
        category_items = [item for item in knowledge if item.get("category") == category]
        for item in category_items:
            lines.append(f"- {item['title']}")
            lines.append(f"  summary: {item['summary']}")
            lines.append(f"  sources: {', '.join(item['source_item_ids'])}")
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

    source_manifest = load_json(dataset_dir / "00_source_window.json")
    distilled_items = load_jsonl(dataset_dir / "05_distilled_items.jsonl")
    item_lookup = index_items(distilled_items)

    if args.provider or args.model:
        provider = args.provider or os.getenv("LLM_DEFAULT_PROVIDER", "openai")
        llm = LLMFactory.create(provider, args.model)
    else:
        llm = get_current_llm()

    prompt = build_prompt(distilled_items)
    raw_output = llm.generate(prompt)
    payload = extract_json(raw_output) or {}

    raw_tasks = payload.get("canonical_tasks")
    raw_knowledge = payload.get("canonical_knowledge")
    if not isinstance(raw_tasks, list):
        raw_tasks = []
    if not isinstance(raw_knowledge, list):
        raw_knowledge = []

    canonical_tasks: List[Dict[str, Any]] = []
    for index, raw in enumerate(raw_tasks, start=1):
        if not isinstance(raw, dict):
            continue
        item = normalize_task(raw, index, item_lookup)
        if item is not None:
            canonical_tasks.append(item)

    canonical_knowledge: List[Dict[str, Any]] = []
    for index, raw in enumerate(raw_knowledge, start=1):
        if not isinstance(raw, dict):
            continue
        item = normalize_knowledge(raw, index, item_lookup)
        if item is not None:
            canonical_knowledge.append(item)

    canonical_items = canonical_tasks + canonical_knowledge
    provider_name, model_name = llm.model_name.split("/", 1) if "/" in llm.model_name else ("unknown", llm.model_name)
    categories = sorted({item["category"] for item in canonical_knowledge if item.get("category")})

    report = {
        "dataset_id": source_manifest["dataset_id"],
        "generated_at": datetime.now().astimezone().isoformat(),
        "input_distilled_item_count": len(distilled_items),
        "canonical_item_count": len(canonical_items),
        "canonical_task_count": len(canonical_tasks),
        "canonical_knowledge_count": len(canonical_knowledge),
        "knowledge_categories": categories,
        "llm_provider": provider_name,
        "llm_model": model_name,
    }

    write_jsonl(dataset_dir / "06_canonical_items.jsonl", canonical_items)
    write_json(dataset_dir / "06_canonical_report.json", report)
    write_text(dataset_dir / "06_canonical_preview.md", build_preview(report, canonical_tasks, canonical_knowledge))

    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
