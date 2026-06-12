# Math-render lint rules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the save-time math validator catch the two rendering-defect classes it currently misses — standalone single-line `$$…$$` display math (ERROR) and literal currency `$` parsed as inline math (WARNING) — without introducing false positives.

**Architecture:** Both rules live in `MathStructureValidator` (already the body-scanning "math structure" validator). Rule 1 fixes the existing `math.display.notIsolated` isolation check; Rule 2 adds a new `math.inline.prose` WARNING. `MathValidationPageFilter` wiring is unchanged — it already maps ERROR→block (422/MCP refusal) and WARNING→`ContentWarningSink`. A corpus pre-sweep clears 5 pages of single-line `$$` so the stricter ERROR doesn't break `ShippedPagesMathHealthTest`.

**Tech Stack:** Java 21, JUnit 5, Maven (surefire). Run a single test class with `mvn -q -pl wikantik-main test -Dtest=ClassName`.

**Context for the implementer:**
- `MathStructureValidator.validate(String body)` returns `List<MathViolation>` (`code`, `severity`, `message`, `range`). `MathViolation` is a record-like type; `MathSourceRange.from(body, startOffset, endExclusive)` builds the range.
- `CodeRegions.scan(body)` → `code.isMasked(offset)` reports whether an offset is inside a fenced/inline code region (so `` `$$x$$` `` and ```` ```sql DO $$…$$``` ```` are ignored).
- `Severity` is `com.wikantik.api.frontmatter.schema.Severity` with `ERROR` / `WARNING`.
- ERROR blocks the save; WARNING does not. `ShippedPagesMathHealthTest` walks `docs/wikantik-pages` and fails if any page yields a math **ERROR** (it ignores WARNINGs).
- The proven prod-push path (admin MCP `update_page`) and a content transform already exist at `/tmp/mcp/` from the 2026-06-12 render audit; reuse `client.py` + the isolation transform.

---

## File Structure

- **`wikantik-main/.../markdown/extensions/math/MathStructureValidator.java`** (modify) — Rule 1 isolation fix (Task 2) + Rule 2 `checkInlineProse` (Task 3).
- **`wikantik-main/.../test/.../math/MathStructureValidatorTest.java`** (modify) — new TP/FP cases (Tasks 2–3); update `allowsCurrencyProse` → now a WARNING (Task 3).
- **5 corpus markdown files** (modify) — single-line `$$` isolation (Task 1): `BayesianReasoning.md`, `CalculusRefreshForCS.md`, `PulleySystems.md`, `BlackScholesModel.md`, `MajorityQuorum.md`.

---

## Task 1: Corpus pre-sweep — isolate single-line `$$` in 5 pages

Rule 1 (Task 2) turns single-line `$$…$$` into an ERROR, and `ShippedPagesMathHealthTest` fails on any corpus ERROR. These 5 pages have single-line/glued `$$` (the render audit missed them because each also has a correctly-isolated block). Fix them first.

**Files:**
- Modify: `docs/wikantik-pages/{BayesianReasoning,CalculusRefreshForCS,PulleySystems,BlackScholesModel,MajorityQuorum}.md`

- [ ] **Step 1: Confirm the straggler set**

Run: `grep -rlE '\$\$.+\$\$' docs/wikantik-pages/*.md`
Expected: exactly these 5 files:
```
docs/wikantik-pages/BayesianReasoning.md
docs/wikantik-pages/CalculusRefreshForCS.md
docs/wikantik-pages/PulleySystems.md
docs/wikantik-pages/BlackScholesModel.md
docs/wikantik-pages/MajorityQuorum.md
```

- [ ] **Step 2: Apply the isolation transform to the 5 files**

Run this Python (the currency-safe escape + display isolation proven in the render audit):

