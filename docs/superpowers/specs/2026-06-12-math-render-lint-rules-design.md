# Math-render lint rules: single-line `$$` + prose-in-inline-`$`

**Date:** 2026-06-12
**Status:** Design approved, pending implementation
**Component:** `wikantik-main` — `com.wikantik.markdown.extensions.math.MathStructureValidator`

## Problem

The save-time math validator (`MathValidationPageFilter`, shipped 2.0.16) misses two
rendering-defect classes that broke ~156 production pages (see
`audits/2026-06-12-prod-formula-rendering.md`):

1. **Standalone single-line display math** — `$$E = mc^2$$` on one line. The renderer's
   `DisplayMathPreProcessor` only recognizes `$$` alone on its own line, so a single-line
   block renders as two inline-`$` toggles, inverting inline parity for the rest of the page.
   The existing `math.display.notIsolated` rule does **not** catch this (see Root cause).
2. **Literal currency `$` parsed as math** — `$3,800` / `$5 and $10` on finance pages pair up
   as inline-math delimiters and wrap prose. The validator deliberately never checked single
   `$` (currency false-positive risk).

Both passed the validator, so they could recur on every future save.

## Root cause of the missed single-line case

`MathStructureValidator.validate` computes isolation with two *half*-checks:

```java
final boolean openIsolated  = lineIsBlankExceptDelimAt(body, open,  true);  // before open only
final boolean closeIsolated = lineIsBlankExceptDelimAt(body, close, false); // after close only
if ((!openIsolated || !closeIsolated) && content.contains("\\")) { ...ERROR... }
```

For a single-line `$$x = y$$`: nothing precedes the open `$$` and nothing follows the close
`$$`, so both half-checks pass → not flagged. The check never verifies that the content is on
a *different* line from the delimiters. The `content.contains("\\")` guard additionally misses
no-backslash content like `$$S_{t+1} = f(S_t, M)$$`.

## Design

