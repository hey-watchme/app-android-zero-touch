"""
Background tasks for ZeroTouch pipeline

transcribe_background: Audio -> STT (ASR provider)
generate_cards_background: Transcription -> Cards (LLM)
"""

import os
import io
import json
import time
import asyncio
from datetime import datetime
import boto3
from supabase import Client
from services.prompts import build_card_generation_prompt


TABLE = "zerotouch_sessions"


def _format_llm_error(error: Exception) -> str:
    """Convert provider exceptions into user-facing messages."""
    message = str(error)
    lower = message.lower()

    if any(k in lower for k in ['quota', 'rate limit', 'rate_limit', '429', 'insufficient_quota']):
        return f"LLM quota exceeded: {message}"
    if any(k in lower for k in ['not found', 'invalid model', '404', 'unsupported model']):
        return f"LLM model error: {message}"
    return f"LLM generation failed: {message}"


def transcribe_background(
    session_id: str,
    s3_audio_path: str,
    s3_client,
    s3_bucket: str,
    supabase: Client,
    asr_service,
    auto_chain: bool = True,
    llm_service=None
):
    """
    Background: download audio from S3, transcribe with ASR provider.
    If auto_chain=True and llm_service provided, auto-chains to card generation.
    """
    try:
        print(f"[Background] Starting transcription for session: {session_id}")
        start_time = time.time()

        supabase.table(TABLE).update({
            'status': 'transcribing',
            'error_message': None,
            'updated_at': datetime.now().isoformat()
        }).eq('id', session_id).execute()

        # Download audio from S3
        s3_response = s3_client.get_object(Bucket=s3_bucket, Key=s3_audio_path)
        audio_content = s3_response['Body'].read()
        audio_file = io.BytesIO(audio_content)

        # Transcribe
        transcription_result = asyncio.run(
            asr_service.transcribe_audio(
                audio_file=audio_file,
                filename=s3_audio_path
            )
        )

        # Calculate duration from utterances
        duration_seconds = 0
        utterances = transcription_result.get('utterances', [])
        if utterances:
            last = utterances[-1]
            duration_seconds = int(last.get('end', 0))

        supabase.table(TABLE).update({
            'transcription': transcription_result['transcription'],
            'transcription_metadata': {
                'utterances': transcription_result.get('utterances', []),
                'paragraphs': transcription_result.get('paragraphs', []),
                'speaker_count': transcription_result.get('speaker_count', 0),
                'confidence': transcription_result.get('confidence', 0.0),
                'word_count': transcription_result.get('word_count', 0),
                'provider': transcription_result.get('provider', 'unknown'),
                'model': transcription_result.get('model', 'unknown'),
                'processing_time': transcription_result.get('processing_time', 0.0),
            },
            'duration_seconds': duration_seconds,
            'status': 'transcribed',
            'updated_at': datetime.now().isoformat()
        }).eq('id', session_id).execute()

        processing_time = time.time() - start_time
        print(f"[Background] Transcription completed in {processing_time:.2f}s for session: {session_id}")

        # Auto-chain to card generation
        if auto_chain and llm_service:
            print(f"[Background] Auto-chaining card generation for session: {session_id}")
            generate_cards_background(
                session_id=session_id,
                supabase=supabase,
                llm_service=llm_service
            )

    except Exception as e:
        print(f"[Background] ERROR in transcription: {str(e)}")
        if supabase:
            supabase.table(TABLE).update({
                'status': 'failed',
                'error_message': f"Transcription failed: {str(e)}",
                'updated_at': datetime.now().isoformat()
            }).eq('id', session_id).execute()


def generate_cards_background(
    session_id: str,
    supabase: Client,
    llm_service,
    use_custom_prompt: bool = False
):
    """
    Background: generate cards from transcription using LLM.
    """
    try:
        print(f"[Background] Starting card generation for session: {session_id}")
        start_time = time.time()

        supabase.table(TABLE).update({
            'status': 'generating',
            'error_message': None,
            'updated_at': datetime.now().isoformat()
        }).eq('id', session_id).execute()

        # Get transcription from DB
        result = supabase.table(TABLE)\
            .select('transcription, cards_prompt')\
            .eq('id', session_id)\
            .single()\
            .execute()

        if not result.data:
            raise ValueError(f"Session not found: {session_id}")

        transcription = result.data.get('transcription')
        if not transcription:
            raise ValueError("Transcription not found")

        # Build or use stored prompt
        if use_custom_prompt:
            prompt = result.data.get('cards_prompt')
            if not prompt:
                raise ValueError("No stored prompt found")
            print(f"[Background] Using stored prompt (first 80 chars): {prompt[:80]}")
        else:
            prompt = build_card_generation_prompt(transcription)
            print(f"[Background] Generated new prompt (first 80 chars): {prompt[:80]}")

        # Save prompt
        supabase.table(TABLE).update({
            'cards_prompt': prompt
        }).eq('id', session_id).execute()

        # Call LLM
        try:
            llm_output = llm_service.generate(prompt)
            if not llm_output:
                raise ValueError("LLM returned empty response")
        except Exception as e:
            raise ValueError(_format_llm_error(e))

        # Parse JSON
        try:
            if llm_output.strip().startswith('{'):
                cards_data = json.loads(llm_output)
            else:
                cards_data = {'summary': llm_output}
        except json.JSONDecodeError:
            cards_data = {'summary': llm_output}

        # Save result
        update_data = {
            'cards_result': cards_data,
            'status': 'completed',
            'error_message': None,
            'updated_at': datetime.now().isoformat()
        }
        if hasattr(llm_service, 'model_name'):
            update_data['model_used'] = llm_service.model_name

        supabase.table(TABLE).update(update_data).eq('id', session_id).execute()

        processing_time = time.time() - start_time
        print(f"[Background] Card generation completed in {processing_time:.2f}s for session: {session_id}")

    except Exception as e:
        print(f"[Background] ERROR in card generation: {str(e)}")
        if supabase:
            supabase.table(TABLE).update({
                'status': 'failed',
                'error_message': f"Card generation failed: {str(e)}",
                'updated_at': datetime.now().isoformat()
            }).eq('id', session_id).execute()
