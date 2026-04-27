"""
Knowledge Worker domain schema.

Declarative catalog of intents that the Action Converter can extract for the
"knowledge worker" domain (designers, project managers, vendor coordinators).
For Slice 1, only email_draft is enabled. Additional intents (slack_message_draft,
meeting_request, decision_log, etc.) are listed in the PoC design doc and will
be enabled in later slices.
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional

DOMAIN = "knowledge_worker"


INTENT_CATALOG: Dict[str, Dict[str, Any]] = {
    "email_draft": {
        "title_ja": "メール下書き",
        "description_ja": (
            "発話の中に「メールに返信する」「メールを送る」という意図が含まれる場合に生成する。"
            "件名と本文を作り、可能なら宛先を埋める。"
        ),
        "destination": "gmail_inline_draft",
        "required_fields": ["subject", "body"],
        "optional_fields": ["recipient", "recipient_name", "tone", "source_quote"],
        "requires_review": True,
    },
}


ENABLED_INTENTS: List[str] = ["email_draft"]


def list_intents() -> List[str]:
    return list(ENABLED_INTENTS)


def get_intent_def(intent_type: str) -> Optional[Dict[str, Any]]:
    return INTENT_CATALOG.get(intent_type)


def normalize_email_payload(payload: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Validate and normalize email_draft payload. Returns None if invalid."""
    if not isinstance(payload, dict):
        return None
    subject = str(payload.get("subject") or "").strip()
    body = str(payload.get("body") or "").strip()
    if not subject or not body:
        return None
    normalized: Dict[str, Any] = {
        "subject": subject[:200],
        "body": body[:5000],
    }
    recipient = str(payload.get("recipient") or "").strip()
    if recipient:
        normalized["recipient"] = recipient[:200]
    recipient_name = str(payload.get("recipient_name") or "").strip()
    if recipient_name:
        normalized["recipient_name"] = recipient_name[:100]
    tone = str(payload.get("tone") or "").strip()
    if tone:
        normalized["tone"] = tone[:50]
    source_quote = str(payload.get("source_quote") or "").strip()
    if source_quote:
        normalized["source_quote"] = source_quote[:500]
    return normalized


def normalize_payload(intent_type: str, payload: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    if intent_type == "email_draft":
        return normalize_email_payload(payload)
    return None


def build_converter_prompt(
    *,
    final_title: str,
    final_summary: str,
    final_description: str,
    utterances: List[Dict[str, Any]],
    facts: List[Dict[str, Any]],
    now_iso: str,
    context_preamble: str = "",
) -> str:
    """
    Build a prompt for extracting Action Candidates (Slice 1: email_draft only).

    The model receives the finalized topic, the underlying utterances, the
    extracted facts, and the workspace context. It must return zero or more
    email_draft candidates strictly grounded in the conversation.
    """

    utterance_lines: List[str] = []
    for row in utterances:
        session_id = str(row.get("id") or "").strip()
        recorded_at = str(row.get("recorded_at") or row.get("created_at") or "").strip()
        text = str(row.get("transcription") or "").strip()
        if not session_id or not text:
            continue
        utterance_lines.append(f"- id={session_id} at={recorded_at} text={text}")
    transcript = "\n".join(utterance_lines) or "- (none)"

    fact_lines: List[str] = []
    for fact in facts:
        fact_text = str(fact.get("fact_text") or "").strip()
        if not fact_text:
            continue
        intents = ",".join(fact.get("intents") or [])
        categories = ",".join(fact.get("categories") or [])
        fact_lines.append(
            f"- fact={fact_text} intents=[{intents}] categories=[{categories}]"
        )
    fact_block = "\n".join(fact_lines) or "- (none)"

    context_section = ""
    if context_preamble.strip():
        context_section = (
            "\n# Context about the speaker and workspace\n\n"
            f"{context_preamble.strip()}\n"
        )

    return f"""You are extracting **Action Candidates** from a finalized conversation topic.

Action Candidates are concrete drafts of digital deliverables that the speaker
needs to produce after this conversation. For this PoC slice, you may only
emit drafts of type `email_draft`.
{context_section}
You MUST only use information explicitly grounded in the utterances. Do not
invent recipients, deadlines, or content that is not stated. If the
conversation does not clearly imply that an email needs to be drafted, return
an empty list.

# When to emit an email_draft
- The speaker says they need to reply to an email someone sent them.
- The speaker says they need to email someone (a request, confirmation,
  follow-up, thank-you, apology, or scheduling note).
- The speaker dictates the substance of an email they intend to send.

Do NOT emit a draft when:
- The conversation is about Slack / chat / phone, not email.
- The speaker is only thinking out loud without committing to send a message.
- The conversation lacks enough substance to write a reasonable email body.

# Required output JSON shape

{{
  "candidates": [
    {{
      "intent_type": "email_draft",
      "title": "string (short label, max ~40 chars)",
      "summary": "string (1 sentence on what this email does)",
      "payload": {{
        "recipient": "string|null  (email address if explicitly stated)",
        "recipient_name": "string|null (person name if mentioned)",
        "subject": "string (required, concise, no emojis)",
        "body": "string (required, polite Japanese unless the conversation is in another language; use \\n for newlines)",
        "tone": "polite | casual | formal | apologetic | grateful | urgent",
        "source_quote": "string (a short verbatim quote from the utterances that justifies this draft)"
      }},
      "confidence": 0.0,
      "source_session_ids": ["uuid", ...]
    }}
  ]
}}

# Output rules
- Output ONLY JSON. No markdown, no commentary.
- If no email is warranted, return {{"candidates": []}}.
- Match the language of the speaker for body and subject (Japanese in, Japanese out).
- Keep body under ~600 characters. The user will edit before sending.
- Prefer concise drafts that the user can quickly skim and send.
- For confidence: 0.9+ when the speaker explicitly dictates the email,
  0.6-0.8 when intent is clear but content is partial, below 0.6 do not emit.

# Reference time
Use this as "now": {now_iso}

# Topic
Title: {final_title or "(none)"}
Summary: {final_summary or "(none)"}
Description: {final_description or "(none)"}

# Utterances
{transcript}

# Facts already extracted from this topic
{fact_block}
"""
