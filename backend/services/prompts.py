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
