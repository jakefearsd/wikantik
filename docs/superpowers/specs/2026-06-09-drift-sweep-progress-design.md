# Drift Sweep Progress Bar — Design

**Status:** Approved 2026-06-09.
**Goal:** The drift sweep (`/admin/drift`) currently shows only a disabled *Run sweep
now* button while a sweep runs. Expose live sweep progress from `DriftSweepService`
and render a determinate progress bar in `AdminDriftPage`, truthful for both
manually-triggered and scheduled (post-rebuild) sweeps.

Companion to [2026-06-09-drift-dashboard-design.md](2026-06-09-drift-dashboard-design.md)
(the feature this extends — shipped 2026-06-09).

## Approach decision

Polled status endpoint + volatile counters. Rejected: SSE/WebSocket push (new
infrastructure for a once-nightly seconds-to-minutes job) and an indeterminate
spinner (doesn't indicate progress).

## Backend — `DriftSweepService` (wikantik-main, `com.wikantik.drift`)

New public record + accessor:

```java
public record SweepProgress( boolean running, String phase, int pagesScanned, int totalPages ) {}
public SweepProgress progress();
```

Backed by volatile fields updated inside `runSweep`:

- `totalPages` set immediately after page enumeration (`getAllPages().size()`).
- `pagesScanned` incremented per visited page during the frontmatter pass (the
  same counter the sweep already keeps, mirrored into the volatile field).
- `phase` walks `"frontmatter" → "shacl" → "persisting"`; all fields reset
  (running=false, zeros, phase null) in the existing `finally` that releases the
  single-flight flag — so a failed sweep also resets progress.
- Idle service → `SweepProgress(false, null, 0, 0)`.

No persistence, no new tables — progress is ephemeral in-memory state.

## REST — `AdminDriftResource` (wikantik-rest)

New action on the existing servlet (no web.xml change — `/admin/drift/*` already
mapped, AdminAuthFilter already gates it):

| Endpoint | Behavior |
|---|---|
| `GET /admin/drift/status` | `{running, phase, pagesScanned, totalPages}` — camelCase, `phase` JSON null when idle (serializeNulls Gson, as the summary endpoint). Same 503 guard as the other actions. |

## UI — `AdminDriftPage.jsx`

- **While sweeping:** poll `GET /admin/drift/status` every 1 s (replacing the
  current 2 s `summary` polling as the completion detector). Render a determinate
  progress bar: width = `pagesScanned/totalPages`, label like
  `"84 / 312 pages — validating frontmatter"`. The `shacl` and `persisting`
  phases render the bar full with their phase label ("checking SHACL
  conformance…", "saving snapshot…") — they are brief and have no per-page
  granularity.
- **Completion (race-safe):** `running` alone is not a safe signal — `triggerAsync`
  returns 202 before the daemon thread sets the flag, and a fast sweep can finish
  before the first poll ever sees `running=true`. Rule: on each tick poll `status`;
  while `running=true`, update the bar. When `running=false`, fetch `summary` —
  if `sweptAt` differs from the pre-trigger value the sweep is done (refresh trend,
  clear the drill-down cache, hide the bar, re-enable the button); if unchanged
  (startup window), keep polling. Bound the loop as today (max tries → error
  message).
- **Mount check:** on mount, fetch `status` once alongside summary/trend; if a
  sweep is already running (e.g. the nightly post-rebuild sweep), enter the same
  sweeping state — bar visible, *Run sweep now* disabled. The bar is truthful
  regardless of who started the sweep.
- **Component:** reuse a progress primitive from `src/components/ui/` if one
  exists; otherwise a small div-based bar (outer track + inner fill width %)
  styled in the existing `admin.css`. `data-testid="drift-progress"` on the bar,
  accessible via `role="progressbar"` + `aria-valuenow/min/max`. No new
  dependencies.

## Error handling

- A status poll that fails mid-sweep does not abort the sweeping UI state —
  log/ignore and try again on the next tick (the bounded try counter still
  applies, so a dead server eventually surfaces the existing timeout message).
- Service-level: progress fields are reset in `finally`, so no failure path can
  leave a stale "running" status; `/status` itself never throws for idle state.

## Testing

- **Unit (service):** latch-hold a page read mid-sweep → assert
  `progress()` reports running, phase `frontmatter`, correct totals; after
  completion → idle zeros; after a repository failure → idle zeros (reset in
  finally).
- **Unit (resource):** `/status` shape for running and idle snapshots; 503 guard.
- **Vitest:** run-now shows the bar with the correct fraction and phase label
  (fake timers, as the existing poll test); mount-while-running shows the bar
  and disables the button; completion hides the bar and refreshes; aria
  attributes present.
- Full sequential IT reactor gates the commit (house rule); no new IT is needed —
  `AdminDriftIT` already exercises the sweep end-to-end and the status endpoint
  is additive (its poll loop may optionally assert `/status` returns 200).

## Out of scope

- Progress persistence or history.
- Per-page granularity for the SHACL/persist phases.
- Push-based updates.
