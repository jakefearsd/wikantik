# LaTeX Math Validation — Design

**Date:** 2026-06-11
**Status:** Approved (Phase 1 scoped; save-path wiring deferred)
**Trigger:** `FastenerEngineering` page renders math as broken literal text.

## Problem

The `FastenerEngineering` page exhibits broken math rendering. Investigation
shows the root cause is **not** invalid LaTeX — the LaTeX content is fine. The
bug is **delimiter placement**. Line 28 reads:

```
...and friction:$$\text{T} = \text{F}_p \left( ... \right)$$Where$\text{P}$is the thread pitch,$\mu_t$and...
```

Two distinct failure modes appear:

1. **Display `$$…$$` glued inline against text** (not on its own line).
   `DisplayMathPreProcessor` only converts `$$` that occupies an entire line, so
   this block is never turned into a ` ```math ` fence. `InlineMathParser` then
   sees `$$`, treats it as display, and skips it. The result renders as **literal
   `$$\text{T}…$$` text** — the headline breakage.
2. **Inline `$…$` glued to surrounding words** (`Where$\text{P}$is`). These parse
   but render cramped/ambiguously.

Neither is a LaTeX-syntax error. KaTeX never gets a chance to validate them
because the math never becomes math. This reframes the work: the dominant class
of breakage is a **markdown-structure / delimiter** problem, distinct from
**LaTeX-syntax** problems (`\frac{1}` missing an argument) that KaTeX would
reject.

## Rendering stack (current)

- **Server (Java/Flexmark):** `DisplayMathPreProcessor` rewrites line-isolated
  `$$…$$` to ` ```math ` fences. `InlineMathParser` rewrites `$…$` to inline math
  nodes. Output is HTML spans `.math-inline` / `.math-display`.
- **Client (KaTeX):** `wikantik-frontend/src/utils/math.js` `renderMath()` runs
  KaTeX with `throwOnError: false` — unrenderable LaTeX silently renders red, and
  nothing is ever blocked.

There is no save-time math validation today.

## Decisions

| Question | Decision |
|----------|----------|
| What to catch | **Both tiers, separated:** delimiter/structure problems AND LaTeX-syntax errors. |
| Server-side syntax check (for agent/MCP saves, no browser KaTeX) | **Pragmatic Java linter**, pinned by the corpus. No new heavy dependency. Approximate vs real KaTeX — the corpus *is* the spec. |
| Severity | **Block structure (422), warn syntax (savable).** Mirrors the existing frontmatter-validation precedent and avoids locking users out on linter edge-cases. |
| Phase 1 scope | **Corpus + validators as pure units only.** Save-path wiring deferred until the validators surface the awkward edges. |

## Architecture — two tiers, one shared corpus

```
            ┌──────────────────── shared corpus (test resource) ────────────────────┐
            │  ~50 valid + ~50 invalid snippets, each tagged:                         │
            │  { id, source, category: structure|syntax,                             │
            │    structureExpect: ok|error, katexExpect: ok|error, linterExpect: ok|warn } │
            └───────────┬──────────────────────────────────────────────┬─────────────┘
                        │ consumed by                                   │ consumed by
              Java JUnit (server validators)                  JS vitest (editor KaTeX)
```

One corpus, two consumers. This guarantees the editor's KaTeX and the server
linter are graded against the *same* spec, and documents exactly where the
pragmatic Java linter diverges from real KaTeX. A `katexExpect: error` /
`linterExpect: ok` row is a Java-linter blind spot — **tracked as a TODO to
close**, not an accepted permanent divergence. The corpus is the running
inventory of these gaps; closing them (tightening the linter) is follow-up work
informed by reading the KaTeX support surface.

## Components

### `MathSpanExtractor` (Java, wikantik-main)
The single place that understands delimiters. Pulls math regions out of a page
body: display `$$…$$`, inline `$…$`, and ` ```math ` fences. Returns each span
with its kind and line/column position. Both validators consume its output;
neither re-parses delimiters itself.

**Code regions are excluded.** `$$…$$` and `` `$…$` `` that appear inside fenced
code blocks (```` ``` ````, `~~~`) or inline `` `code` `` spans are **not** math
— they are literal examples (a page documenting math syntax, like this very
spec). The extractor must skip those regions. This is the single most important
false-positive defense and is a tested requirement.

### `MathStructureValidator` (Java) — ERROR, blocking
Catches the FastenerEngineering class:
- display `$$…$$` glued inline against text (not on its own line);
- unterminated `$$` openers;
- empty spans;
- math glued to words with no surrounding whitespace.

**Conservative on bare-`$` balance** — see the currency caveat.

### `LatexSyntaxLinter` (Java) — WARNING, savable
Pragmatic checks pinned by the corpus:
- balanced `{}`;
- `\left`/`\right` pairing;
- `\begin{x}`/`\end{x}` matching;
- known-command allowlist.

Approximate vs KaTeX by design. Produces warnings only — never blocks.

### Editor tier (JS, deferred to Phase 3)
`PageEditor` runs real KaTeX (`throwOnError: true`) per span and shows inline
errors live before save. This is the *authoritative* syntax check for the UI
path. The JS vitest corpus mirror lives here.

## The currency caveat (why "block structure" is narrowed)

`$5 and $10` in prose is dollars, not broken math. Bare-`$`-count imbalance
therefore cannot be a hard error without false positives. **Structure ERRORS
fire only on high-confidence patterns:**
- a `$$…$$` whose content contains backslash-commands but is not line-isolated;
- an unterminated `$$` opener.