Both rules live in `MathStructureValidator` (it already body-scans `$$` and owns "math
structure" concerns). `MathValidationPageFilter` wiring is unchanged — it already maps
ERROR → `ContentValidationException` (save blocked, 422 / MCP refusal) and WARNING →
`ContentWarningSink` (save succeeds, surfaced).

### Rule 1 — `math.display.notIsolated` → ERROR (fix)

Redefine "isolated": a `$$` delimiter is isolated iff it is **alone on its own line** —
whitespace-only on **both** sides. Replace the two half-checks with one predicate
`delimiterAloneOnLine(body, pos)` that returns true only when every character before `pos`
back to the previous `\n`, **and** every character after the `$$` to the next `\n`, is
whitespace. A display block is isolated iff both its delimiters are alone on their lines.

Flag any non-isolated `$$…$$` pair (code-masked) as ERROR. **Drop the
`content.contains("\\")` guard** — every non-isolated `$$` outside code renders broken
regardless of content. Message is unchanged ("…put the `$$` delimiters on their own lines").

The empty-block (`math.display.empty`, WARNING) and unterminated (`math.display.unterminated`,
ERROR) rules are unchanged. `CodeRegions` masking is unchanged, so SQL `DO $$…$$` in fenced
blocks and `` `$$x$$` `` inline-code examples are never flagged.

### Rule 2 — `math.inline.prose` → WARNING (new)

Detect a literal `$` (typically currency) being parsed as inline math by wrapping prose.

Algorithm:
1. Start from the body with **code regions masked** (`CodeRegions`) and **isolated `$$…$$`
   display blocks removed** (they render as display, not inline — reuse Rule 1's isolation
   predicate to identify them).
2. Walk the remaining text; skip any `$` that is **escaped** (immediately preceded by `\`).
   Pair the remaining single `$` left-to-right (1st opens, 2nd closes, …), matching the
   renderer's order. An odd trailing `$` is ignored.
3. For each `$…$` pair, flag it as `math.inline.prose` (WARNING) **iff** the content:
   - contains **no `\`** (LaTeX command), **and**
   - contains an English stopword as a whole word (case-insensitive), from the curated set
     `and the or is are of to for with per at on in from by as`.
4. Message: ``Inline `$…$` contains prose (\"<snippet>\"); a literal `$` (e.g. currency) is
   likely being parsed as math — escape it as `\$`.``

False-positive guards (why this is precise):
- **No-backslash** excludes `$\frac{a}{b}$`, `$\text{x}$`, `$x \le y$`.
- **Stopword whole-word** excludes `$x + y$`, `$f(n)$`, `$3$`, `$a_1$`, `$max(x,y)$`,
  `$E = mc^2$` (none contain a stopword).
- **Escaped `\$` skipped** — already-escaped currency (`\$500`) is never flagged.
- Prose that sits **between** correctly-closed pairs (`$g(n)$ is the cost $h(n)$`) is outside
  any pair → not flagged.
- WARNING severity means even a borderline hit (`$true or false$` — author should use
  `\text`/`\lor`) never blocks a save.

## Required pre-step — corpus sweep

Rule 1 becomes an ERROR, and `ShippedPagesMathHealthTest` asserts the shipped corpus produces
no math ERRORs. The 2026-06-12 render audit fixed 156 pages, but its detector could have
missed a single-line `$$` on a page that *also* has a correctly-isolated block (the good
block's `math-display` span masked the signal). Before enabling Rule 1:

1. Grep the corpus for single-line display math: a line matching `^\s*\$\$.+\$\$\s*$` (outside
   code fences).
2. Isolate any stragglers (the proven transform: split onto own lines with blank-line guards),
   verify locally, push to prod via MCP.
3. `ShippedPagesMathHealthTest` then enforces a clean corpus going forward.

## Testing (hard — the explicit requirement)

New `MathStructureValidatorTest` cases, each rule with paired true-positives and
false-positive-negatives:

**Rule 1 true positives (expect ERROR `math.display.notIsolated`):**
- single-line with command: `$$E = mc^2$$`
- single-line no-backslash: `$$S_{t+1} = f(S_t, M)$$`
- mid-line glued both sides: `text $$x = y$$ more text`
- glued open, isolated close: `intro $$\n x = y \n$$`

**Rule 1 false-positive negatives (expect NO `notIsolated`):**
- properly isolated block (``$$\nE = mc^2\n$$``)
- `$$` inside a ```sql fence (`DO $$ BEGIN … END $$;`)
- `$$x$$` inside inline code `` `$$x$$` ``
- empty block `$$\n$$` (expect WARNING `math.display.empty`, not the ERROR)

**Rule 2 true positives (expect WARNING `math.inline.prose`):**
- `You save $500 on every $1000 spent`
- `gold hit $3,800 per ounce and silver $48`
- currency wrapping a clause: `from $50 to $100 per year`

**Rule 2 false-positive negatives (expect NO `math.inline.prose`):**
- `$x + y$`, `$f(n)$`, `$3$`, `$a_1 + b_2$`, `$max(x, y)$`
- `$\frac{a}{b}$`, `$\text{Total}$`, `$x \le y$`
- escaped currency: `\$500 and \$1000`
- prose between closed pairs: `$g(n)$ is the cost from start and $h(n)$`
- isolated display block whose content has words: ``$$\n\text{Total} = a + b\n$$``

**Regression (must stay green):** existing `MathStructureValidatorTest`,
`MathValidationPageFilterTest`, `MathValidationCorpusTest`, `ShippedPagesMathHealthTest`.

## Files touched

- `wikantik-main/src/main/java/com/wikantik/markdown/extensions/math/MathStructureValidator.java`
  — Rule 1 isolation fix (drop `\` guard, both-sides predicate) + Rule 2 `checkInlineProse`.
- `wikantik-main/src/test/java/com/wikantik/markdown/extensions/math/MathStructureValidatorTest.java`
  — the TP/FP cases above.
- Corpus markdown stragglers found by the pre-step sweep (if any).

## Risks

- Dropping the Rule 1 `\` guard flags no-backslash glued/single-line `$$` that previously
  passed. This is intended (they render broken), but the corpus sweep + `ShippedPagesMathHealthTest`
  must be green before commit, or legitimate corpus saves would be blocked.
- Rule 2's stopword heuristic could flag an unusual inline expression that spells out an
  English operator word without `\` (e.g. `$p or q$`). Mitigated by WARNING severity (never
  blocks) and by the design choice that such expressions *should* use `\lor`/`\text`.
