#!/usr/bin/env python3
"""Create first-pass topics from a normalized Amical raw corpus window."""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Dict, Iterable, List


@dataclass
class TopicBucket:
    topic_id: str
    start_at: str
    end_at: str
    utterance_ids: List[str] = field(default_factory=list)
    utterances: List[Dict[str, Any]] = field(default_factory=list)
    boundary_reason: str = "window_end"
    gap_from_previous_seconds: float = 0.0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--dataset-dir",
        required=True,
        help="Artifact directory containing 02_raw_corpus.jsonl",
    )
    parser.add_argument(
        "--idle-gap-seconds",
        type=int,
        default=30,
        help="Start a new topic when the gap between utterances reaches this threshold",
    )
    return parser.parse_args()


def load_json(path: Path) -> Dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def load_jsonl(path: Path) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
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


def parse_ts(value: str) -> datetime:
    return datetime.fromisoformat(value)


def summarize_topic_text(utterances: List[Dict[str, Any]], limit: int = 160) -> str:
    text = " ".join((item.get("text") or "").strip() for item in utterances if (item.get("text") or "").strip())
    text = " ".join(text.split())
    if len(text) <= limit:
        return text
    return text[: limit - 1].rstrip() + "…"


def bucket_topics(rows: List[Dict[str, Any]], idle_gap_seconds: int) -> List[TopicBucket]:
    if not rows:
        return []

    topics: List[TopicBucket] = []
    current: TopicBucket | None = None
    previous_ts: datetime | None = None
    threshold = timedelta(seconds=idle_gap_seconds)

    for row in rows:
        current_ts = parse_ts(row["timestamp"])
        start_new = current is None
        gap_seconds = 0.0
        if previous_ts is not None:
            gap_seconds = (current_ts - previous_ts).total_seconds()
            if current_ts - previous_ts >= threshold:
                if current is not None:
                    current.boundary_reason = "idle_gap"
                start_new = True

        if start_new:
            current = TopicBucket(
                topic_id=f"topic-{len(topics) + 1:03d}",
                start_at=row["timestamp"],
                end_at=row["timestamp"],
                boundary_reason="window_end",
                gap_from_previous_seconds=gap_seconds,
            )
            topics.append(current)

        current.utterance_ids.append(row["utterance_id"])
        current.utterances.append(row)
        current.end_at = row["timestamp"]
        previous_ts = current_ts

    return topics


def build_topic_rows(topics: List[TopicBucket]) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    for topic in topics:
        texts = [item["text"] for item in topic.utterances]
        rows.append(
            {
                "topic_id": topic.topic_id,
                "start_at": topic.start_at,
                "end_at": topic.end_at,
                "utterance_count": len(topic.utterances),
                "utterance_ids": topic.utterance_ids,
                "gap_from_previous_seconds": topic.gap_from_previous_seconds,
                "boundary_reason": topic.boundary_reason,
                "preview_text": summarize_topic_text(topic.utterances, limit=220),
                "texts": texts,
            }
        )
    return rows


def build_preview(report: Dict[str, Any], topic_rows: List[Dict[str, Any]]) -> str:
    lines: List[str] = []
    lines.append(f"# Topics Preview: {report['dataset_id']}")
    lines.append("")
    lines.append(f"- `idle_gap_seconds`: {report['idle_gap_seconds']}")
    lines.append(f"- `topic_count`: {report['topic_count']}")
    lines.append(f"- `utterance_count`: {report['utterance_count']}")
    lines.append("")

    for row in topic_rows:
        lines.append(f"## {row['topic_id']}")
        lines.append("")
        lines.append(f"- `start_at`: {row['start_at']}")
        lines.append(f"- `end_at`: {row['end_at']}")
        lines.append(f"- `utterance_count`: {row['utterance_count']}")
        lines.append(f"- `gap_from_previous_seconds`: {row['gap_from_previous_seconds']}")
        lines.append(f"- `boundary_reason`: {row['boundary_reason']}")
        lines.append(f"- `preview_text`: {row['preview_text']}")
        lines.append("")
        for utterance in row["texts"]:
            lines.append(f"- {utterance}")
        lines.append("")

    return "\n".join(lines).rstrip() + "\n"


def main() -> None:
    args = parse_args()
    dataset_dir = Path(args.dataset_dir).expanduser().resolve()
    source_manifest = load_json(dataset_dir / "00_source_window.json")
    raw_corpus = load_jsonl(dataset_dir / "02_raw_corpus.jsonl")

    topics = bucket_topics(raw_corpus, idle_gap_seconds=args.idle_gap_seconds)
    topic_rows = build_topic_rows(topics)

    report = {
        "dataset_id": source_manifest["dataset_id"],
        "source_window": {
            "start": source_manifest["window_start"],
            "end": source_manifest["window_end"],
        },
        "idle_gap_seconds": args.idle_gap_seconds,
        "topic_count": len(topic_rows),
        "utterance_count": len(raw_corpus),
    }

    write_jsonl(dataset_dir / "03_topics.jsonl", topic_rows)
    write_json(dataset_dir / "03_topic_report.json", report)
    write_text(dataset_dir / "03_topics_preview.md", build_preview(report, topic_rows))

    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