Ambiguous bare-`$` balance produces a **warning, not a block**. The corpus
includes currency/prose cases as *valid* to lock this in.

## Phase 2 — save-path wiring: blocking + pinpointing

Designed 2026-06-11. Two priorities, both from the maintainer: **false positives
are worse than a few escapes** (prefer letting questionable content through over
blocking legitimate refinement), and **when we do block, pinpoint the exact
offending text** so the author can see and debug it.

### Blocking bar — exactly two patterns

Only two high-confidence "renders as garbage" patterns hard-block (422, page not
written). Everything else is a savable warning.

| Pattern | Code | Why it blocks |
|---|---|---|
| Display `$$…$$` **not line-isolated** AND content contains a `\command` | `math.display.notIsolated` | The FastenerEngineering bug — renders as literal `$$…$$` text |
| **Unterminated `$$`** opener (no matching close) | `math.display.unterminated` | Swallows the rest of the document into one math block |

Everything else **warns, never blocks**: empty spans, inline `$…$` glued to
words, bare-`$` imbalance, and *all* `LatexSyntaxLinter` findings. A
cramped-but-rendering formula must never stand in the way of editing. Both
blocking rules are void inside code regions (see `MathSpanExtractor`).

> Residual false-positive: `$$\frac{a}{b}$$` written in *plain prose* (no
> backticks) as an example still trips `math.display.notIsolated`. Accepted —
> rare on a wiki, and wrapping the example in backticks is the correct fix
> anyway.

### Location-rich violation, computed once

`MathValidationPageFilter` mirrors `SchemaValidationPageFilter`: `preSave` runs
`MathStructureValidator` (ERROR → throw → 422) then `LatexSyntaxLinter`
(WARNING → stash on `FrontmatterWarningSink` → returned on the 200). It stays in
the existing `{ error, violations[] }` envelope (locus `__math__`) so the UI's
422/warning plumbing works unchanged — but each math violation carries a
`location` block the server fills in:

```jsonc
{
  "field": "__math__",
  "code": "math.display.notIsolated",
  "severity": "error",
  "message": "Display math ($$…$$) is glued to surrounding text and will render as literal text. Put the $$ delimiters on their own lines.",
  "location": {
    "line": 14, "column": 18,           // body-relative (matches what CodeMirror shows)
    "endLine": 14, "endColumn": 119,
    "startOffset": 612, "endOffset": 713,
    "excerpt": "…friction:$$\\text{T} = \\text{F}_p \\left( … \\right)$$Where…",
    "caret":   "          ^^                                      ^^"
  }
}
```

The server computes `excerpt` + `caret` **once**, so agents/MCP and raw clients
get the same compiler-style pointer the editor does, from one code path.

**Location is body-relative.** The CodeMirror editor shows the body, not the
frontmatter. The filter receives full content (frontmatter + body), so it
subtracts the frontmatter span to report body-relative line/column/offsets.

### Two consumers of `location`

- **UI (Phase 2, full pinpoint):** on a 422, `startOffset/endOffset` →
  CodeMirror selection + `scrollIntoView` + a transient highlight decoration;
  the `ValidationSummary` row shows `message` + `excerpt` and a **Jump** button
  (new `jumpToRange`, parallel to the frontmatter `jumpToField`, which targets a
  form field and is wrong for body math).
- **Agents/MCP & raw clients:** render `message` + `excerpt` + `caret` as text —
  a self-contained pointer, no editor required. Cargo IT asserts the refusal
  payload cites the offending span (per the MCP write-surface convention).

Gated by `wikantik.math.enforcement.enabled` (default `true`). Covers UI PUT and
MCP because filters run on every save path.

## Phasing

- **Phase 1 (this spec):** shared corpus + `MathSpanExtractor`,
  `MathStructureValidator`, `LatexSyntaxLinter` as pure units, TDD, graded by the
  ~50/50 corpus. `FastenerEngineering` line 28 is a named regression fixture.
  **Deliverable: the spec of what we render is decided, expressed as passing
  tests.** No save-path wiring.
- **Phase 2 (designed; build deferred until Phase 1 surfaces the awkward
  edges):** wire `MathValidationPageFilter` into the save pipeline (UI PUT +
  MCP) with the two-pattern blocking bar and the location-rich violation.
  Editor pinpoint: CodeMirror highlight + scroll + a **Jump** button on 422.
  Unit test + Cargo IT; the refusal payload cites the offending span + `caret`.
  See "Phase 2 — save-path wiring" above.
- **Phase 3 (deferred):** editor *live* (as-you-type) KaTeX feedback + the JS
  vitest corpus mirror. Distinct from the Phase-2 on-save pinpoint.

## Testing

- Parameterized JUnit over the shared corpus resource (one assertion path per
  `structureExpect` / `linterExpect` column).
- `FastenerEngineering` as an explicit regression fixture.
- Fixing the `FastenerEngineering` page itself is the proof the validator's
  errors are actionable (page edit, not part of the validator code).

## Out of scope (Phase 1)

- Save-path / MCP wiring (Phase 2).
- Editor UX and the JS corpus mirror (Phase 3).
- Running real KaTeX server-side (GraalJS) — explicitly rejected in favor of the
  pragmatic Java linter.
- Changing the renderer's `throwOnError: false` behavior.

## Open TODOs

- **Close Java-linter blind spots.** Each corpus row with `katexExpect: error`
  and `linterExpect: ok` is a syntax error the pragmatic linter currently misses.
  Inventory them in the corpus now; tighten the linter to close them as a
  follow-up, after reading the KaTeX support surface to decide which are worth
  catching server-side vs leaving to the editor's real-KaTeX check.
