"""
Prompt templates for ZeroTouch card generation pipeline

Phase 1 (MVP): build_card_generation_prompt()
  - Transcription -> structured cards (memo items)
"""


def build_card_generation_prompt(transcription: str) -> str:
    """
    Build prompt for card generation from transcription.

    Extracts actionable items, memos, and key information from
    ambient conversation transcription and structures them as cards.

    Args:
        transcription: Transcription text from ASR

    Returns:
        Prompt string for LLM
    """

    prompt = f"""You are an expert at extracting actionable information from workplace conversations.

# Your Role

Extract structured "cards" from the following conversation transcription.
A card represents a discrete piece of actionable or noteworthy information.

# Card Types

Extract the following types of cards:

1. **task** - Something that needs to be done (action item, to-do)
2. **memo** - Important information worth remembering (facts, decisions, notes)
3. **schedule** - Time-related information (appointments, deadlines, events)
4. **contact** - People or organization references with context
5. **issue** - Problems, complaints, concerns raised

# Rules

## DO
- Extract concrete, specific information
- Preserve names, numbers, dates, times exactly as mentioned
- Include context (who said it, about whom/what)
- Mark urgency when apparent from conversation tone or explicit mention
- Extract even brief or casual mentions if they contain actionable content

## DON'T
- Do not infer or guess information not in the transcription
- Do not merge multiple distinct items into one card
- Do not add information not present in the conversation
- Do not skip items just because they seem minor

# Output Format

Output ONLY the following JSON structure. No explanation before or after.

{{
  "cards_v1": {{
    "cards": [
      {{
        "type": "task | memo | schedule | contact | issue",
        "title": "Short summary (max 30 chars)",
        "content": "Detailed content of the card",
        "urgency": "high | normal | low",
        "mentioned_by": "Speaker identifier or null",
        "context": "Brief context about when/why this was mentioned"
      }}
    ],
    "summary": "1-2 sentence overview of the conversation",
    "speaker_count": 0,
    "conversation_topic": "Main topic of the conversation"
  }}
}}

# Transcription

{transcription}
"""

    return prompt


def build_topic_join_prompt(
    topic_live_title: str,
    topic_live_summary: str,
    recent_utterances: list[str],
    incoming_utterance: str,
) -> str:
    recent_text = "\n".join(
        [f"- {line}" for line in recent_utterances if (line or "").strip()]
    ) or "- (no context yet)"

    return f"""You are classifying whether a new utterance belongs to an existing conversation topic.

Decide if the incoming utterance should be attached to the existing topic context.
Output ONLY JSON:
{{
  "decision": "join | new | unsure",
  "confidence": 0.0,
  "reason": "short reason"
}}

Existing topic title:
{topic_live_title or "(none)"}

Existing topic live summary:
{topic_live_summary or "(none)"}

Recent utterances in topic:
{recent_text}

Incoming utterance:
{incoming_utterance or "(empty)"}
"""


def build_topic_live_update_prompt(
    current_title: str,
    current_summary: str,
    utterances: list[str],
) -> str:
    transcript = "\n".join([f"- {line}" for line in utterances if (line or "").strip()])
    return f"""You are updating a live conversation card for an ambient agent UI.

Requirements:
- Keep output concise and neutral.
- The title should be specific but short (max 30 chars if possible).
- Summary should be 1 sentence.
- Do not invent facts that are not in the utterances.

Output ONLY JSON:
{{
  "live_title": "string",
  "live_summary": "string"
}}

Current title:
{current_title or "(none)"}

Current summary:
{current_summary or "(none)"}

Latest utterances:
{transcript or "- (none)"}
"""


def build_topic_finalize_prompt(utterances: list[str]) -> str:
    transcript = "\n".join([f"- {line}" for line in utterances if (line or "").strip()])
    return f"""You are finalizing a completed conversation topic from ambient capture.

Produce:
1) final title
2) final summary
3) task candidates when concrete actions are present
4) topic type

Output ONLY JSON:
{{
  "final_title": "string",
  "final_summary": "string",
  "topic_type": "reservation_call | customer_support | internal_ops | casual_chat | unknown",
  "task_candidates": [
    {{
      "title": "string",
      "detail": "string",
      "priority": "high | normal | low",
      "due_hint": "string | null"
    }}
  ]
}}

Conversation utterances (ordered):
{transcript or "- (none)"}
"""


