# Extraction Admin UI — Design

Date: 2026-04-28
Status: Approved (brainstorming)

## Problem

The LLM-based entity extractor (`BootstrapEntityExtractionIndexer`) — the upstream of every row in `kg_proposals` and the only path that regenerates KG proposals across the entire corpus — has no admin UI. Operators today have three options:

1. Wait for `AsyncEntityExtractionListener` to fire on individual page saves.
2. Run `bin/kg-extract.sh` from a shell with DB access.
3. `curl -X POST http://localhost:8080/admin/knowledge/extract-mentions?force=true` with admin credentials and poll `GET` for status.

The REST endpoint (`AdminExtractionResource`, mapped in `web.xml` at `/admin/knowledge/extract-mentions[/*]`) is fully implemented and wired through `AdminAuthFilter`. What is missing is the React surface to drive it. The closest analogue, `IndexStatusTab.jsx`, already handles a similar long-running batch (Lucene + chunk embeddings rebuild) but its `ContentIndexRebuildService` deliberately does not invoke the LLM extractor.

## Goal

Add a new "Extraction" tab to the Knowledge admin page (`/admin/knowledge`) that lets an admin trigger a full-corpus entity extraction, watch its progress with a status tracker, cancel in flight, and optionally force re-extraction of already-processed pages.

Non-goals:

- Per-page extraction trigger from the UI (the save-time listener already covers single-page extraction).
- Switching extractor backend from the UI (`wikantik.knowledge.extractor.backend` is config-only, restart required).
- Partial-batch "regenerate proposals for these N pages" workflow.

## Placement

A new tab inside `AdminKnowledgePage.jsx`, inserted at index 1 — directly after `Proposals` and before `Node Explorer`. Final tab order:

```
Proposals | Extraction | Node Explorer | Edge Explorer | Content Embeddings | Hub Proposals | Hub Discovery
```

This keeps the upstream of the proposal review queue adjacent to the queue itself, so the relationship is obvious without disrupting the order users already know.

## Frontend

### New component: `wikantik-frontend/src/components/admin/ExtractionTab.jsx`

Modeled after `IndexStatusTab.jsx`. Polls `GET /admin/knowledge/extract-mentions` at `FAST_POLL_MS = 2000` while `state === 'RUNNING'`, otherwise `SLOW_POLL_MS = 10000`. Same `useRef`-driven cadence switch the rebuild tab uses.

The indexer's terminal-state model (per `BootstrapEntityExtractionIndexer.State`) is `IDLE | RUNNING | COMPLETED | ERROR` — there is no separate `STARTING` or `CANCELLED` state. Cancel is a cooperative flag: the loop finishes the in-flight page, then transitions to `COMPLETED` (with `processedPages < totalPages`). The UI surfaces the cancellation purely through the visible progress shortfall and a cancellation hint shown when the request was issued during the current run.

Layout:

- **Header.** State badge (`IDLE` / `RUNNING` / `COMPLETED` / `ERROR`). Elapsed time, `startedAt`, `finishedAt`, configured `concurrency`, and `extractorBackend` (label sourced from the small backend addition below). When the most recent cancel call was successful, also show *"Cancellation requested"* until `state` next leaves `RUNNING`.
- **Progress block** (rendered when `state !== 'IDLE'`).
  - *Pages* progress bar — `processedPages` / `totalPages`. `failedPages` rendered as a red sub-count beneath the bar.
  - *Chunks* progress bar — `processedChunks` / `totalChunks`. `failedChunks` as a red sub-count.
  - Counter row: `mentionsWritten`, `proposalsFiled`, `excludedSkipped`.
- **Controls.**
  - Primary button **"Extract Mentions"**. Disabled while `state === 'RUNNING'`. Click opens a confirm dialog: *"Run entity extraction over every page? This calls the LLM extractor and may take a long time."* On confirm, posts `?force=<checkbox>`.
  - Checkbox **"Force re-extract"** next to the button, default unchecked. When checked the confirm-dialog text becomes: *"Force re-extract every page, including ones already processed? This will re-run the LLM extractor over the entire corpus."*
  - Secondary button **"Cancel"**, rendered only when `state === 'RUNNING'`. Click opens a confirm dialog: *"Cancel the in-flight extraction run? The current page will finish; remaining pages will be skipped."* On confirm, sends `DELETE`.
- **Error panel.** If `lastError` is non-null, render the same red-bordered details panel `IndexStatusTab` uses for its `errors[]` (collapsible `<details>` with `lastError` as the body).
- **Disabled fallback.** If any GET/POST/DELETE returns `503`, render *"Entity extraction is not configured (check `wikantik.knowledge.extractor.backend`)."* and disable the controls.

### Edits

**`wikantik-frontend/src/components/admin/AdminKnowledgePage.jsx`:**

- Import `ExtractionTab`.
- Insert at `TABS[1]`:

  ```jsx
  { id: 'extraction', label: 'Extraction',
    description: 'Run the LLM-based entity extractor across every page to regenerate knowledge-graph proposals. Watch progress, cancel in flight, or force re-extraction of pages already processed.' },
  ```

- Add the conditional render: `{activeTab === 'extraction' && <ExtractionTab />}`.

**`wikantik-frontend/src/api/client.js`** — three methods inside the existing `knowledge` namespace:

- `getExtractionStatus()` → `GET /admin/knowledge/extract-mentions`.
- `startExtraction(force)` → `POST /admin/knowledge/extract-mentions?force=<bool>`. `force` defaults to `false`.
- `cancelExtraction()` → `DELETE /admin/knowledge/extract-mentions`.

## Backend

The REST surface and servlet wiring already exist. Two small additions:

1. **`AdminExtractionResource.statusToMap` extension.** Add an `extractorBackend` field — sourced from the `wikantik.knowledge.extractor.backend` property (`claude` / `ollama` / `disabled` / unset → `unknown`). Pure read-through, no behavior change.
2. **No DDL, no new managers, no new permission.** `AdminAuthFilter` already gates the path under `AllPermission`.

## Error model

The frontend handles three documented REST conditions explicitly (matching `IndexStatusTab`'s pattern):

| Verb | Status | UI behavior |
|------|--------|-------------|
| POST | `409 Conflict` | Inline error: *"An extraction run is already in progress."* The status snapshot in the response body is applied so the UI immediately reflects the in-flight run. |
| DELETE | `409 Conflict` | Inline error: *"No extraction run is currently in progress."* |
| any | `503 Service Unavailable` | Render the disabled-fallback panel; controls disabled. |
| any | other | Generic inline error, message taken from the response body. |

## Testing

- **Frontend, unit (Vitest).** New `ExtractionTab.test.jsx` covering: `IDLE` state shows the trigger button enabled and the cancel button hidden; `RUNNING` state shows both progress bars, counter row, and a visible cancel button (the trigger button is disabled); `ERROR` state shows the error panel; `COMPLETED` with `processedPages < totalPages` (post-cancellation shape) renders without crashing; `503` from the API shows the disabled fallback. Mocks `api.knowledge.getExtractionStatus / startExtraction / cancelExtraction`.
- **Backend, unit.** Existing `AdminExtractionResourceTest` already covers the REST contract — extend with a one-line assertion that the status payload includes `extractorBackend` keyed off the configured property.
- **No new IT.** The rebuild tab does not have a Selenide IT either; the contract is fully exercised at the unit layer.

## Open questions

None — design approved.

## Out of scope

- Per-page extraction trigger.
- Backend switching from the UI.
- Partial-batch extraction.
- Telemetry / metrics changes (the existing Prometheus gauges on the indexer already publish progress).
