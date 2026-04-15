"""
Workspace and device registry helpers.

These helpers let the existing device_id-based pipeline resolve a higher-level
workspace owner without forcing all callers to know about the new tables.
"""

from __future__ import annotations

from typing import Any, Dict, Optional

from supabase import Client


DEVICE_TABLE = "zerotouch_devices"
CONTEXT_PROFILE_TABLE = "zerotouch_context_profiles"


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


def fetch_context_preamble(
    supabase: Client,
    workspace_id: str,
) -> str:
    """
    Fetch prompt_preamble from zerotouch_context_profiles for a workspace.
    Returns empty string if not found or workspace_id is missing.
    """
    if not workspace_id:
        return ""
    try:
        rows = (
            supabase.table(CONTEXT_PROFILE_TABLE)
            .select("prompt_preamble")
            .eq("workspace_id", workspace_id)
            .limit(1)
            .execute()
            .data
            or []
        )
    except Exception:
        return ""
    if not rows:
        return ""
    return (rows[0].get("prompt_preamble") or "").strip()


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
