#!/usr/bin/env python3
"""Extract a normalized raw corpus window from Amical application logs."""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional
from zoneinfo import ZoneInfo


HEADER_RE = re.compile(
    r"^\[(?P<timestamp>[^\]]+)\]\s+\[(?P<level>[^\]]+)\]\s+\[\s+\((?P<channel>[^)]*)\)\]\s+(?P<message>.*)$"
)
TRANSCRIPTION_RE = re.compile(r"transcription:\s*'(.*?)'", re.DOTALL)
TEXT_LENGTH_RE = re.compile(r"textLength:\s*(\d+)")
LANGUAGE_RE = re.compile(r"language:\s*'([^']*)'")
DURATION_RE = re.compile(r"duration:\s*([0-9.]+)")
SESSION_ID_RE = re.compile(r"sessionId:\s*'([^']+)'")
AUDIO_FILE_RE = re.compile(r"audioFilePath:\s*'([^']+)'")


@dataclass
class LogEvent:
    timestamp: datetime
    level: str
    channel: str
    message: str
    body: str
    line_start: int
    line_end: int

    @property
    def full_text(self) -> str:
        return self.message if not self.body else f"{self.message}\n{self.body}"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="Path to Amical log file")
    parser.add_argument("--start", required=True, help="Window start, e.g. 2026-03-27T15:11:00")
    parser.add_argument("--end", help="Window end, e.g. 2026-03-27T16:11:00")
    parser.add_argument("--minutes", type=int, default=60, help="Window size when --end is omitted")
    parser.add_argument(
        "--timezone",
        default="Asia/Tokyo",
        help="Timezone to attach to naive log timestamps",
    )
    parser.add_argument(
        "--output-root",
        default="experiments/amical/artifacts",
        help="Directory where artifacts will be generated",
    )
    parser.add_argument(
        "--exact-duplicate-seconds",
        type=int,
        default=10,
        help="Drop exact duplicate texts within this many seconds",
    )
    parser.add_argument(
        "--supersede-seconds",
        type=int,
        default=20,
        help="Drop a short candidate if a longer extension appears within this many seconds",
    )
    return parser.parse_args()


def parse_local_timestamp(value: str, timezone_name: str) -> datetime:
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is not None:
        return parsed
    return parsed.replace(tzinfo=ZoneInfo(timezone_name))


def load_events(path: Path, timezone_name: str) -> List[LogEvent]:
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    events: List[LogEvent] = []
    current_header: Optional[Dict[str, Any]] = None
    body_lines: List[str] = []

    for index, line in enumerate(lines, start=1):
        match = HEADER_RE.match(line)
        if match:
            if current_header is not None:
                events.append(
                    LogEvent(
                        timestamp=parse_local_timestamp(current_header["timestamp"], timezone_name),
                        level=current_header["level"],
                        channel=current_header["channel"],
                        message=current_header["message"],
                        body="\n".join(body_lines).strip(),
                        line_start=current_header["line_start"],
                        line_end=index - 1,
                    )
                )
            current_header = {
                "timestamp": match.group("timestamp"),
                "level": match.group("level"),
                "channel": match.group("channel").strip(),
                "message": match.group("message").rstrip(),
                "line_start": index,
            }
            body_lines = []
        else:
            body_lines.append(line.rstrip())

    if current_header is not None:
        events.append(
            LogEvent(
                timestamp=parse_local_timestamp(current_header["timestamp"], timezone_name),
                level=current_header["level"],
                channel=current_header["channel"],
                message=current_header["message"],
                body="\n".join(body_lines).strip(),
                line_start=current_header["line_start"],
                line_end=len(lines),
            )
        )

    return events


def normalize_text(text: str) -> str:
    value = " ".join(text.replace("\n", " ").split()).strip()
    value = re.sub(r"\s+([。．、,!?！？])", r"\1", value)
    return value


def extract_inline(pattern: re.Pattern[str], text: str) -> Optional[str]:
    match = pattern.search(text)
    if not match:
        return None
    return match.group(1)


def build_candidates(events: List[LogEvent], input_name: str) -> List[Dict[str, Any]]:
    candidates: List[Dict[str, Any]] = []

    for index, event in enumerate(events):
        if event.channel != "transcription":
            continue
        if "Cloud transcription successful" not in event.message:
            continue

        full_text = event.full_text
        transcription = extract_inline(TRANSCRIPTION_RE, full_text) or ""
        transcription = normalize_text(transcription)

        candidate: Dict[str, Any] = {
            "candidate_id": f"cand-{index + 1:06d}",
            "timestamp": event.timestamp.isoformat(),
            "text": transcription,
            "source": "amical",
            "kind": "utterance_candidate",
            "language": extract_inline(LANGUAGE_RE, full_text),
            "duration_seconds": None,
            "text_length": None,
            "session_id": None,
            "audio_file_path": None,
            "raw_ref": f"{input_name}:{event.line_start}",
            "line_start": event.line_start,
            "line_end": event.line_end,
        }

        duration = extract_inline(DURATION_RE, full_text)
        if duration is not None:
            candidate["duration_seconds"] = float(duration)

        text_length = extract_inline(TEXT_LENGTH_RE, full_text)
        if text_length is not None:
            candidate["text_length"] = int(text_length)

        for follow in events[index + 1 : index + 8]:
            if follow.timestamp - event.timestamp > timedelta(seconds=3):
                break
            follow_text = follow.full_text
            if candidate["session_id"] is None:
                candidate["session_id"] = extract_inline(SESSION_ID_RE, follow_text)
            if candidate["audio_file_path"] is None:
                candidate["audio_file_path"] = extract_inline(AUDIO_FILE_RE, follow_text)
            if candidate["session_id"] and candidate["audio_file_path"]:
                break

        candidates.append(candidate)

    return candidates


