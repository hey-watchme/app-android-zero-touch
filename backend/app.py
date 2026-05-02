"""
ZeroTouch Backend API

FastAPI application for ambient voice capture -> transcription -> card generation.
"""

import os
import uuid
import threading
import time
from datetime import datetime, timedelta
from contextlib import asynccontextmanager

import boto3
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Any, Dict, List, Optional
from supabase import create_client, Client

from services.llm_providers import LLMFactory, get_current_llm
from services.llm_models import get_model_catalog
from services.asr_providers import get_asr_service
from services.background_tasks import transcribe_background, generate_cards_background
from services.topic_manager_process2 import (
    DEFAULT_IDLE_SECONDS,
    finalize_active_topic_for_device,
    resolve_device_llm_service,
)
from services.topic_annotator import annotate_topic
from services.topic_manager import backfill_ungrouped_sessions, reconcile_topics
from services.wiki_ingestor import ingest_wiki
from services.wiki_querier import query_wiki
from services.wiki_linter import lint_wiki
from services.workspace_registry import resolve_workspace_id_for_device
from services.action_converter import (
    convert_topic as run_action_converter,
    list_action_candidates,
    review_action_candidate,
)


# --- Configuration ---


def _env_str(name: str, default: str) -> str:
    value = os.getenv(name)
    if value is None:
        return default
    stripped = value.strip()
    return stripped or default


def _env_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    return value.strip().lower() not in {"0", "false", "no"}


def _env_float(name: str, default: float) -> float:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    try:
        return float(value)
    except ValueError:
        print(f"[ZeroTouch] Invalid float for {name}: {value!r}. Falling back to {default}.")
        return default

SUPABASE_URL = os.getenv("SUPABASE_URL")
SUPABASE_SERVICE_ROLE_KEY = os.getenv("SUPABASE_SERVICE_ROLE_KEY")
S3_BUCKET = os.getenv("S3_BUCKET", "watchme-vault")
AWS_REGION = os.getenv("AWS_REGION", "ap-southeast-2")
TOPIC_FINALIZE_SCHEDULER_ENABLED = _env_bool("TOPIC_FINALIZE_SCHEDULER_ENABLED", True)
TOPIC_FINALIZE_SCHEDULER_INTERVAL_SECONDS = int(os.getenv("TOPIC_FINALIZE_SCHEDULER_INTERVAL_SECONDS", "15"))
TABLE = "zerotouch_sessions"
TOPIC_TABLE = os.getenv("TOPIC_TABLE", "zerotouch_conversation_topics")
ACCOUNT_TABLE = "zerotouch_accounts"
WORKSPACE_TABLE = "zerotouch_workspaces"
WORKSPACE_MEMBER_TABLE = "zerotouch_workspace_members"
ORGANIZATION_TABLE = "zerotouch_organizations"
ORGANIZATION_MEMBER_TABLE = "zerotouch_organization_members"
INVITATION_TABLE = "zerotouch_workspace_invitations"
DEVICE_TABLE = "zerotouch_devices"
CONTEXT_PROFILE_TABLE = "zerotouch_context_profiles"
FACT_TABLE = "zerotouch_facts"
DEVICE_SETTINGS_TABLE = "zerotouch_device_settings"
LIVE_SESSION_TABLE = "zerotouch_live_sessions"
LIVE_TRANSCRIPT_TABLE = "zerotouch_live_transcripts"
LIVE_KEYPOINT_TABLE = "zerotouch_live_keypoints"
REALTIME_TRANSCRIBE_MODEL = _env_str("REALTIME_TRANSCRIBE_MODEL", "gpt-4o-transcribe")
REALTIME_TRANSCRIBE_LANGUAGE = _env_str("REALTIME_TRANSCRIBE_LANGUAGE", "ja")
REALTIME_TRANSCRIBE_PROMPT = os.getenv("REALTIME_TRANSCRIBE_PROMPT", "").strip()
REALTIME_TRANSCRIBE_TEMPERATURE = _env_float("REALTIME_TRANSCRIBE_TEMPERATURE", 0.0)
REALTIME_TRANSCRIBE_PERSIST = _env_bool("REALTIME_TRANSCRIBE_PERSIST", False)
REALTIME_TRANSLATE_MODEL = _env_str("REALTIME_TRANSLATE_MODEL", "gpt-4o-mini")


# --- Globals ---

supabase: Client = None
s3_client = None
topic_finalize_stop_event: threading.Event = None
topic_finalize_thread: threading.Thread = None


def _run_topic_finalize_scheduler(stop_event: threading.Event):
    interval = max(5, min(TOPIC_FINALIZE_SCHEDULER_INTERVAL_SECONDS, 300))
    print(f"[ZeroTouch] Topic finalize scheduler started interval={interval}s")
    while not stop_event.wait(interval):
        try:
            rows = (
                supabase.table(TOPIC_TABLE)
                .select("device_id")
                .eq("topic_status", "active")
                .execute()
                .data
                or []
            )
            device_ids = sorted({row.get("device_id") for row in rows if row.get("device_id")})
            for device_id in device_ids:
                llm_service = resolve_device_llm_service(
                    supabase=supabase,
                    device_id=device_id,
                    fallback_llm_service=_get_topic_llm_service(),
                )
                result = finalize_active_topic_for_device(
                    supabase=supabase,
                    device_id=device_id,
                    llm_service=llm_service,
                    idle_seconds=DEFAULT_IDLE_SECONDS,
                    force=False,
                    boundary_reason="idle_timeout",
                )
                if result.get("ready") or result.get("finalized"):
                    print(f"[ZeroTouch] Topic finalize scheduler result device={device_id}: {result}")
        except Exception as error:
            print(f"[ZeroTouch] Topic finalize scheduler failed: {error}")
    print("[ZeroTouch] Topic finalize scheduler stopped")


@asynccontextmanager
async def lifespan(app: FastAPI):
    global supabase, s3_client, topic_finalize_stop_event, topic_finalize_thread

    # Startup
    if not SUPABASE_URL or not SUPABASE_SERVICE_ROLE_KEY:
        raise RuntimeError("SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY must be set")

    supabase = create_client(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY)
    s3_client = boto3.client("s3", region_name=AWS_REGION)

    print(f"[ZeroTouch] API started - Supabase: {SUPABASE_URL[:30]}...")
    print(f"[ZeroTouch] S3 bucket: {S3_BUCKET}, Region: {AWS_REGION}")
    if TOPIC_FINALIZE_SCHEDULER_ENABLED:
        topic_finalize_stop_event = threading.Event()
        topic_finalize_thread = threading.Thread(
            target=_run_topic_finalize_scheduler,
            args=(topic_finalize_stop_event,),
            daemon=True,
            name="zerotouch-topic-finalize-scheduler",
        )
        topic_finalize_thread.start()

    yield

    # Shutdown
    if topic_finalize_stop_event is not None:
        topic_finalize_stop_event.set()
    if topic_finalize_thread is not None:
        topic_finalize_thread.join(timeout=2)
    print("[ZeroTouch] API shutting down")


