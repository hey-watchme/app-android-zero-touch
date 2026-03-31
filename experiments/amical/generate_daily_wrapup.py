#!/usr/bin/env python3
"""Deprecated compatibility wrapper for the old daily wrap-up script name."""

from __future__ import annotations

import sys
from pathlib import Path


def main() -> None:
    script_dir = Path(__file__).resolve().parent
    sys.path.insert(0, str(script_dir))
    from generate_spot_wrapup import main as spot_main  # type: ignore

    print(
        "[deprecated] generate_daily_wrapup.py now maps to generate_spot_wrapup.py. "
        "Use generate_spot_wrapup.py for spot windows and generate_daily_rollup.py for day-level aggregation.",
        file=sys.stderr,
    )
    spot_main()


if __name__ == "__main__":
    main()