```python
import re, glob, os
PAGES="docs/wikantik-pages"
TARGETS=["BayesianReasoning","CalculusRefreshForCS","PulleySystems","BlackScholesModel","MajorityQuorum"]
def transform(text):
    prot=[]
    def stash(m): prot.append(m.group(0)); return f"\x00{len(prot)-1}\x00"
    t=re.sub(r"```.*?```|`[^`]*`", stash, text, flags=re.S)          # protect code
    t=re.sub(r"(?<!\$)\$(?!\$)(?=\d)", r"\\$", t)                      # currency-safe escape (protects $$)
    t=re.sub(r"\$\$(.+?)\$\$", lambda m:"\n\n$$\n"+m.group(1).strip()+"\n$$\n\n", t, flags=re.S)  # isolate display
    t=re.sub(r"\n{3,}","\n\n",t)
    t=re.sub(r"\x00(\d+)\x00", lambda m: prot[int(m.group(1))], t)
    return t
for n in TARGETS:
    p=f"{PAGES}/{n}.md"; orig=open(p,encoding="utf-8").read(); new=transform(orig)
    open(p,"w",encoding="utf-8").write(new)
    print(n, "changed" if new!=orig else "UNCHANGED")
```

- [ ] **Step 3: Verify no single-line `$$` remain**

Run: `grep -rlE '\$\$.+\$\$' docs/wikantik-pages/*.md | wc -l`
Expected: `0`

- [ ] **Step 4: Push the 5 corrected pages to production via MCP**

Using the existing `/tmp/mcp/client.py` (admin MCP, key from `.env.prod`), for each of the 5: `read_page` → `update_page(content=<local file>, expectedContentHash=<hash>)`. Then re-fetch `https://wiki.wikantik.com/api/pages/<name>?render=true` and confirm `contentHtml` contains `math-display` and no leaked `$` outside currency. (Operational; the git fix alone is what gates the test.)

- [ ] **Step 5: Commit**

```bash
git add docs/wikantik-pages/BayesianReasoning.md docs/wikantik-pages/CalculusRefreshForCS.md \
        docs/wikantik-pages/PulleySystems.md docs/wikantik-pages/BlackScholesModel.md \
        docs/wikantik-pages/MajorityQuorum.md
git commit -m "fix(content): isolate single-line \$\$ display math in 5 more pages (pre-sweep for stricter validator)"
```

---

## Task 2: Rule 1 — single-line / glued `$$` → ERROR

Fix the isolation check so a `$$` delimiter must be alone on its own line (whitespace on **both** sides), and drop the `\command`-content guard.

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/markdown/extensions/math/MathStructureValidator.java`
- Test: `wikantik-main/src/test/java/com/wikantik/markdown/extensions/math/MathStructureValidatorTest.java`

- [ ] **Step 1: Write the failing tests**

Add these methods to `MathStructureValidatorTest` (before the closing `}`):

```java
    @Test
    void blocksSingleLineDisplayWithCommand() {
        assertTrue(hasError(validator.validate("intro\n$$E = \\frac{a}{b}$$\noutro"),
                "math.display.notIsolated"));
    }

    @Test
    void blocksSingleLineDisplayWithoutBackslash() {
        // The ActorModelProgramming class — no backslash, previously missed.
        assertTrue(hasError(validator.validate("intro\n$$S_{t+1} = f(S_t, M)$$\nouttro"),
                "math.display.notIsolated"));
    }

    @Test
    void blocksGluedOpenIsolatedClose() {
        assertTrue(hasError(validator.validate("text $$\n x = y \n$$\nrest"),
                "math.display.notIsolated"));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl wikantik-main test -Dtest=MathStructureValidatorTest`
Expected: the 3 new tests FAIL (single-line `$$` and the glued-open case are not yet flagged; the no-backslash one is doubly missed).

- [ ] **Step 3: Replace the isolation check**

In `MathStructureValidator.java`, replace this block (the `openIsolated`/`closeIsolated`/`if` inside the `for` loop):

```java
            final boolean openIsolated = lineIsBlankExceptDelimAt(body, open, true);
            final boolean closeIsolated = lineIsBlankExceptDelimAt(body, close, false);
            if ((!openIsolated || !closeIsolated) && content.contains("\\")) {
                out.add(new MathViolation("math.display.notIsolated", Severity.ERROR,
                        "Display math ($$…$$) is glued to surrounding text and will render as "
                                + "literal text. Put the $$ delimiters on their own lines.",
                        MathSourceRange.from(body, open, close + 2)));
            }
```

with:

```java
            if (!delimiterAloneOnLine(body, open) || !delimiterAloneOnLine(body, close)) {
                out.add(new MathViolation("math.display.notIsolated", Severity.ERROR,
                        "Display math ($$…$$) is glued to surrounding text and will render as "
                                + "literal text. Put the $$ delimiters on their own lines.",
                        MathSourceRange.from(body, open, close + 2)));
            }
```

- [ ] **Step 4: Replace the predicate**

In `MathStructureValidator.java`, replace the entire `lineIsBlankExceptDelimAt` method:

```java
    /** True when the {@code $$} delimiter at {@code pos} is alone on its line (ignoring whitespace). */
    private static boolean lineIsBlankExceptDelimAt(final String body, final int pos, final boolean checkBefore) {
        if (checkBefore) {
            int i = pos - 1;
            while (i >= 0 && body.charAt(i) != '\n') {
                if (!Character.isWhitespace(body.charAt(i))) { return false; }
                i--;
            }
            return true;
        }
        int i = pos + 2;   // char after "$$"
        while (i < body.length() && body.charAt(i) != '\n') {
            if (!Character.isWhitespace(body.charAt(i))) { return false; }
            i++;
        }
        return true;
    }
```

with:

```java
    /**
     * True when the {@code $$} at {@code pos} is alone on its own line — whitespace-only on BOTH
     * sides (so the content is on a different line). A single-line {@code $$x$$} fails this because
     * content follows the open (and precedes the close) on the same line.
     */
    private static boolean delimiterAloneOnLine(final String body, final int pos) {
        for (int i = pos - 1; i >= 0 && body.charAt(i) != '\n'; i--) {
            if (!Character.isWhitespace(body.charAt(i))) { return false; }
        }
        for (int i = pos + 2; i < body.length() && body.charAt(i) != '\n'; i++) {
            if (!Character.isWhitespace(body.charAt(i))) { return false; }
        }
        return true;
    }
```

Also update the class Javadoc bullet for `notIsolated` to drop the "contains a `\\command`" qualifier:

```java
 *   <li>ERROR {@code math.display.notIsolated}: a {@code $$…$$} pair whose delimiters are not each
 *       alone on their own line — it renders as literal text (the FastenerEngineering / single-line bug).</li>
```

- [ ] **Step 5: Run the validator tests**

Run: `mvn -q -pl wikantik-main test -Dtest=MathStructureValidatorTest`
Expected: PASS — all tests, including the 3 new ones and the existing `allowsLineIsolatedDisplay`, `ignoresDisplayInsideCodeFence`, `warnsOnEmptyDisplay`, `blocksInlineGluedDisplayWithCommand`, `blocksUnterminatedDisplay`. (`allowsCurrencyProse` still passes — Rule 2 isn't added yet.)

- [ ] **Step 6: Run the corpus health test (now clean after Task 1)**

Run: `mvn -q -pl wikantik-main test -Dtest=ShippedPagesMathHealthTest`
Expected: PASS — no corpus page yields a math ERROR. (If it lists offenders, those are single-line `$$` pages Task 1 missed: isolate them with the Task 1 transform, push to prod, re-run.)

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/markdown/extensions/math/MathStructureValidator.java \
        wikantik-main/src/test/java/com/wikantik/markdown/extensions/math/MathStructureValidatorTest.java
git commit -m "feat(math): flag single-line/glued \$\$ display math as ERROR (both-sides isolation, no backslash guard)"
```

---

## Task 3: Rule 2 — prose inside inline `$…$` → WARNING

Add a precise, currency-aware WARNING: an inline `$…$` pair whose content is prose (a stopword, no LaTeX command) — the literal-`$`-as-math defect.

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/markdown/extensions/math/MathStructureValidator.java`
- Test: `wikantik-main/src/test/java/com/wikantik/markdown/extensions/math/MathStructureValidatorTest.java`

- [ ] **Step 1: Write the failing tests (and update the obsolete one)**

In `MathStructureValidatorTest`, **replace** `allowsCurrencyProse` with `warnsOnCurrencyProse`, and add the helper + TP/FP cases:

```java
    private boolean hasWarning(final List<MathViolation> v, final String code) {
        return v.stream().anyMatch(x -> x.severity() == Severity.WARNING && x.code().equals(code));
    }

    @Test
    void warnsOnCurrencyProse() {
        // Was allowsCurrencyProse: currency $ wrapping prose now WARNs (never blocks).
        assertTrue(hasWarning(validator.validate("It costs $5 and $10 to ship, total $15."),
                "math.inline.prose"));
    }

    @Test
    void warnsOnCurrencyWrappingClause() {
        assertTrue(hasWarning(validator.validate("You save $500 on every $1000 spent."),
                "math.inline.prose"));
        assertTrue(hasWarning(validator.validate("gold hit $3,800 per ounce and silver $48 today"),
                "math.inline.prose"));
    }

    @Test
    void doesNotWarnOnRealInlineMath() {
        for (final String body : new String[]{
                "the sum $x + y$ and product $a \\cdot b$",
                "let $f(n)$ be the cost and $g(n)$ the heuristic",
                "the first $3$ terms with $a_1 + b_2$",
                "compute $max(x, y)$ then $\\frac{a}{b}$",
                "Einstein wrote $E = mc^2$ in 1905"}) {
            assertEquals(List.of(), validator.validate(body).stream()
                    .filter(x -> x.code().equals("math.inline.prose")).toList(),
                    "should not warn on: " + body);
        }
    }

    @Test
    void doesNotWarnOnEscapedCurrency() {
        assertEquals(List.of(), validator.validate("costs \\$500 and \\$1000 per unit").stream()
                .filter(x -> x.code().equals("math.inline.prose")).toList());
    }

    @Test
    void doesNotWarnOnProseBetweenClosedPairs() {
        assertEquals(List.of(), validator.validate("let $g(n)$ be the cost from start and $h(n)$ the rest")
                .stream().filter(x -> x.code().equals("math.inline.prose")).toList());
    }
```

Also DELETE the old test:

```java
    @Test
    void allowsCurrencyProse() {
        final String body = "It costs $5 and $10 to ship, total $15.";
        assertEquals(List.of(), validator.validate(body));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl wikantik-main test -Dtest=MathStructureValidatorTest`
Expected: the `warnsOnCurrencyProse` / `warnsOnCurrencyWrappingClause` tests FAIL (no `math.inline.prose` emitted yet); the `doesNotWarn*` tests pass trivially (nothing emitted).

- [ ] **Step 3: Add imports + the stopword pattern field**

In `MathStructureValidator.java`, add to the imports:

```java
import java.util.regex.Pattern;
```

and add this field inside the class (above `validate`):

```java
    /** Whole-word English stopwords that mark inline-$ content as prose, not math. */
    private static final Pattern PROSE_STOPWORD = Pattern.compile(
            "(?i)\\b(and|the|or|is|are|of|to|for|with|per|at|on|in|from|by|as)\\b");
```

- [ ] **Step 4: Call Rule 2 from `validate`**

In `MathStructureValidator.java`, in `validate(...)`, immediately before `return out;`, add:

```java
        out.addAll(checkInlineProse(body, code, marks));
```

(`marks` is the `List<Integer>` of `$$` offsets already collected earlier in `validate`.)

- [ ] **Step 5: Add the `checkInlineProse` + `snippet` methods**

In `MathStructureValidator.java`, add these methods to the class:

```java
    /**
     * WARNING {@code math.inline.prose}: an inline {@code $…$} pair whose content is prose (contains an
     * English stopword and no {@code \\command}) — typically a literal currency {@code $} being parsed
     * as math. Display {@code $$} blocks and code regions are excluded; escaped {@code \\$} is skipped.
     */
    private List<MathViolation> checkInlineProse(final String body, final CodeRegions code,
                                                 final List<Integer> displayMarks) {
        final List<MathViolation> out = new ArrayList<>();
        // Mask positions covered by paired $$…$$ display blocks.
        final boolean[] inDisplay = new boolean[body.length()];
        for (int k = 0; k + 1 < displayMarks.size(); k += 2) {
            final int end = Math.min(displayMarks.get(k + 1) + 2, body.length());
            for (int i = displayMarks.get(k); i < end; i++) { inDisplay[i] = true; }
        }
        // Collect true single-$ offsets (not code-masked, not in display, not escaped, not part of $$).
        final List<Integer> singles = new ArrayList<>();
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) != '$' || code.isMasked(i) || inDisplay[i]) { continue; }
            if (i > 0 && body.charAt(i - 1) == '\\') { continue; }                 // escaped \$
            if ((i > 0 && body.charAt(i - 1) == '$')
                    || (i + 1 < body.length() && body.charAt(i + 1) == '$')) { continue; }  // part of $$
            singles.add(i);
        }
        // Pair left-to-right (renderer order); flag pairs whose content is prose.
        for (int k = 0; k + 1 < singles.size(); k += 2) {
            final int open = singles.get(k);
            final int close = singles.get(k + 1);
            final String content = body.substring(open + 1, close);
            if (!content.contains("\\") && PROSE_STOPWORD.matcher(content).find()) {
                out.add(new MathViolation("math.inline.prose", Severity.WARNING,
                        "Inline $…$ contains prose (\"" + snippet(content) + "\"); a literal '$' "
                                + "(e.g. currency) is likely being parsed as math — escape it as \\$.",
                        MathSourceRange.from(body, open, close + 1)));
            }
        }
        return out;
    }

    private static String snippet(final String s) {
        final String t = s.strip();
        return t.length() <= 40 ? t : t.substring(0, 39) + "…";
    }
