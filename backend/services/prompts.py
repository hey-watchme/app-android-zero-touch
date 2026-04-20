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


def build_topic_finalize_prompt(
    utterances: list[str],
    context_preamble: str = "",
) -> str:
    transcript = "\n".join([f"- {line}" for line in utterances if (line or "").strip()])
    context_section = ""
    if context_preamble.strip():
        context_section = f"""
# Context about the speaker and workspace

{context_preamble.strip()}

"""
    return f"""You are finalizing a completed conversation topic from ambient capture.
{context_section}

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

Rules:
- Use the context only to disambiguate project/workspace meaning and choose better labels.
- Do not invent facts that are not supported by the utterances.
- Prefer project-specific titles when the utterances clearly match a known project or focus topic.

Conversation utterances (ordered):
{transcript or "- (none)"}
"""


def build_topic_scoring_prompt(
    final_title: str,
    final_summary: str,
    utterances: list[str],
    context_preamble: str = "",
) -> str:
    """
    Build prompt for Phase 1 importance scoring (Lv.0-5).
    Uses a lightweight LLM to classify topic importance.
    """
    transcript = "\n".join(
        [f"- {line}" for line in utterances if (line or "").strip()]
    )
    context_section = ""
    if context_preamble.strip():
        context_section = f"""
# Context about the speaker and workspace

{context_preamble.strip()}

"""
    return f"""You are scoring the importance of a finalized conversation topic from an ambient workplace recorder.
{context_section}

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
    context_preamble: str = "",
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

    context_section = ""
    if context_preamble.strip():
        context_section = f"""
# Context about the speaker and workspace

{context_preamble.strip()}

"""
    return f"""You are extracting structured facts from a finalized topic.
{context_section}

You MUST only use the information contained in the utterances.
If the topic has no reliable facts, return an empty list.
Use the context only for disambiguation, category naming, and project/category classification.

# Categories (choose from these when possible)
接客 | 調理 | 清掃 | 事務 | トラブル | アイデア | 人事 | 在庫 | 設備

# Category rule
- If none of the above fits, create a NEW short category name.
- Use 1-3 categories per fact.
- Keep category names concise (2-6 Japanese characters), avoid synonyms or near-duplicates.
- When workspace-specific projects or focus topics are clearly relevant, prefer those labels over overly generic labels like "事務".

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


def build_wiki_ingest_prompt(
    facts: list[dict],
    existing_pages: list[dict],
    available_projects: list[dict],
    context_preamble: str = "",
) -> str:
    """
    Build prompt for Phase 3 Wiki Ingest.
    Takes Facts (Lv.3+) and existing wiki pages, outputs an updated wiki.

    Args:
        facts: list of fact dicts with id, fact_text, importance_level, categories, ttl_type
        existing_pages: list of current wiki page dicts with page_key, title, body, project/category, kind
        available_projects: list of current workspace project dicts
        context_preamble: workspace context string

    Returns:
        Prompt string for LLM
    """
    context_section = ""
    if context_preamble.strip():
        context_section = f"""
# Context about the speaker and workspace

{context_preamble.strip()}

"""

    project_lines = []
    for project in available_projects:
        project_key = str(project.get("project_key") or "").strip()
        display_name = str(project.get("display_name") or "").strip() or project_key
        aliases = ", ".join(project.get("aliases") or []) or "-"
        description = str(project.get("description") or "").strip() or "-"
        if not project_key:
            continue
        project_lines.append(
            f'- project_key="{project_key}" display_name="{display_name}" aliases=[{aliases}]\n'
            f'  description: {description}'
        )
    available_projects_section = "# Available Projects\n\n" + (
        "\n\n".join(project_lines) if project_lines else '(none; use "unassigned")'
    )

    existing_section = ""
    if existing_pages:
        lines = []
        for page in existing_pages:
            lines.append(
                f'- page_key="{page.get("page_key") or "?"}" title="{page["title"]}" '
                f'project_key={page.get("project_key") or "unassigned"} '
                f'category={page.get("category") or "?"} '
                f'kind={page.get("kind") or "?"} version={page.get("version", 1)}\n'
                f'  body: {(page.get("body") or "")[:400]}'
            )
        existing_section = "# Existing Wiki Pages\n\n" + "\n\n".join(lines)
    else:
        existing_section = "# Existing Wiki Pages\n\n(none yet)"

    fact_lines = []
    for fact in facts:
        cats = ", ".join(fact.get("categories") or []) or "-"
        fact_lines.append(
            f'- id={fact["id"]} lv={fact.get("importance_level", 0)} '
            f'ttl={fact.get("ttl_type") or "?"} categories=[{cats}]\n'
            f'  text: {fact.get("fact_text", "")}'
        )
    facts_section = "# Facts to Integrate\n\n" + "\n\n".join(fact_lines)

    return f"""You are a knowledge curator building a personal wiki from structured facts.
{context_section}
Your job: integrate the given Facts into the wiki.

Rules:
- Group related facts into focused wiki pages.
- If a fact fits an existing page, update that page (expand or refine its body).
- If a fact introduces a new category inside a project, create a new page.
- Write body in clear plain text (no markdown headers needed, but bullet lists are ok).
- Each page should be focused on one category-sized topic inside one project.
- kind values: decision | rule | insight | procedure | task
- project_key must be one of the available project keys below, or "unassigned" when none fits.
- category is the mid-level grouping inside the chosen project. Keep it short and reusable.
- page_key is a stable machine key for the page. Reuse the existing page_key when updating an existing page.
- Avoid overly granular categories. Prefer merging near-duplicates such as "README", "README運用", "README更新" into one reusable category.
- Use the provided context to choose project/category labels that match this workspace.
- source_fact_ids: include ALL fact IDs that contributed to this page (across all ingest runs).
- You MUST include every existing page in the output, even if unchanged.
- Do not merge unrelated facts into one page.
- summary_one_liner: write a single Japanese sentence (<=120 chars) that captures what this page is about, so it can be used as an index entry.

{available_projects_section}

{existing_section}

{facts_section}

Output ONLY JSON:
{{
  "pages": [
    {{
      "page_key": "string",
      "project_key": "string | unassigned",
      "category": "string",
      "title": "string",
      "body": "string",
      "kind": "decision | rule | insight | procedure | task",
      "source_fact_ids": ["uuid", "..."],
      "summary_one_liner": "string (<=120 chars, Japanese)"
    }}
  ]
}}
"""


