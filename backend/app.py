"""
ZeroTouch Backend API

FastAPI application for ambient voice capture -> transcription -> card generation.
"""

import os
import uuid
import threading
from datetime import datetime
from contextlib import asynccontextmanager

import boto3
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Any, Dict, Optional
from supabase import create_client, Client

from services.llm_providers import LLMFactory, get_current_llm
from services.llm_models import get_model_catalog
from services.asr_providers import get_asr_service
from services.background_tasks import transcribe_background, generate_cards_background
from services.topic_manager import backfill_ungrouped_sessions, reconcile_topics


# --- Configuration ---

SUPABASE_URL = os.getenv("SUPABASE_URL")
SUPABASE_SERVICE_ROLE_KEY = os.getenv("SUPABASE_SERVICE_ROLE_KEY")
S3_BUCKET = os.getenv("S3_BUCKET", "watchme-vault")
AWS_REGION = os.getenv("AWS_REGION", "ap-southeast-2")
TABLE = "zerotouch_sessions"
TOPIC_TABLE = "conversation_topics"


# --- Globals ---

supabase: Client = None
s3_client = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global supabase, s3_client

    # Startup
    if not SUPABASE_URL or not SUPABASE_SERVICE_ROLE_KEY:
        raise RuntimeError("SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY must be set")

    supabase = create_client(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY)
    s3_client = boto3.client("s3", region_name=AWS_REGION)

    print(f"[ZeroTouch] API started - Supabase: {SUPABASE_URL[:30]}...")
    print(f"[ZeroTouch] S3 bucket: {S3_BUCKET}, Region: {AWS_REGION}")

    yield

    # Shutdown
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


# --- Upload ---

@app.post("/api/upload")
async def upload_audio(
    file: UploadFile = File(...),
    device_id: str = Form(...),
    recorded_at: Optional[str] = Form(None)
):
    """Upload audio file to S3 and create session."""
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
        session_data = {
            "id": session_id,
            "device_id": device_id,
            "s3_audio_path": s3_key,
            "status": "uploaded",
            "recorded_at": recorded_at or now.isoformat(),
            "created_at": now.isoformat(),
            "updated_at": now.isoformat(),
        }
        supabase.table(TABLE).insert(session_data).execute()

        return {"session_id": session_id, "s3_path": s3_key, "status": "uploaded"}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Upload failed: {str(e)}")


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
                    "recorded_at, created_at, updated_at"
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


@app.get("/api/sessions")
def list_sessions(
    device_id: Optional[str] = None,
    status: Optional[str] = None,
    limit: int = 20,
    offset: int = 0
):
    """List sessions with optional filters."""
    query = supabase.table(TABLE)\
        .select("id, device_id, status, duration_seconds, model_used, error_message, recorded_at, created_at, updated_at")\
        .order("created_at", desc=True)\
        .range(offset, offset + limit - 1)

    if device_id:
        query = query.eq("device_id", device_id)
    if status:
        query = query.eq("status", status)

    result = query.execute()
    return {"sessions": result.data or [], "count": len(result.data or [])}


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
