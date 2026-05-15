# LLM Activity View — Design

**Date:** 2026-05-15
**Status:** Approved — ready for implementation planning

## Problem

Background LLM work (entity extraction, proposal judging, embedding indexing)
runs continuously, but an operator has no way to see it. Today there are only
subsystem-level Prometheus counters (`wikantik_kg_extractor_*`) and a couple of
status records (`JudgeRunner.Status`); there is no per-call record and no admin
UI. When something is slow or failing there is nothing to look at.

We need an admin view showing **individual LLM calls** — a row per request — for
the last hour, including calls in flight right now. State is held in memory
only; it does not need to survive a restart.

## Goals

- A live, polled admin view listing recent and in-flight LLM calls.
- Per call: timestamp, subsystem, backend, model, operation, duration, status,
  truncated prompt/response previews, best-effort token counts, error message.
- Per-call server-side logging: `LOG.debug` on success, `LOG.warn` on failure.
- Bounded in-memory storage (~hour window), no persistence, no DB schema.

## Non-goals

- No persistence across restarts.
- No control actions (cancel/trigger) — the view is purely observational.
- No literal wire-level prompt capture — previews render the meaningful
  interface arguments, not raw HTTP JSON (see Approach, below).
- No new database table or migration.

## Approach

All LLM calls flow through three client interfaces — `EntityExtractor`,
`ProposalJudge`, `TextEmbeddingClient` — so calls are captured with **recording
decorators** wrapping each client (chosen over instrumenting inside each client
class). Rationale:

- Zero edits to the LLM client classes; observability stays separated and each
  decorator is independently unit-testable.
- In-flight tracking is natural: record before delegating, finalize after.
- At a 500-char truncation, a rendering of the meaningful interface arguments
  (the page chunk being extracted, the proposal being judged, the parsed
  result) is more useful than the first 500 chars of a boilerplate-heavy,
  prompt-cached wire request.

## Architecture

### Components

All backend additions live in `wikantik-main`, package
`com.wikantik.llm.activity`, unless noted.

| Component | Role |
|---|---|
| `LlmActivityLog` | In-memory ring buffer of LLM calls; single source of truth. Registered as a WikiEngine manager so the servlet can reach it. |
| `LlmCall` | One call. Mutable: created `IN_FLIGHT`, finalized once to `OK`/`ERROR`. |
| `LlmCallView` | Immutable record — the snapshot DTO the servlet serializes. |
| `RecordingEntityExtractor` | Decorator implementing `EntityExtractor`, wrapping the real extractor. |
| `RecordingProposalJudge` | Decorator implementing `ProposalJudge`. |
| `RecordingEmbeddingClient` | Decorator implementing `TextEmbeddingClient` (handles the async path). |
| `AdminLlmActivityResource` (`wikantik-rest`) | `GET /admin/llm-activity` — read-only snapshot. |
| `LlmActivityTab.jsx` (`wikantik-frontend`) | Polled admin table view. |

### Wiring

Decorators are installed at the ~3 client construction sites
(`EntityExtractorFactory`, the proposal-judge wiring, the embedding-client
wiring), gated by `wikantik.llm_activity.enabled` (default `true`). When
disabled, the raw clients are used unwrapped — zero overhead. The
`LlmActivityLog` is constructed once during subsystem init, registered as an
engine manager, and passed into the decorator wiring.

## Data model

### `LlmCall`

| Field | Notes |
|---|---|
| `seq` | Monotonic id; also the newest-first sort key. |
| `startedAt` | `Instant` when the call began. |
| `subsystem` | `ENTITY_EXTRACTION` \| `PROPOSAL_JUDGE` \| `EMBEDDING`. |
| `backend` | `ollama` \| `claude`. |
| `model` | Model tag. |
| `operation` | `chat` \| `embed`. |
| `status` | `IN_FLIGHT` \| `OK` \| `ERROR`. |
| `durationMs` | `-1` while in-flight; set at finalization. |
| `promptPreview` | Rendered input, truncated to `payload_chars`; set at start. |
| `responsePreview` | Rendered output, truncated to `payload_chars`; set at finalize. |
| `inputTokens` / `outputTokens` | Nullable; best-effort — populated only where the parsed result exposes them. |
| `errorMessage` | Nullable; set on `ERROR`. |

Mutable fields are `volatile`; finalization is write-once.
`LlmCallView` is an immutable record copied from `LlmCall` under the buffer lock.

### Retention

`ArrayDeque<LlmCall>` guarded by a lock. Two bounds, both configurable:

- **Count cap** — `wikantik.llm_activity.max_records` (default 5000).
- **Age window** — `wikantik.llm_activity.window_minutes` (default 60).

On insert, evict the oldest **finalized** records past either bound.
`IN_FLIGHT` records are never age-evicted, keeping the "now" view honest.
Bounded memory: ~5000 records × ~1.3 KB ≈ ~6.5 MB. In-memory only — no DB
table, no migration.

## Capture flow

All three decorators follow the same shape:

```
LlmCall call = log.begin(subsystem, backend, model, op, preview(input));
try {
    R result = delegate.<method>(input);
    log.succeed(call, preview(result), tokensOf(result));   // -> LOG.debug
    return result;
} catch (Exception e) {
    log.fail(call, e);                                       // -> LOG.warn
    throw e;                                                 // never swallowed
}
```