def build_wiki_query_selector_prompt(
    question: str,
    index_rows: list[dict],
    context_preamble: str = "",
) -> str:
    """
    Build prompt for the Query selector step.
    Selects candidate wiki pages from the index (one-liner summaries) that
    most likely contain the answer to the user's natural-language question.
    """
    context_section = ""
    if context_preamble.strip():
        context_section = f"""
# Context about the speaker and workspace

{context_preamble.strip()}

"""

    index_lines: list[str] = []
    for row in index_rows:
        page_id = str(row.get("page_id") or "").strip()
        if not page_id:
            continue
        title = str(row.get("title") or "").strip() or "(no title)"
        project_name = str(row.get("project_name") or "").strip() or "unassigned"
        category = str(row.get("category") or "").strip() or "-"
        kind = str(row.get("kind") or "").strip() or "-"
        source_count = row.get("source_count") or 0
        summary = str(row.get("summary_one_liner") or "").strip() or "(no summary)"
        index_lines.append(
            f'- page_id={page_id} project="{project_name}" category="{category}" '
            f'kind={kind} sources={source_count}\n'
            f'  title: {title}\n'
            f'  summary: {summary}'
        )
    index_section = "\n\n".join(index_lines) if index_lines else "(empty index)"

    return f"""You are a librarian selecting candidate wiki pages for a question.
{context_section}
You will receive a natural-language question and an index of wiki pages.
Each index entry has a page_id, project, category, kind, and a one-line summary.

Rules:
- Choose up to 5 page_ids that are most likely to contain the answer.
- Prefer pages whose summary_one_liner, project_name, or category directly relate to the question.
- It is OK to return fewer than 5 if only a few pages look relevant.
- Never invent a page_id. Return only page_ids present in the index.
- Return only JSON.

# Question

{question}

# Wiki Index

{index_section}

Output ONLY JSON:
{{
  "selected_page_ids": ["uuid", "..."],
  "reasoning": "short reason in Japanese"
}}
"""