app = FastAPI(
    title="ZeroTouch API",
    description="Ambient voice capture -> transcription -> card generation",
    version="0.1.0",
    lifespan=lifespan
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# --- Request/Response Models ---

class SessionResponse(BaseModel):
    id: str
    device_id: str
    status: str
    s3_audio_path: Optional[str] = None
    duration_seconds: Optional[int] = None
    transcription: Optional[str] = None
    transcription_metadata: Optional[dict] = None
    cards_result: Optional[dict] = None
    model_used: Optional[str] = None
    error_message: Optional[str] = None
    recorded_at: Optional[str] = None
    created_at: str
    updated_at: str


class GenerateCardsRequest(BaseModel):
    provider: Optional[str] = None
    model: Optional[str] = None
    use_custom_prompt: Optional[bool] = False


class TopicReconcileRequest(BaseModel):
    device_id: Optional[str] = None
    topic_id: Optional[str] = None
    force_finalize: bool = False


class TopicBackfillRequest(BaseModel):
    device_id: Optional[str] = None
    limit: int = 200


class TopicEvaluatePendingRequest(BaseModel):
    device_id: str
    force: bool = False
    idle_seconds: int = DEFAULT_IDLE_SECONDS
    max_sessions: int = 200
    boundary_reason: Optional[str] = None


class DeviceSettingsRequest(BaseModel):
    llm_provider: Optional[str] = None
    llm_model: Optional[str] = None


class DistillAnnotateRequest(BaseModel):
    force: bool = False


class AccountCreateRequest(BaseModel):
    display_name: str
    email: Optional[str] = None
    external_auth_provider: Optional[str] = None
    external_auth_subject: Optional[str] = None
    avatar_url: Optional[str] = None


class AccountUpdateRequest(BaseModel):
    display_name: Optional[str] = None
    avatar_url: Optional[str] = None


class WorkspaceCreateRequest(BaseModel):
    name: str
    owner_account_id: Optional[str] = None
    organization_id: Optional[str] = None
    slug: Optional[str] = None
    description: Optional[str] = None


class OrganizationCreateRequest(BaseModel):
    name: str
    created_by: Optional[str] = None
    slug: Optional[str] = None


class OrganizationUpdateRequest(BaseModel):
    name: Optional[str] = None
    slug: Optional[str] = None


class OrganizationMemberUpsertRequest(BaseModel):
    account_id: str
    role: str = "org_member"


class WorkspaceMemberUpsertRequest(BaseModel):
    account_id: str
    role: str = "editor"


class WorkspaceUpdateRequest(BaseModel):
    name: Optional[str] = None
    slug: Optional[str] = None
    description: Optional[str] = None


class DeviceCreateRequest(BaseModel):
    workspace_id: str
    device_id: str
    display_name: str
    device_kind: str = "android_tablet"
    source_type: str = "android_ambient"
    platform: Optional[str] = None
    context_note: Optional[str] = None
    is_virtual: bool = False
    is_active: bool = True


class DeviceUpdateRequest(BaseModel):
    display_name: Optional[str] = None
    context_note: Optional[str] = None
    is_active: Optional[bool] = None


class ContextProfileRequest(BaseModel):
    schema_version: Optional[int] = 1
    profile_name: Optional[str] = None
    owner_name: Optional[str] = None
    role_title: Optional[str] = None
    identity_summary: Optional[str] = None
    environment: Optional[str] = None
    usage_scenario: Optional[str] = None
    goal: Optional[str] = None
    analysis_notes: Optional[str] = None
    account_context: Optional[Dict[str, Any]] = None
    workspace_context: Optional[Dict[str, Any]] = None
    device_contexts: Optional[List[Dict[str, Any]]] = None
    environment_context: Optional[Dict[str, Any]] = None
    analysis_context: Optional[Dict[str, Any]] = None
    onboarding_completed_at: Optional[str] = None
    reference_materials: Optional[List[Dict[str, Any]]] = None
    glossary: Optional[List[Dict[str, Any]]] = None
    prompt_preamble: Optional[str] = None


class LiveSessionCreateRequest(BaseModel):
    device_id: str
    workspace_id: Optional[str] = None
    share_token: Optional[str] = None
    language_primary: Optional[str] = "ja"
    visibility: Optional[str] = "public"
    metadata: Optional[Dict[str, Any]] = None


# --- Health ---

@app.get("/health")
def health():
    return {"status": "ok", "service": "zerotouch-api", "version": "0.1.0"}


def _get_topic_llm_service():
    try:
        return get_current_llm()
    except Exception as exc:
        print(f"[ZeroTouch] Topic LLM unavailable, fallback to rule-based grouping: {exc}")
        return None


def _resolve_session_timestamp(row: Dict[str, Any]) -> str:
    return (
        row.get("recorded_at")
        or row.get("created_at")
        or datetime.now().isoformat()
    )


def _parse_session_timestamp(row: Dict[str, Any]) -> datetime:
    value = _resolve_session_timestamp(row)
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except Exception:
        return datetime.now()


def _cleanup_topic_after_session_delete(topic_id: Optional[str]):
    if not topic_id:
        return {"topic_deleted": False, "topic_updated": False}

    remaining = (
        supabase.table(TABLE)
        .select("id, recorded_at, created_at")
        .eq("topic_id", topic_id)
        .execute()
        .data
        or []
    )
    remaining.sort(key=_parse_session_timestamp)

    if not remaining:
        supabase.table(TOPIC_TABLE).delete().eq("id", topic_id).execute()
        return {"topic_deleted": True, "topic_updated": False}

    start_at = _resolve_session_timestamp(remaining[0])
    end_at = _resolve_session_timestamp(remaining[-1])
    payload = {
        "utterance_count": len(remaining),
        "start_at": start_at,
        "end_at": end_at,
        "last_utterance_at": end_at,
        "updated_at": datetime.now().isoformat(),
    }
    supabase.table(TOPIC_TABLE).update(payload).eq("id", topic_id).execute()
    return {"topic_deleted": False, "topic_updated": True}


def _error_mentions_missing_column(error: Exception, column: str) -> bool:
    message = str(error).lower()
    column_name = column.lower()
    return column_name in message and (
        "column" in message
        or "schema cache" in message
        or "could not find" in message
    )


def _insert_session_compat(payload: Dict[str, Any]) -> None:
    candidate = dict(payload)
    while True:
        try:
            supabase.table(TABLE).insert(candidate).execute()
            return
        except Exception as error:
            missing_columns = [
                column
                for column in list(candidate.keys())
                if column != "id" and _error_mentions_missing_column(error, column)
            ]
            if missing_columns:
                for column in missing_columns:
                    candidate.pop(column, None)
                continue
            raise


def _looks_like_duplicate_share_token(error: Exception) -> bool:
    message = str(error).lower()
    return "duplicate key value" in message and "share_token" in message


def _generate_live_share_token() -> str:
    return uuid.uuid4().hex[:12]


def _resolve_workspace_id(
    device_id: Optional[str] = None,
    workspace_id: Optional[str] = None,
) -> Optional[str]:
    explicit = (workspace_id or "").strip()
    if explicit:
        return explicit
    identifier = (device_id or "").strip()
    if not identifier:
        return None
    return resolve_workspace_id_for_device(supabase=supabase, device_id=identifier)


def _organization_ids_for_account(account_id: str) -> List[str]:
    rows = (
        supabase.table(ORGANIZATION_MEMBER_TABLE)
        .select("organization_id")
        .eq("account_id", account_id)
        .execute()
        .data
        or []
    )
    return [str(row.get("organization_id")).strip() for row in rows if row.get("organization_id")]


def _workspace_ids_for_organizations(organization_ids: List[str]) -> List[str]:
    if not organization_ids:
        return []
    rows = (
        supabase.table(WORKSPACE_TABLE)
        .select("id")
        .in_("organization_id", organization_ids)
        .execute()
        .data
        or []
    )
    return [str(row.get("id")).strip() for row in rows if row.get("id")]


def _workspace_ids_for_account(account_id: str) -> List[str]:
    # Resolution order:
    #   1. Workspaces belonging to any organization the account is a member of
    #      (covers the common case and gives org_admins access to every workspace).
    #   2. Workspaces where the account is directly listed as a workspace_member
    #      (covers historical data and explicit per-workspace membership).
    ids: set[str] = set()

    org_ids = _organization_ids_for_account(account_id)
    if org_ids:
        ids.update(_workspace_ids_for_organizations(org_ids))

    direct_rows = (
        supabase.table(WORKSPACE_MEMBER_TABLE)
        .select("workspace_id")
        .eq("account_id", account_id)
        .execute()
        .data
        or []
    )
    for row in direct_rows:
        ws_id = row.get("workspace_id")
        if ws_id:
            ids.add(str(ws_id).strip())

    return [ws_id for ws_id in ids if ws_id]


def _normalize_text(value: Any) -> Optional[str]:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _first_non_blank(*values: Any) -> Optional[str]:
    for value in values:
        text = _normalize_text(value)
        if text:
            return text
    return None


def _safe_json_object(value: Any) -> Dict[str, Any]:
    if isinstance(value, dict):
        return dict(value)
    return {}


def _parse_prompt_preamble(preamble: Optional[str]) -> Dict[str, str]:
    if not preamble:
        return {}
    result: Dict[str, str] = {}
    prefixes = {
        "Identity:": "identity_summary",
        "Role:": "role_title",
        "Workspace:": "workspace_summary",
        "Device:": "device_summary",
        "Environment:": "environment_summary",
        "Goal:": "analysis_goal",
        "Notes:": "analysis_notes",
    }
    for raw_line in str(preamble).splitlines():
        line = raw_line.strip()
        if not line:
            continue
        for prefix, key in prefixes.items():
            if line.startswith(prefix):
                value = _normalize_text(line[len(prefix):])
                if value:
                    result[key] = value
                break
    return result


def _build_prompt_preamble(
    identity_summary: Optional[str],
    role_title: Optional[str],
    workspace_summary: Optional[str],
    device_summary: Optional[str],
    environment_summary: Optional[str],
    analysis_goal: Optional[str],
    analysis_notes: Optional[str],
) -> Optional[str]:
    lines: List[str] = []
    mapping = [
        ("Identity", identity_summary),
        ("Role", role_title),
        ("Workspace", workspace_summary),
        ("Device", device_summary),
        ("Environment", environment_summary),
        ("Goal", analysis_goal),
        ("Notes", analysis_notes),
    ]
    for label, value in mapping:
        text = _normalize_text(value)
        if text:
            lines.append(f"{label}: {text}")
    if not lines:
        return None
    return "\n".join(lines)


def _normalize_device_contexts(raw_device_contexts: Any) -> List[Dict[str, Any]]:
    if not isinstance(raw_device_contexts, list):
        return []
    result: List[Dict[str, Any]] = []
    for item in raw_device_contexts:
        if not isinstance(item, dict):
            continue
        device_id = _normalize_text(item.get("device_id"))
        summary = _normalize_text(item.get("summary"))
        if not device_id and not summary:
            continue
        row: Dict[str, Any] = {}
        if device_id:
            row["device_id"] = device_id
        if summary:
            row["summary"] = summary
        result.append(row)
    return result


def _build_context_profile_payload(
    workspace_id: str,
    body: ContextProfileRequest,
) -> Dict[str, Any]:
    prompt_fields = _parse_prompt_preamble(body.prompt_preamble)

    account_ctx_in = body.account_context
    workspace_ctx_in = body.workspace_context
    environment_ctx_in = body.environment_context
    analysis_ctx_in = body.analysis_context
    device_ctx_in = body.device_contexts or []

    owner_name = _first_non_blank(
        body.owner_name,
        account_ctx_in.get("owner_name") if account_ctx_in else None,
    )
    role_title = _first_non_blank(
        body.role_title,
        account_ctx_in.get("role_title") if account_ctx_in else None,
        prompt_fields.get("role_title"),
    )
    identity_summary = _first_non_blank(
        body.identity_summary,
        account_ctx_in.get("identity_summary") if account_ctx_in else None,
        prompt_fields.get("identity_summary"),
    )
    profile_name = _first_non_blank(
        body.profile_name,
        workspace_ctx_in.get("profile_name") if workspace_ctx_in else None,
    )
    usage_scenario = _first_non_blank(
        body.usage_scenario,
        workspace_ctx_in.get("usage_scenario") if workspace_ctx_in else None,
        prompt_fields.get("workspace_summary"),
    )
    environment_summary = _first_non_blank(
        body.environment,
        environment_ctx_in.get("summary") if environment_ctx_in else None,
        prompt_fields.get("environment_summary"),
    )
    analysis_goal = _first_non_blank(
        body.goal,
        analysis_ctx_in.get("goal") if analysis_ctx_in else None,
        prompt_fields.get("analysis_goal"),
    )
    analysis_notes = _first_non_blank(
        body.analysis_notes,
        analysis_ctx_in.get("notes") if analysis_ctx_in else None,
        prompt_fields.get("analysis_notes"),
    )

    normalized_device_contexts: List[Dict[str, Any]] = []
    for item in device_ctx_in:
        if item is None:
            continue
        device_id = _normalize_text(item.get("device_id") if isinstance(item, dict) else None)
        summary = _normalize_text(item.get("summary") if isinstance(item, dict) else None)
        if not device_id and not summary:
            continue
        row: Dict[str, Any] = {}
        if device_id:
            row["device_id"] = device_id
        if summary:
            row["summary"] = summary
        normalized_device_contexts.append(row)

    if not normalized_device_contexts:
        legacy_device_summary = _first_non_blank(prompt_fields.get("device_summary"))
        if legacy_device_summary:
            normalized_device_contexts = [{"summary": legacy_device_summary}]

    # Start from the full incoming dict, then enforce/override scalar fields
    account_context: Dict[str, Any] = dict(account_ctx_in or {})
    if owner_name:
        account_context["owner_name"] = owner_name
    if role_title:
        account_context["role_title"] = role_title
    if identity_summary:
        account_context["identity_summary"] = identity_summary

    workspace_context: Dict[str, Any] = dict(workspace_ctx_in or {})
    if profile_name:
        workspace_context["profile_name"] = profile_name
    if usage_scenario:
        workspace_context["usage_scenario"] = usage_scenario

    environment_context: Dict[str, Any] = dict(environment_ctx_in or {})
    if environment_summary:
        environment_context["summary"] = environment_summary

    analysis_context: Dict[str, Any] = dict(analysis_ctx_in or {})
    if analysis_goal:
        analysis_context["goal"] = analysis_goal
    if analysis_notes:
        analysis_context["notes"] = analysis_notes

    prompt_preamble = _build_prompt_preamble(
        identity_summary=identity_summary,
        role_title=role_title,
        workspace_summary=usage_scenario,
        device_summary=normalized_device_contexts[0].get("summary") if normalized_device_contexts else None,
        environment_summary=environment_summary,
        analysis_goal=analysis_goal,
        analysis_notes=analysis_notes,
    )

    now_iso = datetime.now().isoformat()
    schema_version = body.schema_version if body.schema_version and body.schema_version > 0 else 1
    payload: Dict[str, Any] = {
        "workspace_id": workspace_id,
        "schema_version": schema_version,
        "profile_name": profile_name,
        "owner_name": owner_name,
        "role_title": role_title,
        "environment": environment_summary,
        "usage_scenario": usage_scenario,
        "goal": analysis_goal,
        "account_context": account_context,
        "workspace_context": workspace_context,
        "device_contexts": normalized_device_contexts,
        "environment_context": environment_context,
        "analysis_context": analysis_context,
        "reference_materials": body.reference_materials or [],
        "glossary": body.glossary or [],
        "prompt_preamble": prompt_preamble,
        "updated_at": now_iso,
    }

    if body.onboarding_completed_at is not None:
        payload["onboarding_completed_at"] = _normalize_text(body.onboarding_completed_at)

    return payload


def _normalize_context_profile_record(profile: Dict[str, Any]) -> Dict[str, Any]:
    prompt_fields = _parse_prompt_preamble(profile.get("prompt_preamble"))
    account_context = _safe_json_object(profile.get("account_context"))
    workspace_context = _safe_json_object(profile.get("workspace_context"))
    environment_context = _safe_json_object(profile.get("environment_context"))
    analysis_context = _safe_json_object(profile.get("analysis_context"))
    device_contexts = _normalize_device_contexts(profile.get("device_contexts"))

    owner_name = _first_non_blank(profile.get("owner_name"), account_context.get("owner_name"))
    role_title = _first_non_blank(profile.get("role_title"), account_context.get("role_title"), prompt_fields.get("role_title"))
    identity_summary = _first_non_blank(account_context.get("identity_summary"), prompt_fields.get("identity_summary"))
    profile_name = _first_non_blank(profile.get("profile_name"), workspace_context.get("profile_name"))
    usage_scenario = _first_non_blank(profile.get("usage_scenario"), workspace_context.get("usage_scenario"), prompt_fields.get("workspace_summary"))

    first_device_summary = None
    if device_contexts:
        first_device_summary = _first_non_blank(device_contexts[0].get("summary"))
    device_summary = _first_non_blank(first_device_summary, prompt_fields.get("device_summary"))
    if not device_contexts and device_summary:
        device_contexts = [{"summary": device_summary}]

    environment_summary = _first_non_blank(profile.get("environment"), environment_context.get("summary"), prompt_fields.get("environment_summary"))
    analysis_goal = _first_non_blank(profile.get("goal"), analysis_context.get("goal"), prompt_fields.get("analysis_goal"))
    analysis_notes = _first_non_blank(analysis_context.get("notes"), prompt_fields.get("analysis_notes"))

    normalized_account_context = dict(account_context)
    if owner_name:
        normalized_account_context["owner_name"] = owner_name
    if role_title:
        normalized_account_context["role_title"] = role_title
    if identity_summary:
        normalized_account_context["identity_summary"] = identity_summary

    normalized_workspace_context = dict(workspace_context)
    if profile_name:
        normalized_workspace_context["profile_name"] = profile_name
    if usage_scenario:
        normalized_workspace_context["usage_scenario"] = usage_scenario

    normalized_environment_context = dict(environment_context)
    if environment_summary:
        normalized_environment_context["summary"] = environment_summary

    normalized_analysis_context = dict(analysis_context)
    if analysis_goal:
        normalized_analysis_context["goal"] = analysis_goal
    if analysis_notes:
        normalized_analysis_context["notes"] = analysis_notes

    prompt_preamble = _build_prompt_preamble(
        identity_summary=identity_summary,
        role_title=role_title,
        workspace_summary=usage_scenario,
        device_summary=device_summary,
        environment_summary=environment_summary,
        analysis_goal=analysis_goal,
        analysis_notes=analysis_notes,
    )
    if not prompt_preamble:
        prompt_preamble = _normalize_text(profile.get("prompt_preamble"))

    normalized = dict(profile)
    normalized["schema_version"] = int(profile.get("schema_version") or 1)
    normalized["profile_name"] = profile_name
    normalized["owner_name"] = owner_name
    normalized["role_title"] = role_title
    normalized["environment"] = environment_summary
    normalized["usage_scenario"] = usage_scenario
    normalized["goal"] = analysis_goal
    normalized["identity_summary"] = identity_summary
    normalized["device_summary"] = device_summary
    normalized["analysis_notes"] = analysis_notes
    normalized["account_context"] = normalized_account_context
    normalized["workspace_context"] = normalized_workspace_context
    normalized["device_contexts"] = device_contexts
    normalized["environment_context"] = normalized_environment_context
    normalized["analysis_context"] = normalized_analysis_context
    normalized["prompt_preamble"] = prompt_preamble
    normalized["reference_materials"] = profile.get("reference_materials") or []
    normalized["glossary"] = profile.get("glossary") or []
    return normalized


def _upsert_context_profile_compat(payload: Dict[str, Any]) -> Dict[str, Any]:
    candidate = dict(payload)
    while True:
        try:
            rows = supabase.table(CONTEXT_PROFILE_TABLE).upsert(candidate).execute().data or []
            return rows[0] if rows else candidate
        except Exception as error:
            missing_columns = [
                column
                for column in list(candidate.keys())
                if column != "workspace_id" and _error_mentions_missing_column(error, column)
            ]
            if not missing_columns:
                raise
            for column in missing_columns:
                candidate.pop(column, None)


# --- Upload ---

@app.post("/api/upload")
async def upload_audio(
    file: UploadFile = File(...),
    device_id: str = Form(...),
    workspace_id: Optional[str] = Form(None),
    recorded_at: Optional[str] = Form(None),
    local_recording_id: Optional[str] = Form(None)
):
    """Upload audio file to S3 and create session."""
    stable_recording_id = (local_recording_id or "").strip() or None
    if stable_recording_id:
        existing_rows = (
            supabase.table(TABLE)
            .select("id, s3_audio_path, status")
            .eq("device_id", device_id)
            .eq("local_recording_id", stable_recording_id)
            .limit(1)
            .execute()
            .data
            or []
        )
        if existing_rows:
            existing = existing_rows[0]
            return {
                "session_id": existing["id"],
                "s3_path": existing.get("s3_audio_path"),
                "status": existing.get("status") or "uploaded",
            }

    session_id = str(uuid.uuid4())
    now = datetime.now()

    # S3 path: zerotouch/{device_id}/{date}/{session_id}.{ext}
    ext = file.filename.split(".")[-1] if file.filename and "." in file.filename else "m4a"
    date_str = now.strftime("%Y-%m-%d")
    s3_key = f"zerotouch/{device_id}/{date_str}/{session_id}.{ext}"

    try:
        # Upload to S3
        content = await file.read()
        s3_client.put_object(
            Bucket=S3_BUCKET,
            Key=s3_key,
            Body=content,
            ContentType=file.content_type or "audio/mp4"
        )

        # Create session in DB
        resolved_workspace_id = _resolve_workspace_id(device_id=device_id, workspace_id=workspace_id)
        session_data = {
            "id": session_id,
            "device_id": device_id,
            "workspace_id": resolved_workspace_id,
            "s3_audio_path": s3_key,
            "local_recording_id": stable_recording_id,
            "status": "uploaded",
            "recorded_at": recorded_at or now.isoformat(),
            "created_at": now.isoformat(),
            "updated_at": now.isoformat(),
        }
        try:
            _insert_session_compat(session_data)
        except Exception:
            if stable_recording_id:
                existing_rows = (
                    supabase.table(TABLE)
                    .select("id, s3_audio_path, status")
                    .eq("device_id", device_id)
                    .eq("local_recording_id", stable_recording_id)
                    .limit(1)
                    .execute()
                    .data
                    or []
                )
                if existing_rows:
                    existing = existing_rows[0]
                    return {
                        "session_id": existing["id"],
                        "s3_path": existing.get("s3_audio_path"),
                        "status": existing.get("status") or "uploaded",
                    }
            raise

        return {"session_id": session_id, "s3_path": s3_key, "status": "uploaded"}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Upload failed: {str(e)}")


# --- Live Support ---

@app.post("/api/live-sessions")
def create_live_session(body: LiveSessionCreateRequest):
    now_dt = datetime.now()
    now = now_dt.isoformat()
    expires_at = (now_dt + timedelta(days=30)).isoformat()
    requested_token = (body.share_token or "").strip() or None
    requested_visibility = (body.visibility or "public").strip().lower()
    if requested_visibility not in {"public", "private", "deleted"}:
        raise HTTPException(status_code=400, detail="visibility must be one of: public, private, deleted")
    max_attempts = 5

    for _ in range(max_attempts):
        share_token = requested_token or _generate_live_share_token()
        payload = {
            "id": str(uuid.uuid4()),
            "device_id": body.device_id,
            "workspace_id": _resolve_workspace_id(
                device_id=body.device_id,
                workspace_id=body.workspace_id,
            ),
            "share_token": share_token,
            "status": "active",
            "started_at": now,
            "expires_at": expires_at,
            "language_primary": body.language_primary or "ja",
            "visibility": requested_visibility,
            "metadata": body.metadata or {},
            "created_at": now,
            "updated_at": now,
        }
        try:
            row = (
                supabase.table(LIVE_SESSION_TABLE)
                .insert(payload)
                .execute()
                .data
                or []
            )
            return row[0] if row else payload
        except Exception as exc:
            # If caller explicitly requested the token and it already exists, fail fast.
            if requested_token and _looks_like_duplicate_share_token(exc):
                raise HTTPException(status_code=409, detail="share_token already exists")
            if requested_token or not _looks_like_duplicate_share_token(exc):
                raise HTTPException(status_code=500, detail=f"Failed to create live session: {exc}")
            continue

    raise HTTPException(status_code=500, detail="Failed to generate a unique share_token")


@app.get("/api/live-sessions/{live_session_id}")
def get_live_session(live_session_id: str):
    rows = (
        supabase.table(LIVE_SESSION_TABLE)
        .select("*")
        .eq("id", live_session_id)
        .eq("is_deleted", False)
        .limit(1)
        .execute()
        .data
        or []
    )
    if not rows:
        raise HTTPException(status_code=404, detail="Live session not found")
    return rows[0]


@app.get("/api/live-sessions/by-token/{share_token}")
def get_live_session_by_share_token(share_token: str):
    rows = (
        supabase.table(LIVE_SESSION_TABLE)
        .select("*")
        .eq("share_token", share_token)
        .eq("is_deleted", False)
        .limit(1)
        .execute()
        .data
        or []
    )
    if not rows:
        raise HTTPException(status_code=404, detail="Live session not found")
    return rows[0]


@app.post("/api/live-sessions/{live_session_id}/end")
def end_live_session(live_session_id: str):
    now = datetime.now().isoformat()
    rows = (
        supabase.table(LIVE_SESSION_TABLE)
        .update(
            {
                "status": "ended",
                "ended_at": now,
                "updated_at": now,
            }
        )
        .eq("id", live_session_id)
        .eq("is_deleted", False)
        .execute()
        .data
        or []
    )
    if not rows:
        raise HTTPException(status_code=404, detail="Live session not found")
    return {"status": "ended", "live_session": rows[0]}


@app.post("/api/live-sessions/{live_session_id}/delete")
def delete_live_session(live_session_id: str):
    now = datetime.now().isoformat()
    rows = (
        supabase.table(LIVE_SESSION_TABLE)
        .update(
            {
                "status": "deleted",
                "visibility": "deleted",
                "is_deleted": True,
                "deleted_at": now,
                "deleted_reason": "manual",
                "updated_at": now,
            }
        )
        .eq("id", live_session_id)
        .eq("is_deleted", False)
        .execute()
        .data
        or []
    )
    if not rows:
        return {
            "status": "deleted",
            "live_session_id": live_session_id,
            "already_deleted": True,
        }
    return {"status": "deleted", "live_session": rows[0]}


@app.get("/api/live-sessions/{live_session_id}/transcripts")
def list_live_session_transcripts(live_session_id: str, limit: int = 200, offset: int = 0):
    query = (
        supabase.table(LIVE_TRANSCRIPT_TABLE)
        .select("*")
        .eq("live_session_id", live_session_id)
        .order("chunk_index", desc=False)
        .order("created_at", desc=False)
        .range(offset, offset + limit - 1)
    )
    rows = query.execute().data or []
    return {"transcripts": rows, "count": len(rows)}


# --- Realtime Transcribe ---

@app.post("/api/transcribe/realtime")
async def transcribe_realtime(
    file: UploadFile = File(...),
    live_session_id: str = Form(...),
    chunk_index: int = Form(...),
):
    # Display-first mode can skip per-chunk DB validation to minimize latency.
    if REALTIME_TRANSCRIBE_PERSIST:
        session_rows = (
            supabase.table(LIVE_SESSION_TABLE)
            .select("id, status, is_deleted")
            .eq("id", live_session_id)
            .eq("is_deleted", False)
            .limit(1)
            .execute()
            .data
            or []
        )
        if not session_rows:
            raise HTTPException(status_code=404, detail="Live session not found")

    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        raise HTTPException(status_code=500, detail="OPENAI_API_KEY is not configured")

    try:
        from openai import OpenAI
    except ModuleNotFoundError as exc:
        raise HTTPException(status_code=500, detail="openai package is not installed") from exc

    content = await file.read()
    if not content:
        raise HTTPException(status_code=400, detail="Audio chunk is empty")

    client = OpenAI(api_key=api_key)
    model = REALTIME_TRANSCRIBE_MODEL
    request_kwargs: Dict[str, Any] = {
        "model": model,
        "file": (
            file.filename or f"chunk-{chunk_index}.webm",
            content,
            file.content_type or "audio/webm",
        ),
        "language": REALTIME_TRANSCRIBE_LANGUAGE,
        "temperature": REALTIME_TRANSCRIBE_TEMPERATURE,
    }
    if REALTIME_TRANSCRIBE_PROMPT:
        request_kwargs["prompt"] = REALTIME_TRANSCRIBE_PROMPT

    try:
        response = client.audio.transcriptions.create(**request_kwargs)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Realtime transcription failed: {exc}")

    text = getattr(response, "text", None) or ""
    language = getattr(response, "language", None)

    now = datetime.now().isoformat()
    if not REALTIME_TRANSCRIBE_PERSIST:
        return {
            "live_session_id": live_session_id,
            "chunk_index": int(chunk_index),
            "text": text,
            "persisted": False,
            "model": model,
            "language": language,
            "requested_language": REALTIME_TRANSCRIBE_LANGUAGE,
            "temperature": REALTIME_TRANSCRIBE_TEMPERATURE,
            "processed_at": now,
        }

    transcript_payload = {
        "id": str(uuid.uuid4()),
        "live_session_id": live_session_id,
        "chunk_index": int(chunk_index),
        "text": text,
        "provider": "openai",
        "model": model,
        "language": language,
        "requested_language": REALTIME_TRANSCRIBE_LANGUAGE,
        "metadata": {},
        "created_at": now,
        "updated_at": now,
    }

    stored_rows = (
        supabase.table(LIVE_TRANSCRIPT_TABLE)
        .upsert(transcript_payload, on_conflict="live_session_id,chunk_index")
        .execute()
        .data
        or []
    )
    supabase.table(LIVE_SESSION_TABLE).update({"updated_at": now}).eq("id", live_session_id).execute()

    return {
        "live_session_id": live_session_id,
        "chunk_index": int(chunk_index),
        "text": text,
        "persisted": True,
        "requested_language": REALTIME_TRANSCRIBE_LANGUAGE,
        "temperature": REALTIME_TRANSCRIBE_TEMPERATURE,
        "transcript": stored_rows[0] if stored_rows else transcript_payload,
    }


# --- Realtime Translate ---

@app.post("/api/translate/realtime")
async def translate_realtime(
    live_session_id: str = Form(...),
    chunk_index: int = Form(...),
    text: str = Form(...),
    target_language: str = Form("en"),
):
    source_text = (text or "").strip()
    if not source_text:
        raise HTTPException(status_code=400, detail="Text is empty")

    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        raise HTTPException(status_code=500, detail="OPENAI_API_KEY is not configured")

    try:
        from openai import OpenAI
    except ModuleNotFoundError as exc:
        raise HTTPException(status_code=500, detail="openai package is not installed") from exc

    client = OpenAI(api_key=api_key)
    model = REALTIME_TRANSLATE_MODEL

    system_prompt = (
        "You are a professional translator. Translate the user's Japanese text faithfully into the "
        "target language. Keep it concise and natural. Return only the translation with no commentary."
    )

    try:
        response = client.chat.completions.create(
            model=model,
            messages=[
                {"role": "system", "content": system_prompt},
                {
                    "role": "user",
                    "content": f"target_language: {target_language}\ntext: {source_text}",
                },
            ],
            temperature=0,
        )
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Realtime translation failed: {exc}")

    translated_text = ""
    try:
        content = response.choices[0].message.content
        if isinstance(content, str):
            translated_text = content.strip()
        elif isinstance(content, list):
            translated_text = "".join(
                part.get("text", "") if isinstance(part, dict) else str(part)
                for part in content
            ).strip()
    except Exception:
        translated_text = ""

    if not translated_text:
        raise HTTPException(status_code=500, detail="Realtime translation returned empty text")

    return {
        "live_session_id": live_session_id,
        "chunk_index": int(chunk_index),
        "source_text": source_text,
        "translated_text": translated_text,
        "target_language": target_language,
        "model": model,
        "processed_at": datetime.now().isoformat(),
    }


# --- Transcribe ---

@app.post("/api/transcribe/{session_id}", status_code=202)
def transcribe(
    session_id: str,
    auto_chain: bool = True,
    provider: Optional[str] = None,
    model: Optional[str] = None,
    language: Optional[str] = None
):
    """Start transcription (async). If auto_chain=True, auto-generates cards after."""
    # Get session
    result = supabase.table(TABLE)\
        .select("id, s3_audio_path, status")\
        .eq("id", session_id)\
        .single()\
        .execute()

    if not result.data:
        raise HTTPException(status_code=404, detail="Session not found")

    s3_audio_path = result.data.get("s3_audio_path")
    if not s3_audio_path:
        raise HTTPException(status_code=400, detail="No audio file found for session")

    # Get LLM service for auto-chain
    llm_service = get_current_llm() if auto_chain else None
    topic_llm_service = _get_topic_llm_service()

    # Resolve ASR provider/model and validate keys before starting the thread
    try:
        asr_service, resolved_provider, resolved_model, resolved_language = get_asr_service(
            provider,
            model,
            language
        )
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    # Run in background thread
    thread = threading.Thread(
        target=transcribe_background,
        kwargs={
            "session_id": session_id,
            "s3_audio_path": s3_audio_path,
            "s3_client": s3_client,
            "s3_bucket": S3_BUCKET,
            "supabase": supabase,
            "asr_service": asr_service,
            "auto_chain": auto_chain,
            "llm_service": llm_service,
            "topic_llm_service": topic_llm_service,
        }
    )
    thread.start()

    return {
        "session_id": session_id,
        "status": "transcribing",
        "auto_chain": auto_chain,
        "asr_provider": resolved_provider,
        "asr_model": resolved_model,
        "asr_language": resolved_language,
    }


# --- Generate Cards ---

@app.post("/api/generate-cards/{session_id}", status_code=202)
def generate_cards(session_id: str, body: GenerateCardsRequest = None):
    """Generate cards from transcription (async). Spot execution."""
    if body is None:
        body = GenerateCardsRequest()

    # Get session
    result = supabase.table(TABLE)\
        .select("id, transcription, status")\
        .eq("id", session_id)\
        .single()\
        .execute()

    if not result.data:
        raise HTTPException(status_code=404, detail="Session not found")

    if not result.data.get("transcription"):
        raise HTTPException(status_code=400, detail="Transcription not found. Run /api/transcribe first.")

    # Create LLM service
    if body.provider or body.model:
        provider = body.provider or "openai"
        llm_service = LLMFactory.create(provider, body.model)
    else:
        llm_service = get_current_llm()

    # Run in background
    thread = threading.Thread(
        target=generate_cards_background,
        kwargs={
            "session_id": session_id,
            "supabase": supabase,
            "llm_service": llm_service,
            "use_custom_prompt": body.use_custom_prompt or False,
        }
    )
    thread.start()

    return {"session_id": session_id, "status": "generating"}


# --- Topics ---

@app.get("/api/topics")
def list_topics(
    device_id: Optional[str] = None,
    workspace_id: Optional[str] = None,
    status: Optional[str] = None,
    limit: int = 20,
    offset: int = 0,
    include_children: bool = False,
):
    query = (
        supabase.table(TOPIC_TABLE)
        .select("*")
        .order("last_utterance_at", desc=True)
        .range(offset, offset + limit - 1)
    )

    if device_id:
        query = query.eq("device_id", device_id)
    if workspace_id:
        query = query.eq("workspace_id", workspace_id)
    if status:
        query = query.eq("topic_status", status)

    topics = query.execute().data or []

    if include_children and topics:
        topic_ids = [topic["id"] for topic in topics if topic.get("id")]
        child_rows: list[Dict[str, Any]] = []
        if topic_ids:
            child_rows = (
                supabase.table(TABLE)
                .select(
                    "id, topic_id, device_id, status, transcription, duration_seconds, "
                    "transcription_metadata, recorded_at, created_at, updated_at"
                )
                .in_("topic_id", topic_ids)
                .order("created_at", desc=False)
                .execute()
                .data
                or []
            )
        children_map: Dict[str, list[Dict[str, Any]]] = {}
        for row in child_rows:
            children_map.setdefault(row.get("topic_id"), []).append(row)
        for topic in topics:
            topic["utterances"] = children_map.get(topic.get("id"), [])

    return {"topics": topics, "count": len(topics)}


@app.get("/api/topics/{topic_id}")
def get_topic(topic_id: str):
    topic = (
        supabase.table(TOPIC_TABLE)
        .select("*")
        .eq("id", topic_id)
        .single()
        .execute()
        .data
    )
    if not topic:
        raise HTTPException(status_code=404, detail="Topic not found")

    utterances = (
        supabase.table(TABLE)
        .select(
            "id, topic_id, device_id, status, transcription, duration_seconds, "
            "s3_audio_path, transcription_metadata, recorded_at, created_at, updated_at"
        )
        .eq("topic_id", topic_id)
        .order("created_at", desc=False)
        .execute()
        .data
        or []
    )

    return {"topic": topic, "utterances": utterances, "count": len(utterances)}


@app.post("/api/topics/reconcile")
def reconcile_topic_groups(body: TopicReconcileRequest = None):
    if body is None:
        body = TopicReconcileRequest()

    result = reconcile_topics(
        supabase=supabase,
        llm_service=_get_topic_llm_service(),
        device_id=body.device_id,
        topic_id=body.topic_id,
        force_finalize=body.force_finalize,
    )
    return {"status": "ok", "result": result}


@app.post("/api/topics/{topic_id}/finalize")
def finalize_topic(topic_id: str):
    result = reconcile_topics(
        supabase=supabase,
        llm_service=_get_topic_llm_service(),
        topic_id=topic_id,
        force_finalize=True,
    )
    return {"status": "ok", "topic_id": topic_id, "result": result}


@app.post("/api/topics/backfill")
def backfill_topics(body: TopicBackfillRequest = None):
    if body is None:
        body = TopicBackfillRequest()
    safe_limit = max(1, min(body.limit, 1000))
    result = backfill_ungrouped_sessions(
        supabase=supabase,
        llm_service=_get_topic_llm_service(),
        device_id=body.device_id,
        limit=safe_limit,
    )
    return {"status": "ok", "result": result}


@app.post("/api/topics/evaluate-pending")
def evaluate_pending_topics(body: TopicEvaluatePendingRequest):
    safe_idle = max(10, min(body.idle_seconds, 3600))
    llm_service = resolve_device_llm_service(
        supabase=supabase,
        device_id=body.device_id,
        fallback_llm_service=_get_topic_llm_service(),
    )
    reason = (body.boundary_reason or "").strip().lower()
    if not reason:
        reason = "manual" if body.force else "idle_timeout"
    if reason not in {"idle_timeout", "ambient_stopped", "manual", "legacy_repair"}:
        reason = "manual" if body.force else "idle_timeout"

    result = finalize_active_topic_for_device(
        supabase=supabase,
        device_id=body.device_id,
        llm_service=llm_service,
        idle_seconds=safe_idle,
        force=body.force,
        boundary_reason=reason,
    )
    return {"status": "ok", "result": result}


# --- Distillation ---

@app.post("/api/distill/annotate/{topic_id}")
def distill_annotate(topic_id: str, body: DistillAnnotateRequest = None):
    if body is None:
        body = DistillAnnotateRequest()

    device_row = (
        supabase.table(TOPIC_TABLE)
        .select("device_id")
        .eq("id", topic_id)
        .single()
        .execute()
        .data
        or {}
    )
    device_id = device_row.get("device_id")
    llm_service = resolve_device_llm_service(
        supabase=supabase,
        device_id=device_id,
        fallback_llm_service=_get_topic_llm_service(),
    )

    result = annotate_topic(
        supabase=supabase,
        topic_id=topic_id,
        llm_service=llm_service,
        force=body.force,
    )
    return {"status": "ok", "result": result}


# --- Ownership ---

@app.get("/api/accounts")
def list_accounts():
    rows = (
        supabase.table(ACCOUNT_TABLE)
        .select("*")
        .order("created_at", desc=False)
        .execute()
        .data
        or []
    )
    return {"accounts": rows, "count": len(rows)}


@app.post("/api/accounts")
def create_account(body: AccountCreateRequest):
    payload = {
        "display_name": body.display_name.strip(),
        "email": (body.email or "").strip() or None,
        "external_auth_provider": (body.external_auth_provider or "").strip() or None,
        "external_auth_subject": (body.external_auth_subject or "").strip() or None,
        "avatar_url": (body.avatar_url or "").strip() or None,
        "created_at": datetime.now().isoformat(),
        "updated_at": datetime.now().isoformat(),
    }
    rows = supabase.table(ACCOUNT_TABLE).insert(payload).execute().data or []
    return {"status": "ok", "account": rows[0] if rows else payload}


@app.patch("/api/accounts/{account_id}")
def update_account(account_id: str, body: AccountUpdateRequest):
    now_iso = datetime.now().isoformat()
    payload: Dict[str, Any] = {"updated_at": now_iso}

    if body.display_name is not None:
        name = body.display_name.strip()
        if not name:
            raise HTTPException(status_code=400, detail="display_name must not be empty")
        payload["display_name"] = name

    if body.avatar_url is not None:
        payload["avatar_url"] = (body.avatar_url or "").strip() or None

    rows = (
        supabase.table(ACCOUNT_TABLE)
        .update(payload)
        .eq("id", account_id)
        .execute()
        .data
        or []
    )
    if not rows:
        raise HTTPException(status_code=404, detail="Account not found")
    return {"status": "ok", "account": rows[0]}


@app.get("/api/organizations")
def list_organizations(account_id: Optional[str] = None):
    if account_id:
        org_ids = _organization_ids_for_account(account_id)
        if not org_ids:
            return {"organizations": [], "count": 0}
        rows = (
            supabase.table(ORGANIZATION_TABLE)
            .select("*")
            .in_("id", org_ids)
            .order("created_at", desc=False)
            .execute()
            .data
            or []
        )
        return {"organizations": rows, "count": len(rows)}

    rows = (
        supabase.table(ORGANIZATION_TABLE)
        .select("*")
        .order("created_at", desc=False)
        .execute()
        .data
        or []
    )
    return {"organizations": rows, "count": len(rows)}


@app.post("/api/organizations")
def create_organization(body: OrganizationCreateRequest):
    now_iso = datetime.now().isoformat()
    payload = {
        "name": body.name.strip(),
        "slug": (body.slug or "").strip() or None,
        "created_by": (body.created_by or "").strip() or None,
        "created_at": now_iso,
        "updated_at": now_iso,
    }
    rows = supabase.table(ORGANIZATION_TABLE).insert(payload).execute().data or []
    org = rows[0] if rows else None
    if not org:
        raise HTTPException(status_code=500, detail="Organization creation failed")

    if body.created_by:
        member_payload = {
            "organization_id": org["id"],
            "account_id": body.created_by,
            "role": "org_admin",
            "created_at": now_iso,
            "updated_at": now_iso,
        }
        supabase.table(ORGANIZATION_MEMBER_TABLE).upsert(
            member_payload,
            on_conflict="organization_id,account_id",
        ).execute()

    return {"status": "ok", "organization": org}


@app.patch("/api/organizations/{organization_id}")
def update_organization(organization_id: str, body: OrganizationUpdateRequest):
    now_iso = datetime.now().isoformat()
    payload: Dict[str, Any] = {"updated_at": now_iso}
    if body.name is not None:
        name = body.name.strip()
        if not name:
            raise HTTPException(status_code=400, detail="name must not be empty")
        payload["name"] = name
    if body.slug is not None:
        payload["slug"] = (body.slug or "").strip() or None

    rows = (
        supabase.table(ORGANIZATION_TABLE)
        .update(payload)
        .eq("id", organization_id)
        .execute()
        .data
        or []
    )
    if not rows:
        raise HTTPException(status_code=404, detail="Organization not found")
    return {"status": "ok", "organization": rows[0]}


@app.get("/api/organizations/{organization_id}/members")
def list_organization_members(organization_id: str):
    rows = (
        supabase.table(ORGANIZATION_MEMBER_TABLE)
        .select("*, account:zerotouch_accounts(id, display_name, email, avatar_url)")
        .eq("organization_id", organization_id)
        .order("created_at", desc=False)
        .execute()
        .data
        or []
    )
    return {"members": rows, "count": len(rows)}


@app.post("/api/organizations/{organization_id}/members")
def upsert_organization_member(
    organization_id: str,
    body: OrganizationMemberUpsertRequest,
):
    allowed_roles = {"org_admin", "org_member"}
    role = (body.role or "").strip() or "org_member"
    if role not in allowed_roles:
        raise HTTPException(
            status_code=400,
            detail=f"role must be one of {sorted(allowed_roles)}",
        )

    org_exists = (
        supabase.table(ORGANIZATION_TABLE)
        .select("id")
        .eq("id", organization_id)
        .limit(1)
        .execute()
        .data
    )
    if not org_exists:
        raise HTTPException(status_code=404, detail="Organization not found")

    account_exists = (
        supabase.table(ACCOUNT_TABLE)
        .select("id")
        .eq("id", body.account_id)
        .limit(1)
        .execute()
        .data
    )
    if not account_exists:
        raise HTTPException(status_code=404, detail="Account not found")

    now_iso = datetime.now().isoformat()
    payload = {
        "organization_id": organization_id,
        "account_id": body.account_id,
        "role": role,
        "created_at": now_iso,
        "updated_at": now_iso,
    }
    rows = (
        supabase.table(ORGANIZATION_MEMBER_TABLE)
        .upsert(payload, on_conflict="organization_id,account_id")
        .execute()
        .data
        or []
    )
    return {"status": "ok", "member": rows[0] if rows else payload}


@app.delete("/api/organizations/{organization_id}/members/{account_id}")
def remove_organization_member(organization_id: str, account_id: str):
    rows = (
        supabase.table(ORGANIZATION_MEMBER_TABLE)
        .delete()
        .eq("organization_id", organization_id)
        .eq("account_id", account_id)
        .execute()
        .data
        or []
    )
    if not rows:
        raise HTTPException(status_code=404, detail="Membership not found")
    return {"status": "ok", "removed": len(rows)}


@app.get("/api/workspaces/{workspace_id}/members")
def list_workspace_members(workspace_id: str):
    rows = (
        supabase.table(WORKSPACE_MEMBER_TABLE)
        .select("*, account:zerotouch_accounts(id, display_name, email, avatar_url)")
        .eq("workspace_id", workspace_id)
        .order("created_at", desc=False)
        .execute()
        .data
        or []
    )
    return {"members": rows, "count": len(rows)}


@app.post("/api/workspaces/{workspace_id}/members")
def upsert_workspace_member(
    workspace_id: str,
    body: WorkspaceMemberUpsertRequest,
):
    allowed_roles = {"admin", "editor", "viewer"}
    role = (body.role or "").strip() or "editor"
    if role not in allowed_roles:
        raise HTTPException(
            status_code=400,
            detail=f"role must be one of {sorted(allowed_roles)}",
        )

    ws_exists = (
        supabase.table(WORKSPACE_TABLE)
        .select("id")
        .eq("id", workspace_id)
        .limit(1)
        .execute()
        .data
    )
    if not ws_exists:
        raise HTTPException(status_code=404, detail="Workspace not found")

    account_exists = (
        supabase.table(ACCOUNT_TABLE)
        .select("id")
        .eq("id", body.account_id)
        .limit(1)
        .execute()
        .data
    )
    if not account_exists:
        raise HTTPException(status_code=404, detail="Account not found")

    now_iso = datetime.now().isoformat()
    payload = {
        "workspace_id": workspace_id,
        "account_id": body.account_id,
        "role": role,
        "created_at": now_iso,
        "updated_at": now_iso,
    }
    rows = (
        supabase.table(WORKSPACE_MEMBER_TABLE)
        .upsert(payload, on_conflict="workspace_id,account_id")
        .execute()
        .data
        or []
    )
    return {"status": "ok", "member": rows[0] if rows else payload}


@app.delete("/api/workspaces/{workspace_id}/members/{account_id}")
def remove_workspace_member(workspace_id: str, account_id: str):
    rows = (
        supabase.table(WORKSPACE_MEMBER_TABLE)
        .delete()
        .eq("workspace_id", workspace_id)
        .eq("account_id", account_id)
        .execute()
        .data
        or []
    )
    if not rows:
        raise HTTPException(status_code=404, detail="Membership not found")
    return {"status": "ok", "removed": len(rows)}


@app.get("/api/workspaces")
def list_workspaces(account_id: Optional[str] = None):
    if account_id:
        workspace_ids = _workspace_ids_for_account(account_id)
        if not workspace_ids:
            return {"workspaces": [], "count": 0}
        rows = (
            supabase.table(WORKSPACE_TABLE)
            .select("*")
            .in_("id", workspace_ids)
            .order("created_at", desc=False)
            .execute()
            .data
            or []
        )
        return {"workspaces": rows, "count": len(rows)}

    rows = (
        supabase.table(WORKSPACE_TABLE)
        .select("*")
        .order("created_at", desc=False)
        .execute()
        .data
        or []
    )
    return {"workspaces": rows, "count": len(rows)}


@app.post("/api/workspaces")
def create_workspace(body: WorkspaceCreateRequest):
    now_iso = datetime.now().isoformat()

    # Resolve organization_id. If not provided, fall back to the owner's first org.
    organization_id = (body.organization_id or "").strip() or None
    if not organization_id and body.owner_account_id:
        org_ids = _organization_ids_for_account(body.owner_account_id)
        if org_ids:
            organization_id = org_ids[0]
    if not organization_id:
        raise HTTPException(
            status_code=400,
            detail="organization_id is required (owner has no organization membership)",
        )

    payload = {
        "organization_id": organization_id,
        "owner_account_id": body.owner_account_id,
        "name": body.name.strip(),
        "slug": (body.slug or "").strip() or None,
        "description": (body.description or "").strip() or None,
        "created_at": now_iso,
        "updated_at": now_iso,
    }
    rows = supabase.table(WORKSPACE_TABLE).insert(payload).execute().data or []
    workspace = rows[0] if rows else None
    if not workspace:
        raise HTTPException(status_code=500, detail="Workspace creation failed")

    if body.owner_account_id:
        member_payload = {
            "workspace_id": workspace["id"],
            "account_id": body.owner_account_id,
            "role": "admin",
            "created_at": now_iso,
            "updated_at": now_iso,
        }
        supabase.table(WORKSPACE_MEMBER_TABLE).upsert(
            member_payload,
            on_conflict="workspace_id,account_id",
        ).execute()

    return {"status": "ok", "workspace": workspace}


@app.patch("/api/workspaces/{workspace_id}")
def update_workspace(workspace_id: str, body: WorkspaceUpdateRequest):
    now_iso = datetime.now().isoformat()
    payload: Dict[str, Any] = {"updated_at": now_iso}

    if body.name is not None:
        name = body.name.strip()
        if not name:
            raise HTTPException(status_code=400, detail="name must not be empty")
        payload["name"] = name

    if body.slug is not None:
        payload["slug"] = (body.slug or "").strip() or None

    if body.description is not None:
        payload["description"] = (body.description or "").strip() or None

    rows = (
        supabase.table(WORKSPACE_TABLE)
        .update(payload)
        .eq("id", workspace_id)
        .execute()
        .data
        or []
    )
    if not rows:
        raise HTTPException(status_code=404, detail="Workspace not found")
    return {"status": "ok", "workspace": rows[0]}


@app.get("/api/devices")
def list_devices(
    workspace_id: Optional[str] = None,
    account_id: Optional[str] = None,
):
    query = supabase.table(DEVICE_TABLE).select("*").order("created_at", desc=False)
    if workspace_id:
        query = query.eq("workspace_id", workspace_id)
    elif account_id:
        workspace_ids = _workspace_ids_for_account(account_id)
        if not workspace_ids:
            return {"devices": [], "count": 0}
        query = query.in_("workspace_id", workspace_ids)

    rows = query.execute().data or []
    return {"devices": rows, "count": len(rows)}


@app.post("/api/devices")
def create_device(body: DeviceCreateRequest):
    payload = {
        "workspace_id": body.workspace_id,
        "device_id": body.device_id.strip(),
        "display_name": body.display_name.strip(),
        "device_kind": body.device_kind,
        "source_type": body.source_type,
        "platform": (body.platform or "").strip() or None,
        "context_note": (body.context_note or "").strip() or None,
        "is_virtual": body.is_virtual,
        "is_active": body.is_active,
        "created_at": datetime.now().isoformat(),
        "updated_at": datetime.now().isoformat(),
    }
    rows = supabase.table(DEVICE_TABLE).insert(payload).execute().data or []
    return {"status": "ok", "device": rows[0] if rows else payload}


@app.patch("/api/devices/{device_row_id}")
def update_device(device_row_id: str, body: DeviceUpdateRequest):
    now_iso = datetime.now().isoformat()
    payload: Dict[str, Any] = {"updated_at": now_iso}

    if body.display_name is not None:
        display_name = body.display_name.strip()
        if not display_name:
            raise HTTPException(status_code=400, detail="display_name must not be empty")
        payload["display_name"] = display_name

    if body.context_note is not None:
        payload["context_note"] = (body.context_note or "").strip() or None

    if body.is_active is not None:
        payload["is_active"] = body.is_active

    rows = (
        supabase.table(DEVICE_TABLE)
        .update(payload)
        .eq("id", device_row_id)
        .execute()
        .data
        or []
    )
    if not rows:
        raise HTTPException(status_code=404, detail="Device not found")
    return {"status": "ok", "device": rows[0]}


@app.get("/api/context-profiles/{workspace_id}")
def get_context_profile(workspace_id: str):
    rows = (
        supabase.table(CONTEXT_PROFILE_TABLE)
        .select("*")
        .eq("workspace_id", workspace_id)
        .limit(1)
        .execute()
        .data
        or []
    )
    profile = rows[0] if rows else None
    if not profile:
        return {"workspace_id": workspace_id, "profile": None}
    return {"workspace_id": workspace_id, "profile": _normalize_context_profile_record(profile)}


@app.post("/api/context-profiles/{workspace_id}")
def upsert_context_profile(workspace_id: str, body: ContextProfileRequest):
    payload = _build_context_profile_payload(workspace_id=workspace_id, body=body)
    saved = _upsert_context_profile_compat(payload)
    return {"status": "ok", "profile": _normalize_context_profile_record(saved)}


@app.get("/api/device-settings/{device_id}")
def get_device_settings(device_id: str):
    rows = (
        supabase.table(DEVICE_SETTINGS_TABLE)
        .select("*")
        .eq("device_id", device_id)
        .limit(1)
        .execute()
        .data
        or []
    )
    row = rows[0] if rows else None
    if not row:
        return {"device_id": device_id, "llm_provider": None, "llm_model": None}
    return row


# --- Facts ---

@app.get("/api/facts")
def list_facts(
    device_id: Optional[str] = None,
    workspace_id: Optional[str] = None,
    topic_id: Optional[str] = None,
    limit: int = 50,
    offset: int = 0,
):
    query = (
        supabase.table(FACT_TABLE)
        .select("*")
        .order("created_at", desc=True)
        .range(offset, offset + limit - 1)
    )
    if device_id:
        query = query.eq("device_id", device_id)
    if workspace_id:
        query = query.eq("workspace_id", workspace_id)
    if topic_id:
        query = query.eq("topic_id", topic_id)

    facts = query.execute().data or []
    return {"facts": facts, "count": len(facts)}


@app.get("/api/facts/{fact_id}")
def get_fact(fact_id: str):
    fact = (
        supabase.table(FACT_TABLE)
        .select("*")
        .eq("id", fact_id)
        .single()
        .execute()
        .data
    )
    if not fact:
        raise HTTPException(status_code=404, detail="Fact not found")
    return {"fact": fact}


@app.post("/api/device-settings/{device_id}")
def update_device_settings(device_id: str, body: DeviceSettingsRequest):
    payload: Dict[str, Any] = {
        "device_id": device_id,
        "workspace_id": _resolve_workspace_id(device_id=device_id),
        "updated_at": datetime.now().isoformat(),
    }
    if body.llm_provider is not None:
        payload["llm_provider"] = body.llm_provider
    if body.llm_model is not None:
        payload["llm_model"] = body.llm_model

    row = (
        supabase.table(DEVICE_SETTINGS_TABLE)
        .upsert(payload)
        .execute()
        .data
    )
    return {"status": "ok", "settings": row[0] if row else payload}


# --- Sessions ---

@app.get("/api/sessions/{session_id}")
def get_session(session_id: str):
    """Get session by ID."""
    result = supabase.table(TABLE)\
        .select("*")\
        .eq("id", session_id)\
        .single()\
        .execute()

    if not result.data:
        raise HTTPException(status_code=404, detail="Session not found")

    return result.data


@app.delete("/api/sessions/{session_id}")
def delete_session(session_id: str):
    """Delete a session card and clean up related storage/topic state."""
    rows = (
        supabase.table(TABLE)
        .select("id, topic_id, s3_audio_path")
        .eq("id", session_id)
        .limit(1)
        .execute()
        .data
        or []
    )

    row = rows[0] if rows else None
    if not row:
        return {
            "status": "deleted",
            "session_id": session_id,
            "already_deleted": True,
            "topic_deleted": False,
            "topic_updated": False,
        }

    topic_id = row.get("topic_id")
    s3_audio_path = row.get("s3_audio_path")

    if s3_audio_path:
        try:
            s3_client.delete_object(Bucket=S3_BUCKET, Key=s3_audio_path)
        except Exception as exc:
            print(f"[ZeroTouch] Failed to delete S3 audio: session={session_id} key={s3_audio_path} error={exc}")

    supabase.table(TABLE).delete().eq("id", session_id).execute()
    topic_cleanup = _cleanup_topic_after_session_delete(topic_id)

    return {
        "status": "deleted",
        "session_id": session_id,
        "topic_id": topic_id,
        **topic_cleanup,
    }


@app.get("/api/sessions")
def list_sessions(
    device_id: Optional[str] = None,
    workspace_id: Optional[str] = None,
    status: Optional[str] = None,
    limit: int = 20,
    offset: int = 0
):
    """List sessions with optional filters."""
    query = supabase.table(TABLE)\
        .select("id, device_id, workspace_id, status, duration_seconds, model_used, error_message, recorded_at, created_at, updated_at")\
        .order("created_at", desc=True)\
        .range(offset, offset + limit - 1)

    if device_id:
        query = query.eq("device_id", device_id)
    if workspace_id:
        query = query.eq("workspace_id", workspace_id)
    if status:
        query = query.eq("status", status)

    result = query.execute()
    return {"sessions": result.data or [], "count": len(result.data or [])}


# --- Wiki Ingest ---

class WikiIngestRequest(BaseModel):
    device_id: Optional[str] = None
    provider: Optional[str] = None
    model: Optional[str] = None
    min_importance: int = 3


@app.post("/api/ingest-wiki")
def run_wiki_ingest(request: WikiIngestRequest):
    """
    Phase 3: Ingest Facts into wiki pages for a device.
    Reads all Facts (Lv.min_importance+), calls LLM to generate/update wiki pages,
    and upserts the result into zerotouch_wiki_pages.
    """
    device_id = request.device_id or os.getenv("ZEROTOUCH_DEVICE_ID", "amical-db-test")

    if request.provider and request.model:
        llm_service = LLMFactory.create(provider=request.provider, model=request.model)
    else:
        llm_service = get_current_llm()

    result = ingest_wiki(
        supabase=supabase,
        device_id=device_id,
        llm_service=llm_service,
        min_importance=request.min_importance,
    )
    return result


# --- Wiki Query ---

class WikiQueryRequest(BaseModel):
    device_id: Optional[str] = None
    question: str
    provider: Optional[str] = None
    model: Optional[str] = None
    max_pages: int = 5


@app.post("/api/query-wiki")
def run_wiki_query(request: WikiQueryRequest):
    """
    Run a natural-language query against the wiki for a device.
    Uses the wiki index for page selection, answers with selected pages,
    and files the outcome back via zerotouch_wiki_log (and a new query_answer
    page when outcome='synthesis').
    """
    device_id = request.device_id or os.getenv("ZEROTOUCH_DEVICE_ID", "amical-db-test")

    if request.provider and request.model:
        llm_service = LLMFactory.create(provider=request.provider, model=request.model)
    else:
        llm_service = get_current_llm()

    result = query_wiki(
        supabase=supabase,
        device_id=device_id,
        question=request.question,
        llm_service=llm_service,
        max_pages=request.max_pages,
    )
    return result


# --- Wiki Lint ---

class WikiLintRequest(BaseModel):
    device_id: Optional[str] = None
    provider: Optional[str] = None
    model: Optional[str] = None


@app.post("/api/lint-wiki")
def run_wiki_lint(request: WikiLintRequest):
    """
    Run Wiki Lint for a device: detect orphan pages and summarize recurring data gaps.
    Results are written to zerotouch_wiki_log only (operation='lint').
    Wiki pages are never modified.
    """
    device_id = request.device_id or os.getenv("ZEROTOUCH_DEVICE_ID", "amical-db-test")
    if request.provider and request.model:
        llm_service = LLMFactory.create(provider=request.provider, model=request.model)
    else:
        llm_service = get_current_llm()
    result = lint_wiki(supabase=supabase, device_id=device_id, llm_service=llm_service)
    return result


@app.get("/api/lint-results")
def get_lint_results(device_id: Optional[str] = None, limit: int = 50):
    """Return recent lint log entries for a device from zerotouch_wiki_log."""
    device_id = device_id or os.getenv("ZEROTOUCH_DEVICE_ID", "amical-db-test")
    result = (
        supabase.table("zerotouch_wiki_log")
        .select("*")
        .eq("device_id", device_id)
        .eq("operation", "lint")
        .order("created_at", desc=True)
        .limit(limit)
        .execute()
    )
    return {"device_id": device_id, "entries": result.data or []}


@app.get("/api/wiki-log")
def get_wiki_log(
    device_id: Optional[str] = None,
    operation: Optional[str] = None,
    limit: int = 50,
):
    """Return recent entries from zerotouch_wiki_log for a device, optionally filtered by operation."""
    device_id = device_id or os.getenv("ZEROTOUCH_DEVICE_ID", "amical-db-test")
    query = (
        supabase.table("zerotouch_wiki_log")
        .select("*")
        .eq("device_id", device_id)
        .order("created_at", desc=True)
        .limit(limit)
    )
    if operation:
        query = query.eq("operation", operation)
    result = query.execute()
    return {"device_id": device_id, "entries": result.data or []}


# --- Wiki Pages ---

@app.get("/api/wiki-pages")
def get_wiki_pages(device_id: Optional[str] = None, limit: int = 500):
    """Return wiki pages for a device, enriched with project info."""
    resolved_device_id = device_id or os.getenv("ZEROTOUCH_DEVICE_ID", "amical-db-test")

    wiki_result = (
        supabase.table("zerotouch_wiki_pages")
        .select(
            "id, title, body, project_id, category, page_key, kind, status, version, "
            "source_fact_ids, last_ingest_at, created_at, updated_at"
        )
        .eq("device_id", resolved_device_id)
        .order("updated_at", desc=True)
        .limit(limit)
        .execute()
    )
    wiki_rows = wiki_result.data or []

    project_ids = list({row["project_id"] for row in wiki_rows if row.get("project_id")})
    projects_by_id: dict = {}

    if project_ids:
        project_result = (
            supabase.table("zerotouch_workspace_projects")
            .select("id, project_key, display_name")
            .in_("id", project_ids)
            .execute()
        )
        for proj in (project_result.data or []):
            projects_by_id[proj["id"]] = proj

    pages = []
    for row in wiki_rows:
        proj = projects_by_id.get(row.get("project_id")) if row.get("project_id") else None
        pages.append({
            **row,
            "project_key": proj["project_key"] if proj else None,
            "project_name": proj["display_name"] if proj else None,
        })

    return {"device_id": resolved_device_id, "pages": pages, "wiki_available": True}


# --- Action Candidates ---

class ActionConvertRequest(BaseModel):
    provider: Optional[str] = None
    model: Optional[str] = None
    force: bool = False
    domain: str = "knowledge_worker"


class ActionReviewRequest(BaseModel):
    action: str  # approve | reject | edit
    edits: Optional[Dict[str, Any]] = None
    notes: Optional[str] = None
    reviewer: Optional[str] = None


@app.post("/api/action-candidates/from-topic/{topic_id}")
def convert_topic_to_action_candidates(topic_id: str, body: Optional[ActionConvertRequest] = None):
    """
    Slice 1: Generate Action Candidates for a finalized topic.
    Currently emits email_draft candidates only. Manual trigger.
    """
    request = body or ActionConvertRequest()

    device_row = (
        supabase.table(TOPIC_TABLE)
        .select("device_id")
        .eq("id", topic_id)
        .single()
        .execute()
        .data
        or {}
    )
    device_id = device_row.get("device_id")

    if request.provider and request.model:
        llm_service = LLMFactory.create(provider=request.provider, model=request.model)
    else:
        llm_service = resolve_device_llm_service(
            supabase=supabase,
            device_id=device_id,
            fallback_llm_service=_get_topic_llm_service(),
        )

    result = run_action_converter(
        supabase=supabase,
        topic_id=topic_id,
        llm_service=llm_service,
        force=request.force,
        domain=request.domain,
    )
    return {"status": "ok", "result": result}


@app.get("/api/action-candidates")
def get_action_candidates(
    device_id: Optional[str] = None,
    topic_id: Optional[str] = None,
    status: Optional[str] = None,
    intent_type: Optional[str] = None,
    limit: int = 50,
):
    """List Action Candidates filtered by device / topic / status / intent."""
    if topic_id is not None:
        try:
            uuid.UUID(topic_id)
        except ValueError:
            return {"candidates": [], "count": 0}
    rows = list_action_candidates(
        supabase=supabase,
        device_id=device_id,
        topic_id=topic_id,
        status=status,
        intent_type=intent_type,
        limit=limit,
    )
    return {"candidates": rows, "count": len(rows)}


@app.get("/api/action-candidates/{candidate_id}")
def get_action_candidate(candidate_id: str):
    row = (
        supabase.table("zerotouch_action_candidates")
        .select("*")
        .eq("id", candidate_id)
        .single()
        .execute()
        .data
    )
    if not row:
        raise HTTPException(status_code=404, detail="action candidate not found")
    return row


@app.post("/api/action-candidates/{candidate_id}/review")
def review_action_candidate_endpoint(candidate_id: str, body: ActionReviewRequest):
    result = review_action_candidate(
        supabase=supabase,
        candidate_id=candidate_id,
        action=body.action,
        edits=body.edits,
        notes=body.notes,
        reviewer=body.reviewer,
    )
    if not result.get("ok"):
        raise HTTPException(status_code=400, detail=result.get("reason") or "review_failed")
    return result


# --- Model Catalog ---

@app.get("/api/models")
def get_models():
    """Get available LLM models."""
    return get_model_catalog()


# --- Run ---

if __name__ == "__main__":
    import uvicorn
    port = int(os.getenv("PORT", "8061"))
    uvicorn.run(app, host="0.0.0.0", port=port)
