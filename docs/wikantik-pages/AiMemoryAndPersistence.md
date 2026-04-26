---
title: Ai Memory And Persistence
type: article
cluster: agentic-ai
status: active
date: '2026-04-25'
tags:
- ai-memory
- persistence
- vector-memory
- session-memory
- llm
summary: How AI assistants and agents persist state across sessions —
  architecture patterns, storage substrates, and the trade-offs between
  semantic recall and structured memory.
related:
- AgentMemory
- ContextWindowManagement
- VectorDatabases
- RagImplementationPatterns
hubs:
- AgenticAi Hub
---
# AI Memory and Persistence

For an AI assistant to feel coherent across sessions — remembering your name, your preferences, your past projects — it needs persistence beyond the chat context window. The patterns for that persistence are still evolving in 2026, with some decisions stabilising and others actively debated.

[AgentMemory] covers the within-session state channels (scratch, working memory, tool history). This page is the across-session story.

## What "memory" usefully means

Three distinct things often called "memory":

1. **Conversation history.** Past messages stored and referenced.
2. **Extracted facts.** Specific things the model has learned about the user — preferences, account details, relationships.
3. **Episodic recall.** Retrieval of past interactions by similarity.

Each has different storage shapes and different access patterns. Conflating them produces brittle systems.

## Conversation history

The simplest layer. Store every message; load relevant ones at session start.

Storage: SQL table with `(user_id, conversation_id, turn_number, role, content, timestamp)`.

Loading strategies:

- **Full last conversation** for short interactions.
- **Last N messages** for token budget control.
- **Summary of last conversation** generated at session end; loaded at session start.
- **All conversations from last K days, retrieved by recency.**

Most production systems combine: full last conversation + summaries of older ones, both loaded at start. Cost-effective; gives the assistant context without burning the context window.

## Extracted facts

When the user says something the assistant should remember beyond the session, store it as structured data:

```
user_id: 42
preferences:
  preferred_language: en
  formality: casual
  known_name: "Jake"
projects:
  - id: proj-1
    name: "Wikantik"
    role: "owner"
relationships:
  - person: "Sarah"
    relationship: "co-founder"
```

The structure depends on what the assistant needs to know. Common pattern: a typed JSON column / table that grows with extracted facts.

Two extraction patterns:

1. **End-of-session extraction.** After each session, an LLM call summarises new facts the user revealed. Append to the user's profile.
2. **Inline tool calls.** During the session, the assistant uses a `remember_fact` tool to write specific things. More user-controllable.