def build_wiki_query_answerer_prompt(
    question: str,
    full_pages: list[dict],
    context_preamble: str = "",
) -> str:
    """
    Build prompt for the Query answerer step.
    Produces an answer grounded in the provided pages and decides how the
    result should be filed back (derivable | synthesis | gap_or_conflict).
    """
    context_section = ""
    if context_preamble.strip():
        context_section = f"""
# Context about the speaker and workspace

{context_preamble.strip()}

"""

    page_blocks: list[str] = []
    for page in full_pages:
        page_id = str(page.get("id") or "").strip()
        if not page_id:
            continue
        title = str(page.get("title") or "").strip() or "(no title)"
        project_name = str(page.get("project_name") or "").strip() or "unassigned"
        category = str(page.get("category") or "").strip() or "-"
        kind = str(page.get("kind") or "").strip() or "-"
        page_key = str(page.get("page_key") or "").strip() or "-"
        body = str(page.get("body") or "").strip() or "(empty)"
        page_blocks.append(
            f'## page_id={page_id}\n'
            f'title: {title}\n'
            f'project: {project_name}\n'
            f'category: {category}\n'
            f'kind: {kind}\n'
            f'page_key: {page_key}\n'
            f'body:\n{body}'
        )
    pages_section = "\n\n---\n\n".join(page_blocks) if page_blocks else "(no pages provided)"

    return f"""You answer questions grounded strictly in the provided wiki pages.
{context_section}
You will receive a natural-language question and a small set of full wiki pages.

Rules:
- Answer in Japanese.
- Use only information contained in the provided pages. Do not invent facts.
- When quoting or citing a specific page, inline [[page_key]] in the answer where relevant.
- Decide the outcome:
  - outcome=derivable: the answer is directly contained in one or more of the pages.
  - outcome=synthesis: you combined multiple pages into a novel synthesis that is worth filing back as a NEW query_answer page. Fill suggested_new_page.
  - outcome=gap_or_conflict: the pages contradict each other or lack the information needed to answer. Fill target_page_id pointing to the most relevant (or conflicting) page.
- source_page_ids: include every page_id you actually used to form the answer.
- target_page_id: set only when outcome=gap_or_conflict. Otherwise null.
- suggested_new_page: fill only when outcome=synthesis. Otherwise all fields null.
  - project_key: reuse a project_key that appears in the provided pages, or null.
  - category: short reusable label (for example "query_answer" or a project-specific category).
  - title: concise title for the new page.
  - summary_one_liner: single Japanese sentence (<=120 chars) for the index entry.
- confidence: high | medium | low, reflecting how well the pages support the answer.
- reasoning: short Japanese sentence explaining why you chose this outcome.

# Question

{question}

# Wiki Pages

{pages_section}

Output ONLY JSON:
{{
  "answer": "string (Japanese)",
  "confidence": "high | medium | low",
  "outcome": "derivable | synthesis | gap_or_conflict",
  "reasoning": "string (Japanese, short)",
  "source_page_ids": ["uuid", "..."],
  "target_page_id": "uuid or null",
  "suggested_new_page": {{
    "project_key": "string or null",
    "category": "string or null",
    "title": "string or null",
    "summary_one_liner": "string or null"
  }}
}}
"""


def build_topic_batch_group_prompt(
    sessions: list[dict],
    context_preamble: str = "",
) -> str:
    lines: list[str] = []
    for session in sessions:
        session_id = str(session.get("id") or "").strip()
        transcription = str(session.get("transcription") or "").strip()
        recorded_at = str(session.get("recorded_at") or session.get("created_at") or "").strip()
        if not session_id or not transcription:
            continue
        lines.append(f"- id={session_id} at={recorded_at} text={transcription}")

    transcript = "\n".join(lines) or "- (none)"
    context_section = ""
    if context_preamble.strip():
        context_section = f"""
# Context about the speaker and workspace

{context_preamble.strip()}

"""

    return f"""You are grouping ambient conversation cards into finalized topics.
{context_section}

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
- Use the context only to disambiguate project/workspace meaning when grouping related sessions.
- Prefer project-specific titles when the transcript clearly matches a known project or focus topic.

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


def build_wiki_lint_gap_prompt(
    gap_entries: list[dict],
    context_preamble: str = "",
) -> str:
    """
    Build the LLM prompt for the Wiki Lint data-gap detection step.

    Args:
        gap_entries: List of dicts with keys 'question' and 'reasoning',
                     each representing a past query that could not be answered.
        context_preamble: Optional workspace/device context string.

    Returns:
        Prompt string for LLM.
    """
    context_section = ""
    if context_preamble:
        context_section = f"\n# Context\n\n{context_preamble.strip()}\n"

    entries_text = "\n".join(
        f"- Question: {e.get('question', '').strip()}\n  Reasoning: {e.get('reasoning', '').strip()}"
        for e in gap_entries
        if e.get("question") or e.get("reasoning")
    )
    if not entries_text:
        entries_text = "(no entries)"

    return f"""You are analyzing recurring knowledge gaps from past queries.{context_section}

Each entry below is a question that could not be answered from the wiki, along with the LLM's reasoning for why.

# Gap Entries

{entries_text}

# Task

Identify up to {5} themes of missing knowledge based on the entries above.
Group related questions together under a single descriptive theme.
For each theme, provide:
- theme: short label (English)
- description: brief explanation of what knowledge is missing (日本語可)
- related_questions: list of representative question strings from the entries

Return ONLY JSON with no markdown:
{{"gaps": [{{"theme": "string", "description": "string", "related_questions": ["string"]}}]}}"""