```

- [ ] **Step 6: Run the validator tests**

Run: `mvn -q -pl wikantik-main test -Dtest=MathStructureValidatorTest`
Expected: PASS — all tests, including the new WARNING TPs and the `doesNotWarn*` FP-negatives, plus all Task 2 + original tests.

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/markdown/extensions/math/MathStructureValidator.java \
        wikantik-main/src/test/java/com/wikantik/markdown/extensions/math/MathStructureValidatorTest.java
git commit -m "feat(math): WARN on prose inside inline \$…\$ (currency parsed as math), false-positive-guarded"
```

---

## Task 4: Full verification

Confirm the whole math package and the dependent filter/corpus tests are green, then a module build.

**Files:** none (verification only).

- [ ] **Step 1: Run the full math package test suite**

Run: `mvn -q -pl wikantik-main test -Dtest='com.wikantik.markdown.extensions.math.*'`
Expected: PASS — `MathStructureValidatorTest`, `MathValidationPageFilterTest`, `MathValidationCorpusTest`, `ShippedPagesMathHealthTest`, `MathSpanExtractorTest`, `DisplayMathPreProcessorTest`, `LatexSyntaxLinterTest` all green. (`MathSpanExtractorTest` and `DisplayMathPreProcessorTest` exercise different classes; their currency strings are unaffected by Rule 2.)