def filter_window(
    items: Iterable[Dict[str, Any]],
    start: datetime,
    end: datetime,
) -> List[Dict[str, Any]]:
    results: List[Dict[str, Any]] = []
    for item in items:
        ts = datetime.fromisoformat(item["timestamp"])
        if start <= ts < end:
            results.append(item)
    return results


def should_supersede(current: Dict[str, Any], upcoming: Dict[str, Any], seconds: int) -> bool:
    current_text = current["text"]
    upcoming_text = upcoming["text"]
    if not current_text or not upcoming_text:
        return False
    current_ts = datetime.fromisoformat(current["timestamp"])
    upcoming_ts = datetime.fromisoformat(upcoming["timestamp"])
    if upcoming_ts - current_ts > timedelta(seconds=seconds):
        return False
    if len(upcoming_text) <= len(current_text) + 8:
        return False
    return upcoming_text.startswith(current_text)


def clean_candidates(
    candidates: List[Dict[str, Any]],
    exact_duplicate_seconds: int,
    supersede_seconds: int,
) -> tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    cleaned: List[Dict[str, Any]] = []
    dropped: List[Dict[str, Any]] = []

    for index, candidate in enumerate(candidates):
        text = candidate["text"]
        if not text:
            dropped.append(
                {
                    "candidate_id": candidate["candidate_id"],
                    "raw_ref": candidate["raw_ref"],
                    "reason": "empty_text",
                }
            )
            continue

        superseded_by: Optional[str] = None
        for upcoming in candidates[index + 1 : index + 4]:
            if should_supersede(candidate, upcoming, supersede_seconds):
                superseded_by = upcoming["candidate_id"]
                break
        if superseded_by is not None:
            dropped.append(
                {
                    "candidate_id": candidate["candidate_id"],
                    "raw_ref": candidate["raw_ref"],
                    "reason": "superseded_by_longer_candidate",
                    "superseded_by": superseded_by,
                }
            )
            continue

        if cleaned:
            previous = cleaned[-1]
            previous_ts = datetime.fromisoformat(previous["timestamp"])
            current_ts = datetime.fromisoformat(candidate["timestamp"])
            if (
                previous["text"] == text
                and current_ts - previous_ts <= timedelta(seconds=exact_duplicate_seconds)
            ):
                dropped.append(
                    {
                        "candidate_id": candidate["candidate_id"],
                        "raw_ref": candidate["raw_ref"],
                        "reason": "exact_duplicate_within_window",
                        "duplicate_of": previous["candidate_id"],
                    }
                )
                continue

        cleaned.append(
            {
                "utterance_id": f"utt-{len(cleaned) + 1:06d}",
                "timestamp": candidate["timestamp"],
                "text": text,
                "source": candidate["source"],
                "kind": "utterance",
                "language": candidate["language"],
                "duration_seconds": candidate["duration_seconds"],
                "session_id": candidate["session_id"],
                "audio_file_path": candidate["audio_file_path"],
                "raw_ref": candidate["raw_ref"],
                "candidate_id": candidate["candidate_id"],
            }
        )

    return cleaned, dropped


def sanitize_slug(value: str) -> str:
    return re.sub(r"[^a-zA-Z0-9._-]+", "_", value).strip("_")


def write_json(path: Path, payload: Any) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: Iterable[Dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def main() -> None:
    args = parse_args()
    input_path = Path(args.input).expanduser().resolve()
    output_root = Path(args.output_root)
    if not output_root.is_absolute():
        output_root = (Path.cwd() / output_root).resolve()

    start = parse_local_timestamp(args.start, args.timezone)
    end = parse_local_timestamp(args.end, args.timezone) if args.end else start + timedelta(minutes=args.minutes)

    events = load_events(input_path, args.timezone)
    candidates = build_candidates(events, input_path.name)
    window_candidates = filter_window(candidates, start, end)
    raw_corpus, dropped = clean_candidates(
        window_candidates,
        exact_duplicate_seconds=args.exact_duplicate_seconds,
        supersede_seconds=args.supersede_seconds,
    )

    dataset_id = sanitize_slug(
        f"{input_path.stem}__{start.strftime('%Y%m%dT%H%M%S')}__{end.strftime('%Y%m%dT%H%M%S')}"
    )
    output_dir = output_root / dataset_id
    output_dir.mkdir(parents=True, exist_ok=True)

    source_manifest = {
        "dataset_id": dataset_id,
        "input_path": str(input_path),
        "window_start": start.isoformat(),
        "window_end": end.isoformat(),
        "timezone": args.timezone,
        "total_log_events": len(events),
        "candidate_count": len(window_candidates),
        "raw_corpus_count": len(raw_corpus),
        "dropped_count": len(dropped),
        "stages": {
            "00_source_window": "Metadata for the source file and selected time window",
            "01_candidate_utterances": "Transcription candidates extracted from the log window",
            "02_raw_corpus": "Normalized utterance stream for downstream topicing/distillation",
            "02_cleaning_report": "Dropped candidate records and reasons",
        },
    }

    cleaning_report = {
        "dataset_id": dataset_id,
        "summary": {
            "candidate_count": len(window_candidates),
            "raw_corpus_count": len(raw_corpus),
            "dropped_count": len(dropped),
        },
        "dropped": dropped,
    }

    write_json(output_dir / "00_source_window.json", source_manifest)
    write_jsonl(output_dir / "01_candidate_utterances.jsonl", window_candidates)
    write_jsonl(output_dir / "02_raw_corpus.jsonl", raw_corpus)
    write_json(output_dir / "02_cleaning_report.json", cleaning_report)

    print(json.dumps(source_manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