- `begin` appends an `IN_FLIGHT` record (immediately visible in the view).
  `succeed`/`fail` finalize it in place — status, `durationMs`,
  `responsePreview`, tokens/error.
- **Per-call logging** lives inside `log.succeed`/`log.fail`: `LOG.debug` on
  success, `LOG.warn` on failure (subsystem, model, error). Emitted uniformly,
  exactly once, regardless of decorator.
- **Robustness:** every `log.*` method body is internally `try/catch` — on a
  recorder bug it emits `LOG.warn` and continues. A recorder failure can never
  disturb the LLM call or alter its exception. The decorator never swallows the
  delegate's exception (always rethrows).
- **Async embedding:** `embedBatchAsync` returns a `CompletableFuture`; the
  decorator calls `begin`, then `.whenComplete(...)` to `fail` on error or
  `succeed` on success, so in-flight async embeds finalize correctly.
- **Previews:** `preview(input)` renders the meaningful interface argument
  (chunk text; proposal under judgement; "N chunks" for embeds);
  `preview(result)` renders the parsed outcome (e.g. `"12 mentions,
  5 proposals"`). Both truncated to `wikantik.llm_activity.payload_chars`
  (default 500).
- **Tokens:** populated where the parsed result exposes them; otherwise null.
  Threading raw API token counts through the client result types is an optional
  follow-up, not v1-blocking.

## Backend endpoint

`AdminLlmActivityResource` — a `RestServletBase` subclass, mapped
`/admin/llm-activity` in `web.xml`, already covered by `AdminAuthFilter`
(`/admin/*`). Read-only:

```
GET /admin/llm-activity?limit=200&subsystem=&status=
-> { "data": { "calls": [ ...newest-first... ], "inFlight": 3,
               "windowMinutes": 60, "capacity": 5000, "enabled": true } }
```

`limit` defaults to 200, capped at `max_records`. `subsystem`/`status` filtering
is optional server-side; the dataset is small enough that the UI may also filter
client-side. No `POST`/`DELETE` — purely observational.

## Frontend

`LlmActivityTab.jsx`, added as a **new tab in the existing admin Knowledge
area** (alongside `ExtractionTab`). This deliberately avoids a new standalone
SPA route — no `web.xml` + `SpaRoutingFilter` dual-registration to get wrong.

- **Polling:** 2 s when any call is `IN_FLIGHT`, 10 s otherwise (mirrors
  `ExtractionTab`).
- **Table:** time · subsystem (colored badge) · model · operation · duration ·
  status · tokens. `IN_FLIGHT` rows highlighted; `ERROR` rows red. Row click
  expands `promptPreview` + `responsePreview` + full error.
- **Header:** "N in-flight · M calls in last hour · K errors". Filter chips for
  subsystem and status. Empty state. If `enabled:false`, a notice explaining
  the flag instead of a table.
- **API client:** `api.knowledge.getLlmActivity()` ->
  `request('/admin/llm-activity?limit=200')`.

## Configuration

All in `wikantik.properties`, overridable in `wikantik-custom.properties`:

| Property | Default | Meaning |
|---|---|---|
| `wikantik.llm_activity.enabled` | `true` | Install recording decorators. When false, raw clients are used. |
| `wikantik.llm_activity.window_minutes` | `60` | Age window for retention. |
| `wikantik.llm_activity.max_records` | `5000` | Hard count cap on the ring buffer. |
| `wikantik.llm_activity.payload_chars` | `500` | Truncation length for previews. |

## Error handling

- Recorder internal failures: caught, `LOG.warn`, never propagated.
- Decorators rethrow delegate exceptions unchanged — no swallowing.
- Servlet: standard `RestServletBase` error handling.

## Testing

Test-driven — failing test first for each unit.

- **`LlmActivityLogTest`** — `begin` -> `succeed`/`fail` transitions; count-cap
  and age-window eviction; in-flight records never age-evicted; newest-first
  snapshot; payload truncation at the configured length; concurrent writes from
  multiple threads (judge-pool simulation).
- **`RecordingEntityExtractorTest` / `RecordingProposalJudgeTest` /
  `RecordingEmbeddingClientTest`** — delegates correctly; records `OK` on
  success and `ERROR` + rethrow on exception; a recorder exception does not
  break the call; async embedding finalizes on future success *and* failure.
- **`AdminLlmActivityResourceTest`** — GET returns snapshot JSON; `limit`
  honored; disabled state reported.
- **`LlmActivityTab.test.jsx`** — renders rows; in-flight highlight; filters;
  poll-rate switch; disabled notice.
- No DB schema -> **no migration**. Not an MCP write surface -> no IT pairing
  required, but the full integration-test reactor runs before commit.

## Open items / optional follow-ups

- Surfacing real API token counts (Ollama `prompt_eval_count`/`eval_count`,
  Claude `usage.*`) requires threading them through the client result types —
  deferred; the `inputTokens`/`outputTokens` fields exist now and stay null
  until then.
- Distinguishing save-time vs bootstrap extraction is not possible from the
  decorator (same extractor instance); a `trigger` dimension could be added
  later if needed.
