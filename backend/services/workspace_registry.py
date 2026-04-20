"""
Workspace and device registry helpers.

These helpers let the existing device_id-based pipeline resolve a higher-level
workspace owner without forcing all callers to know about the new tables.
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional

from supabase import Client


DEVICE_TABLE = "zerotouch_devices"
CONTEXT_PROFILE_TABLE = "zerotouch_context_profiles"
PROJECT_TABLE = "zerotouch_workspace_projects"


def _normalize_text(value: Any) -> Optional[str]:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _normalize_list(value: Any) -> List[str]:
    if isinstance(value, list):
        items: List[str] = []
        for item in value:
            text = _normalize_text(item)
            if text:
                items.append(text)
        return items
    text = _normalize_text(value)
    return [text] if text else []


def _stringify_list(values: List[str], limit: int = 6) -> Optional[str]:
    if not values:
        return None
    clipped = values[:limit]
    suffix = ""
    if len(values) > limit:
        suffix = f" (+{len(values) - limit} more)"
    return ", ".join(clipped) + suffix


def _normalize_aliases(values: Any) -> List[str]:
    aliases = _normalize_list(values)
    deduped: List[str] = []
    seen: set[str] = set()
    for alias in aliases:
        key = alias.casefold()
        if key in seen:
            continue
        seen.add(key)
        deduped.append(alias)
    return deduped


def _make_project_key(value: Any) -> Optional[str]:
    text = _normalize_text(value)
    if not text:
        return None

    chars: List[str] = []
    previous_was_sep = False
    for raw_char in text.casefold():
        if raw_char.isalnum():
            chars.append(raw_char)
            previous_was_sep = False
            continue
        if raw_char in {" ", "-", "_", "/", "&", ":"}:
            if not previous_was_sep:
                chars.append("-")
                previous_was_sep = True
            continue

    key = "".join(chars).strip("-")
    return key or None


def _load_context_profile(
    supabase: Client,
    workspace_id: str,
) -> Optional[Dict[str, Any]]:
    if not workspace_id:
        return None
    try:
        rows = (
            supabase.table(CONTEXT_PROFILE_TABLE)
            .select(
                "owner_name, usage_scenario, environment, goal, prompt_preamble, "
                "account_context, workspace_context, device_contexts, "
                "environment_context, analysis_context"
            )
            .eq("workspace_id", workspace_id)
            .limit(1)
            .execute()
            .data
            or []
        )
    except Exception:
        return None
    return rows[0] if rows else None


def _extract_seed_projects(profile: Optional[Dict[str, Any]]) -> List[Dict[str, Any]]:
    if not profile:
        return []

    workspace_context = profile.get("workspace_context")
    if not isinstance(workspace_context, dict):
        return []

    raw_projects = workspace_context.get("key_projects")
    if not isinstance(raw_projects, list):
        return []

    seeds: List[Dict[str, Any]] = []
    seen_keys: set[str] = set()
    for item in raw_projects:
        if not isinstance(item, dict):
            continue
        display_name = _normalize_text(item.get("wiki_theme")) or _normalize_text(item.get("name"))
        if not display_name:
            continue
        project_key = _make_project_key(display_name) or _make_project_key(item.get("name"))
        if not project_key or project_key in seen_keys:
            continue
        seen_keys.add(project_key)

        aliases = _normalize_aliases([
            item.get("name"),
            item.get("wiki_theme"),
        ])
        seeds.append({
            "project_key": project_key,
            "display_name": display_name,
            "description": _normalize_text(item.get("summary")),
            "aliases": aliases,
            "status": "active",
            "source": "seeded_from_context",
        })
    return seeds


def _summarize_projects(workspace_context: Dict[str, Any]) -> Optional[str]:
    raw_projects = workspace_context.get("key_projects")
    if not isinstance(raw_projects, list):
        return None

    summarized: List[str] = []
    for item in raw_projects:
        if not isinstance(item, dict):
            continue
        name = _normalize_text(item.get("name"))
        if not name:
            continue
        project_type = _normalize_text(item.get("type"))
        key_topics = _normalize_list(item.get("key_topics"))
        topic_hint = _stringify_list(key_topics, limit=3)
        detail_parts: List[str] = []
        if project_type:
            detail_parts.append(project_type)
        if topic_hint:
            detail_parts.append(topic_hint)
        if detail_parts:
            summarized.append(f"{name} ({'; '.join(detail_parts)})")
        else:
            summarized.append(name)

    return _stringify_list(summarized, limit=5)


def _summarize_device_context(
    device_contexts: Any,
    device_id: Optional[str],
) -> Optional[str]:
    if not isinstance(device_contexts, list):
        return None

    matched_summary: Optional[str] = None
    fallback_summary: Optional[str] = None

    for item in device_contexts:
        if not isinstance(item, dict):
            continue
        summary = _normalize_text(item.get("summary"))
        item_device_id = _normalize_text(item.get("device_id"))
        if summary and not fallback_summary:
            fallback_summary = summary
        if summary and device_id and item_device_id == device_id:
            matched_summary = summary
            break

    return matched_summary or fallback_summary


def _build_context_preamble(
    profile: Dict[str, Any],
    device_record: Optional[Dict[str, Any]] = None,
    device_id: Optional[str] = None,
) -> str:
    account_context = profile.get("account_context") if isinstance(profile.get("account_context"), dict) else {}
    workspace_context = profile.get("workspace_context") if isinstance(profile.get("workspace_context"), dict) else {}
    environment_context = profile.get("environment_context") if isinstance(profile.get("environment_context"), dict) else {}
    analysis_context = profile.get("analysis_context") if isinstance(profile.get("analysis_context"), dict) else {}
    device_contexts = profile.get("device_contexts")

    identity_summary = _normalize_text(
        account_context.get("identity_summary") or profile.get("owner_name")
    )
    roles = _stringify_list(_normalize_list(account_context.get("primary_roles")))
    product_summary = _normalize_text(account_context.get("product_summary"))

    workspace_summary = _normalize_text(
        workspace_context.get("workspace_summary") or profile.get("usage_scenario")
    )
    workspace_type = _normalize_text(workspace_context.get("workspace_type"))
    workspace_goals = _stringify_list(_normalize_list(workspace_context.get("workspace_goals")))
    operating_rules = _stringify_list(_normalize_list(workspace_context.get("operating_rules")))
    project_summary = _summarize_projects(workspace_context)

    device_summary = _summarize_device_context(device_contexts, device_id)
    if not device_summary and device_record:
        display_name = _normalize_text(device_record.get("display_name"))
        context_note = _normalize_text(device_record.get("context_note"))
        if display_name and context_note:
            device_summary = f"{display_name}: {context_note}"
        else:
            device_summary = display_name or context_note

    environment_summary = _normalize_text(
        environment_context.get("summary") or profile.get("environment")
    )
    analysis_goal = _normalize_text(
        analysis_context.get("analysis_objective")
        or analysis_context.get("goal")
        or profile.get("goal")
    )
    focus_topics = _stringify_list(_normalize_list(analysis_context.get("focus_topics")), limit=8)
    ignore_topics = _stringify_list(_normalize_list(analysis_context.get("ignore_topics")), limit=6)
    taxonomy = _stringify_list(_normalize_list(analysis_context.get("topic_taxonomy")), limit=8)
    classification_notes = _normalize_text(analysis_context.get("classification_notes"))

    fallback_preamble = _normalize_text(profile.get("prompt_preamble"))

    lines: List[str] = []
    mapping = [
        ("Identity", identity_summary),
        ("Roles", roles),
        ("Product", product_summary),
        ("Workspace", workspace_summary),
        ("Workspace Type", workspace_type),
        ("Workspace Goals", workspace_goals),
        ("Projects", project_summary),
        ("Device", device_summary),
        ("Environment", environment_summary),
        ("Goal", analysis_goal),
        ("Focus Topics", focus_topics),
        ("Ignore Topics", ignore_topics),
        ("Theme Hints", taxonomy),
        ("Operating Rules", operating_rules),
        ("Classification Notes", classification_notes),
    ]
    for label, value in mapping:
        text = _normalize_text(value)
        if text:
            lines.append(f"{label}: {text}")

    if lines:
        return "\n".join(lines)
    return fallback_preamble or ""


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
    device_id: Optional[str] = None,
) -> str:
    """
    Build a prompt preamble from structured context_profile fields for a workspace.
    Falls back to the stored prompt_preamble when structured fields are absent.
    """
    profile = _load_context_profile(supabase=supabase, workspace_id=workspace_id)
    if not profile:
        return ""

    device_record = get_device_record(supabase=supabase, device_id=device_id) if device_id else None
    return _build_context_preamble(
        profile=profile,
        device_record=device_record,
        device_id=device_id,
    )


def ensure_workspace_projects(
    supabase: Client,
    workspace_id: str,
) -> List[Dict[str, Any]]:
    if not workspace_id:
        return []

    try:
        existing_rows = (
            supabase.table(PROJECT_TABLE)
            .select("*")
            .eq("workspace_id", workspace_id)
            .order("display_name", desc=False)
            .execute()
            .data
            or []
        )
    except Exception:
        return []

    existing_keys = {
        str(row.get("project_key") or "").strip().casefold()
        for row in existing_rows
        if str(row.get("project_key") or "").strip()
    }

    seeds = _extract_seed_projects(
        _load_context_profile(supabase=supabase, workspace_id=workspace_id)
    )
    missing = [
        {
            **seed,
            "workspace_id": workspace_id,
        }
        for seed in seeds
        if seed["project_key"].casefold() not in existing_keys
    ]

    if missing:
        try:
            supabase.table(PROJECT_TABLE).insert(missing).execute()
        except Exception:
            pass

    try:
        refreshed_rows = (
            supabase.table(PROJECT_TABLE)
            .select("*")
            .eq("workspace_id", workspace_id)
            .order("display_name", desc=False)
            .execute()
            .data
            or []
        )
    except Exception:
        return existing_rows
    return refreshed_rows


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
