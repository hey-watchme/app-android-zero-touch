"""
Workspace and device registry helpers.

These helpers let the existing device_id-based pipeline resolve a higher-level
workspace owner without forcing all callers to know about the new tables.
"""

from __future__ import annotations

from typing import Any, Dict, Optional

from supabase import Client


DEVICE_TABLE = "zerotouch_devices"


def get_device_record(
    supabase: Client,
    device_id: str,
) -> Optional[Dict[str, Any]]:
    if not device_id:
        return None

    try:
        rows = (
            supabase.table(DEVICE_TABLE)
            .select("*")
            .eq("device_id", device_id)
            .limit(1)
            .execute()
            .data
            or []
        )
    except Exception:
        return None
    return rows[0] if rows else None


def resolve_workspace_id_for_device(
    supabase: Client,
    device_id: str,
) -> Optional[str]:
    record = get_device_record(supabase=supabase, device_id=device_id)
    if not record:
        return None
    value = record.get("workspace_id")
    if value is None:
        return None
    text = str(value).strip()
    return text or None
