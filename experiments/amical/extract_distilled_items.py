#!/usr/bin/env python3
"""Extract first-pass task and knowledge items from topic windows."""

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
        help="Artifact directory containing 03_topics.jsonl",
    )
    parser.add_argument(
        "--provider",
        default=None,
        help="Override LLM provider",
    )
    parser.add_argument(
        "--model",
        default=None,
        help="Override LLM model",
    )
    parser.add_argument(
        "--max-topics",
        type=int,
        default=0,
        help="Only process the first N topics when non-zero",
    )
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


def build_prompt(topic: Dict[str, Any]) -> str:
    utterance_lines: List[str] = []
    for utterance_id, text in zip(topic.get("utterance_ids", []), topic.get("texts", [])):
        utterance_lines.append(f"- {utterance_id}: {text}")
    transcript = "\n".join(utterance_lines) or "- (none)"

    return f"""You are extracting useful work items from a topic in an ambient conversation log.

Return two kinds of items only:
- task: an action that someone should do, confirm, create, update, investigate, or decide
- knowledge: a durable insight, rule, preference, design principle, operating policy, or architecture insight worth retaining

Rules:
- Use ONLY the content provided in the topic
- Do not invent facts or infer hidden business context
- Prefer fewer, higher-quality items over many weak ones
- Drop filler, acknowledgements, and low-information fragments
- If an item is weak or incomplete, do not include it
- A task must describe a concrete action
- A knowledge item should be reusable beyond this single utterance
- Evidence must reference only utterance ids from the topic
- Output Japanese text for summaries and rationales

Output ONLY JSON:
{{
  "items": [
    {{
      "item_type": "task|knowledge",
      "summary": "short Japanese summary",
      "rationale": "why this item matters",
      "confidence": "high|medium|low",
      "evidence_utterance_ids": ["utt-000001"]
    }}
  ]
}}

# Topic
topic_id: {topic.get("topic_id")}
start_at: {topic.get("start_at")}
end_at: {topic.get("end_at")}
preview_text: {topic.get("preview_text") or "(none)"}

# Utterances
{transcript}
"""


def normalize_item(topic_id: str, raw_item: Dict[str, Any], index: int) -> Optional[Dict[str, Any]]:
    item_type = str(raw_item.get("item_type") or "").strip().lower()
    if item_type not in {"task", "knowledge"}:
        return None

    summary = " ".join(str(raw_item.get("summary") or "").split()).strip()
    rationale = " ".join(str(raw_item.get("rationale") or "").split()).strip()
    confidence = str(raw_item.get("confidence") or "").strip().lower()
    if confidence not in {"high", "medium", "low"}:
        confidence = "medium"
    evidence = raw_item.get("evidence_utterance_ids")
    if not isinstance(evidence, list):
        evidence = []
    evidence_ids = [str(value).strip() for value in evidence if str(value).strip()]

    if not summary:
        return None

    return {
        "item_id": f"{topic_id}__item_{index:03d}",
        "topic_id": topic_id,
        "item_type": item_type,
        "summary": summary,
        "rationale": rationale or None,
        "confidence": confidence,
        "evidence_utterance_ids": evidence_ids,
    }


def build_preview(report: Dict[str, Any], items_by_topic: Dict[str, List[Dict[str, Any]]]) -> str:
    lines: List[str] = []
    lines.append(f"# Distilled Items Preview: {report['dataset_id']}")
    lines.append("")
    lines.append(f"- `topic_count`: {report['topic_count']}")
    lines.append(f"- `item_count`: {report['item_count']}")
    lines.append(f"- `task_count`: {report['task_count']}")
    lines.append(f"- `knowledge_count`: {report['knowledge_count']}")
    lines.append(f"- `llm_provider`: {report['llm_provider']}")
    lines.append(f"- `llm_model`: {report['llm_model']}")
    lines.append("")

    for topic_id, items in items_by_topic.items():
        lines.append(f"## {topic_id}")
        lines.append("")
        if not items:
            lines.append("- (no items)")
            lines.append("")
            continue
        for item in items:
            lines.append(f"- `{item['item_type']}` {item['summary']}")
            if item.get("rationale"):
                lines.append(f"  rationale: {item['rationale']}")
            if item.get("evidence_utterance_ids"):
                lines.append(f"  evidence: {', '.join(item['evidence_utterance_ids'])}")
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
    topics = load_jsonl(dataset_dir / "03_topics.jsonl")
    if args.max_topics > 0:
        topics = topics[: args.max_topics]

    if args.provider or args.model:
        provider = args.provider or os.getenv("LLM_DEFAULT_PROVIDER", "openai")
        llm = LLMFactory.create(provider, args.model)
    else:
        llm = get_current_llm()

    all_items: List[Dict[str, Any]] = []
    items_by_topic: Dict[str, List[Dict[str, Any]]] = {}
    topic_reports: List[Dict[str, Any]] = []

    for topic in topics:
        topic_id = topic["topic_id"]
        print(f"[distill] topic {topic_id}...", flush=True)
        prompt = build_prompt(topic)
        raw_output = llm.generate(prompt)
        payload = extract_json(raw_output) or {}
        raw_items = payload.get("items")
        if not isinstance(raw_items, list):
            raw_items = []

        normalized_items: List[Dict[str, Any]] = []
        for index, raw_item in enumerate(raw_items, start=1):
            if not isinstance(raw_item, dict):
                continue
            item = normalize_item(topic_id, raw_item, index)
            if item is not None:
                normalized_items.append(item)
                all_items.append(item)

        items_by_topic[topic_id] = normalized_items
        topic_reports.append(
            {
                "topic_id": topic_id,
                "item_count": len(normalized_items),
                "task_count": sum(1 for item in normalized_items if item["item_type"] == "task"),
                "knowledge_count": sum(1 for item in normalized_items if item["item_type"] == "knowledge"),
            }
        )
        print(
            f"[distill] topic {topic_id} done: {len(normalized_items)} items",
            flush=True,
        )

    provider_name, model_name = llm.model_name.split("/", 1) if "/" in llm.model_name else ("unknown", llm.model_name)
    report = {
        "dataset_id": source_manifest["dataset_id"],
        "generated_at": datetime.now().astimezone().isoformat(),
        "topic_count": len(topics),
        "item_count": len(all_items),
        "task_count": sum(1 for item in all_items if item["item_type"] == "task"),
        "knowledge_count": sum(1 for item in all_items if item["item_type"] == "knowledge"),
        "llm_provider": provider_name,
        "llm_model": model_name,
        "topic_reports": topic_reports,
    }

    write_jsonl(dataset_dir / "05_distilled_items.jsonl", all_items)
    write_json(dataset_dir / "05_distilled_report.json", report)
    write_text(dataset_dir / "05_distilled_preview.md", build_preview(report, items_by_topic))

    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