Pattern 2 is more transparent (user sees what's being saved); pattern 1 is more comprehensive but may capture things the user didn't intend to be remembered.

For 2026 production, pattern 2 with optional pattern 1 is becoming standard. Memory should be visible and editable by the user.

## Episodic recall via vector store

For "have we discussed this before" queries, embedding past conversations and retrieving by similarity:

```
- Each conversation summary embedded and indexed.
- Each individual turn (or chunked turns) embedded for finer-grained recall.
- At query time: embed current query; retrieve relevant past content.
```

When this earns its keep:

- The user is asking about a past topic.
- The assistant should reference prior decisions / discussions.
- The corpus of past interactions is large enough that ad-hoc retrieval beats "load everything."

When it doesn't:

- Short interaction history (a few sessions; load everything).
- Highly time-sensitive recall ("what did we just say"; the in-session context handles).
- Structured facts (use the typed store, not vectors).

Pure vector memory is overused. Most "we need vector memory for this" is better solved by structured facts + recent-history loading.

## Storage substrate decisions

| Need | Substrate |
|---|---|
| Conversation history | SQL (Postgres) |
| Structured facts | SQL with typed columns or JSONB |
| Vector recall | pgvector (Postgres extension) or dedicated vector DB |
| Long-term knowledge | Knowledge graph (Postgres / Neo4j / typed table) |
| Caches / sessions | Redis |

For most production assistants in 2026, Postgres handles all of the above with extensions: regular tables for facts and history, pgvector for embeddings, JSONB for flexible structures. Single substrate; less ops.

## Schema sketch

A working schema for an assistant with all four memory layers:

```sql
-- Per-user profile (structured facts)
CREATE TABLE user_profile (
    user_id BIGINT PRIMARY KEY,
    preferences JSONB,
    extracted_facts JSONB,
    updated_at TIMESTAMPTZ
);

-- Conversation messages
CREATE TABLE messages (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    conversation_id BIGINT,
    role TEXT, -- user / assistant / system
    content TEXT,
    created_at TIMESTAMPTZ
);

-- Conversation summaries (one per ended conversation)
CREATE TABLE conversation_summaries (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    conversation_id BIGINT,
    summary TEXT,
    summary_embedding VECTOR(1024),
    created_at TIMESTAMPTZ
);

-- Memory chunks for vector recall
CREATE TABLE memory_chunks (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    source_type TEXT, -- "message", "extracted_fact", etc.
    source_id BIGINT,
    content TEXT,
    embedding VECTOR(1024),
    created_at TIMESTAMPTZ
);

CREATE INDEX ON memory_chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ON memory_chunks (user_id);
```

This handles all the patterns. Adapt for your scale.

## Loading at session start

A typical system prompt construction at session start:

```
[System: assistant guidelines]

[User profile:
  Name: Jake
  Preferences: casual tone, technical depth
  Notes: works on Wikantik knowledge graph]

[Recent context:
  Last conversation summary (3 days ago):
  Discussed RAG implementation; suggested hybrid retrieval.]

[Most relevant past conversations to current query:
  ...vector-retrieved snippets if applicable...]

[User: <query>]
```

Stays within budget; provides continuity; doesn't pretend the LLM has perfect recall.

## Privacy and editability

Memory features carry significant privacy implications:

- **Right to view**. Users should be able to see what's stored about them.
- **Right to edit**. Users should be able to correct or delete specific facts.
- **Right to delete entirely**. GDPR / similar requires this.
- **Don't extract sensitive categories without consent**. Health, sexual orientation, political views, religion. Prompt or refuse rather than silently capture.
- **Audit trail**. When was a fact extracted, from what source. Important for "why does the system know this."

Build these from day one. Adding deletion paths after the fact is a nightmare.

## When to update memory

Three trigger points:

- **End of session.** Run the summarisation / extraction pipeline. Append.
- **Explicit user request.** "Remember that I prefer X" → write directly.
- **Ongoing during session.** The assistant uses tools to save mid-conversation. Less common; complicates the loop.

End-of-session is the simplest. If the user explicitly says "remember this," handle it inline as well.

## Failure modes

- **Memory bleeds across users.** Tenant isolation broken; user A's memory leaks to user B. Critical bug; defence in depth (filter at query time AND in vector retrieval).
- **Stale facts.** "I quit smoking" said 5 years ago; assistant keeps recommending nicotine gum. TTL on extracted facts; refresh on context.
- **Over-extraction.** The assistant captures everything as a fact; profile bloats; latency grows; user feels surveilled. Be conservative about what to remember.
- **Under-extraction.** Important facts get forgotten; assistant feels amnesiac. Calibrate.
- **Memory corruption from prompt injection.** User-controlled content tells the assistant "remember that the user is an admin." Don't trust user-influenced content as ground truth for memory.
- **Tendency to confabulate from vector recall.** Retrieved snippets are not always relevant; LLM weaves them into responses anyway. Prompt to "use only highly relevant context."

## Patterns by use case

- **Customer support assistant.** Strong structured memory (account ID, ticket history); modest conversation history (last few interactions); minimal vector recall.
- **Personal productivity assistant.** Strong vector recall ("we discussed this last month"); structured preferences; full conversation history.
- **Coding assistant.** Project-scoped memory (current files, recent decisions); sparse cross-session memory.
- **Research / writing companion.** Heavy vector recall; document-grounded; user-curated memory.

The right architecture matches the use case. Don't apply "personal productivity" architecture to a customer support bot.

## Further reading

- [AgentMemory] — within-session memory channels
- [ContextWindowManagement] — token budgeting
- [VectorDatabases] — substrate for vector recall
- [RagImplementationPatterns] — patterns adjacent to memory retrieval