def build_topic_scoring_prompt(
    final_title: str,
    final_summary: str,
    utterances: list[str],
) -> str:
    """
    Build prompt for Phase 1 importance scoring (Lv.0-5).
    Uses a lightweight LLM to classify topic importance.
    """
    transcript = "\n".join(
        [f"- {line}" for line in utterances if (line or "").strip()]
    )
    return f"""You are scoring the importance of a finalized conversation topic from an ambient workplace recorder.

Rate the topic from 0 to 5 based on its information value to the workplace.

# Importance Levels

- 0: Noise - misrecognition, inaudible fragments, meaningless audio artifacts
- 1: Daily/emotional - greetings, weather talk, fatigue expressions ("good morning", "I'm tired")
- 2: Routine operations - standard task completions ("register closed", "inventory checked")
- 3: Shareable facts - information others should know ("Mr. XX is visiting next week", "the pipe might be leaking")
- 4: Decisions - policy changes, action plans ("this menu item is sold out from today", "handle this complaint like this")
- 5: Master knowledge - recipes, procedures, VIP preferences, emergency contacts

# Rules

- Score based ONLY on the conversation content provided
- If the transcription is mostly garbled, empty, or nonsensical, score 0
- Short greetings or single filler words are level 1
- When in doubt between two levels, choose the lower one
- Provide a brief reason for your scoring

Output ONLY JSON:
{{
  "importance_level": 0,
  "importance_reason": "short reason"
}}

# Topic

Title: {final_title or "(none)"}
Summary: {final_summary or "(none)"}

# Utterances

{transcript or "- (none)"}
"""


def build_topic_annotation_prompt(
    final_title: str,
    final_summary: str,
    final_description: str,
    utterances: list[dict],
    now_iso: str,
) -> str:
    lines: list[str] = []
    for row in utterances:
        session_id = str(row.get("id") or "").strip()
        recorded_at = str(row.get("recorded_at") or row.get("created_at") or "").strip()
        text = str(row.get("transcription") or "").strip()
        if not session_id or not text:
            continue
        lines.append(f"- id={session_id} at={recorded_at} text={text}")

    transcript = "\n".join(lines) or "- (none)"

    return f"""You are extracting structured facts from a finalized topic.

You MUST only use the information contained in the utterances.
If the topic has no reliable facts, return an empty list.

# Categories (choose from these when possible)
接客 | 調理 | 清掃 | 事務 | トラブル | アイデア | 人事 | 在庫 | 設備

# Intents (choose from these when possible)
報告 | 相談 | 指示 | 不満 | 提案 | 質問 | 確認 | 共有

# TTL guidance
- ephemeral: short-lived (1-7 days)
- seasonal: medium (1-6 months)
- permanent: no expiry

# Date normalization
Use this reference as "now": {now_iso}
Convert relative dates ("tomorrow", "next week") into absolute ISO-8601.
If time is unknown, set time to 09:00 in the local timezone.

Output ONLY JSON:
{{
  "facts": [
    {{
      "fact_text": "string",
      "entities": [
        {{"type": "person|product|place|datetime|money|other", "value": "string", "role": "string|optional"}}
      ],
      "categories": ["string"],
      "intents": ["string"],
      "ttl": {{"type": "ephemeral|seasonal|permanent", "expires_at": "ISO-8601|null"}},
      "source_cards": ["uuid"]
    }}
  ]
}}

# Topic
Title: {final_title or "(none)"}
Summary: {final_summary or "(none)"}
Description: {final_description or "(none)"}

# Utterances
{transcript}
"""


def build_topic_batch_group_prompt(sessions: list[dict]) -> str:
    lines: list[str] = []
    for session in sessions:
        session_id = str(session.get("id") or "").strip()
        transcription = str(session.get("transcription") or "").strip()
        recorded_at = str(session.get("recorded_at") or session.get("created_at") or "").strip()
        if not session_id or not transcription:
            continue
        lines.append(f"- id={session_id} at={recorded_at} text={transcription}")

    transcript = "\n".join(lines) or "- (none)"

    return f"""You are grouping ambient conversation cards into finalized topics.

You will receive session cards for a single device and a single evaluation run.
Return grouped topics with a short title and summary.

Rules:
- Use ONLY the provided session IDs.
- Every provided session ID must be assigned to exactly one topic.
- Do not invent or drop session IDs.
- If all cards are about one thing, return one topic.
- If a card is short (for example one phrase), still assign it to a topic.
- Keep title short and concrete.
- Summary should be one concise sentence.

Output ONLY JSON:
{{
  "topics": [
    {{
      "title": "string",
      "summary": "string",
      "session_ids": ["uuid", "uuid"]
    }}
  ]
}}

Sessions:
{transcript}
"""