- [ ] **Step 2: Build wikantik-main to confirm nothing else regressed**

Run: `mvn -q -pl wikantik-main -am test-compile && mvn -q -pl wikantik-main test -Dtest='MathStructureValidatorTest,MathValidationPageFilterTest,ShippedPagesMathHealthTest'`
Expected: BUILD SUCCESS, tests PASS.

- [ ] **Step 3: No commit** — all code committed in Tasks 1–3.

---

## Self-Review Notes (for the author — not a step)

- **Spec coverage:** Rule 1 isolation fix + drop `\` guard (Task 2), Rule 2 inline-prose WARNING with stopword/no-backslash/escaped-`\$` guards (Task 3), corpus pre-sweep of the 5 stragglers (Task 1), the `allowsCurrencyProse`→WARNING behavior change (Task 3 Step 1), hard TP/FP tests for both rules (Tasks 2–3), regression tests green (Task 4). All spec sections covered.
- **Type/name consistency:** `delimiterAloneOnLine`, `checkInlineProse`, `snippet`, `PROSE_STOPWORD`, codes `math.display.notIsolated` / `math.inline.prose`, `MathViolation`, `MathSourceRange.from(body, start, endExclusive)`, `CodeRegions.isMasked` used identically across tasks. `marks` is the existing local in `validate` passed to `checkInlineProse`.
- **No placeholders:** every step shows complete code/commands.
