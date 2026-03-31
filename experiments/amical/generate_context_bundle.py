#!/usr/bin/env python3
"""Emit the next-day context bundle from an active state snapshot."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from stateful_daily_utils import build_context_bundle, load_json, recent_rollup_context, resolve_output_dir, write_json


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--date", required=True, help="Target date in YYYY-MM-DD")
    parser.add_argument(
        "--artifacts-root",
        default="experiments/amical/artifacts",
        help="Artifacts root containing daily-rollups",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    artifacts_root = Path(args.artifacts_root).expanduser().resolve()
    output_dir = resolve_output_dir(artifacts_root, args.date)
    snapshot_path = output_dir / "12_active_state_snapshot.json"
    if not snapshot_path.exists():
        raise SystemExit(f"Missing snapshot: {snapshot_path}")

    snapshot = load_json(snapshot_path)
    bundle = build_context_bundle(snapshot, recent_rollup_context(artifacts_root, args.date))
    write_json(output_dir / "09_context_bundle.json", bundle)

    print(
        json.dumps(
            {
                "date": args.date,
                "active_task_ref_count": len(bundle.get("active_task_refs") or []),
                "active_decision_ref_count": len(bundle.get("active_decision_refs") or []),
                "active_knowledge_ref_count": len(bundle.get("active_knowledge_refs") or []),
                "priority_item_count": len(bundle.get("priority_items") or []),
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
