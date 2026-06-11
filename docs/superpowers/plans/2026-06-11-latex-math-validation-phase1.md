# LaTeX Math Validation — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the math-validation *spec as passing tests* — a code-region-aware span extractor, a structure validator (the FastenerEngineering bug), and a pragmatic LaTeX-syntax linter, graded by a shared ~50-valid / ~50-invalid corpus. No save-path wiring (that's Phase 2).

**Architecture:** Three pure Java units in `com.wikantik.markdown.extensions.math` (wikantik-main): `CodeRegions` masks fenced/inline code (the key false-positive defense); `MathSpanExtractor` pulls valid math spans; `MathStructureValidator` flags the two high-confidence ERROR patterns + structure warnings; `LatexSyntaxLinter` emits savable WARNINGs. A JSON corpus resource grades structure + linter behavior and inventories Java-linter blind spots vs KaTeX as TODOs. Severity is reused from `wikantik-api` so Phase 2 can fold these violations straight into the existing `{ error, violations[] }` envelope.

**Tech Stack:** Java 21, JUnit 5 (`@ParameterizedTest` + `@MethodSource`), Jackson (`ObjectMapper`, already on the wikantik-main test classpath) for the corpus, `com.wikantik.api.frontmatter.schema.Severity`.

**Spec:** `docs/superpowers/specs/2026-06-11-latex-math-validation-design.md`

---

## File Structure

**Production** — `wikantik-main/src/main/java/com/wikantik/markdown/extensions/math/`:
- `MathSourceRange.java` — value type: offset + 1-based line/col range, with `from(body, start, end)`.
- `MathSpan.java` — value type: `Kind` (INLINE_DOLLAR / DISPLAY_DOLLAR / MATH_FENCE) + content + range.
- `MathViolation.java` — value type: code + `Severity` + message + range.
- `CodeRegions.java` — masks fenced code (```` ``` ````, `~~~`, but NOT ```` ```math ````) and inline `` `code` `` spans.
- `MathSpanExtractor.java` — extracts valid spans (line-isolated `$$`, ```` ```math ````, inline `$…$`), skipping masked offsets.
- `MathStructureValidator.java` — ERROR: not-isolated display `$$`, unterminated `$$`; WARNING: empty display, glued inline.
- `LatexSyntaxLinter.java` — WARNING: unbalanced `{}`, `\left`/`\right`, `\begin`/`\end`, unknown command.

**Test** — `wikantik-main/src/test/java/com/wikantik/markdown/extensions/math/`:
- `CodeRegionsTest.java`, `MathSpanExtractorTest.java`, `MathStructureValidatorTest.java`, `LatexSyntaxLinterTest.java`
- `MathValidationCorpusTest.java` — corpus-driven grading + blind-spot inventory + FastenerEngineering regression.

**Resource** — `wikantik-main/src/test/resources/math/math-validation-corpus.json`.

**Page fix** — `docs/wikantik-pages/FastenerEngineering.md`.

---

## Conventions

- Run a single test class with: `mvn test -pl wikantik-main -Dtest=ClassName -q`
- This module's provider tests are parallel-flaky (known); these new tests are pure and deterministic — run them by name, not the whole suite.
- License header: copy the Apache header block verbatim from `InlineMathParser.java` (lines 1–18) into every new `.java` file.
- Commit after each task with the exact files listed.

---

## Task 1: Value types

**Files:**
- Create: `.../math/MathSourceRange.java`
- Create: `.../math/MathSpan.java`
- Create: `.../math/MathViolation.java`
- Test: `.../math/MathSourceRangeTest.java`

- [ ] **Step 1: Write the failing test**

`MathSourceRangeTest.java`:
```java
package com.wikantik.markdown.extensions.math;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MathSourceRangeTest {

    @Test
    void computesLineAndColumnFromOffsets() {
        final String body = "abc\n$$x$$\nyz";   // line 2 starts at offset 4
        final int start = body.indexOf("$$");     // 4
        final int end = start + 6;                // after closing $$ (offset 10)
        final MathSourceRange r = MathSourceRange.from(body, start, end);
        assertEquals(start, r.startOffset());
        assertEquals(end, r.endOffset());
        assertEquals(2, r.line());
        assertEquals(1, r.column());
        assertEquals(2, r.endLine());
        assertEquals(7, r.endColumn());           // 1 + length("$$x$$")=6 -> col 7
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=MathSourceRangeTest -q`
Expected: FAIL — `MathSourceRange` does not exist (compile error).

- [ ] **Step 3: Write the value types**

`MathSourceRange.java` (Apache header, then):
```java
package com.wikantik.markdown.extensions.math;

/** Half-open source span: [startOffset, endOffset) with 1-based line/column endpoints. */
public record MathSourceRange(int startOffset, int endOffset,
                              int line, int column, int endLine, int endColumn) {

    /** Computes a range from absolute offsets into {@code body}. {@code end} is exclusive. */
    public static MathSourceRange from(final String body, final int start, final int end) {
        int line = 1, col = 1;
        for (int i = 0; i < start && i < body.length(); i++) {
            if (body.charAt(i) == '\n') { line++; col = 1; } else { col++; }
        }
        int endLine = line, endCol = col;
        for (int i = start; i < end && i < body.length(); i++) {
            if (body.charAt(i) == '\n') { endLine++; endCol = 1; } else { endCol++; }
        }
        return new MathSourceRange(start, end, line, col, endLine, endCol);
    }
}
```

`MathSpan.java` (Apache header, then):
```java
package com.wikantik.markdown.extensions.math;

/** A recognised math region and the LaTeX content between its delimiters. */
public record MathSpan(Kind kind, String content, MathSourceRange range) {
    public enum Kind { INLINE_DOLLAR, DISPLAY_DOLLAR, MATH_FENCE }
}
```

`MathViolation.java` (Apache header, then):
```java
package com.wikantik.markdown.extensions.math;

import com.wikantik.api.frontmatter.schema.Severity;

/** A single math-validation finding, with the source range it points at. */
public record MathViolation(String code, Severity severity, String message, MathSourceRange range) {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=MathSourceRangeTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/markdown/extensions/math/MathSourceRange.java \
        wikantik-main/src/main/java/com/wikantik/markdown/extensions/math/MathSpan.java \
        wikantik-main/src/main/java/com/wikantik/markdown/extensions/math/MathViolation.java \
        wikantik-main/src/test/java/com/wikantik/markdown/extensions/math/MathSourceRangeTest.java
git commit -m "feat(math): value types for math span/violation/range"
```

---

## Task 2: CodeRegions (false-positive bedrock)

**Files:**
- Create: `.../math/CodeRegions.java`
- Test: `.../math/CodeRegionsTest.java`

- [ ] **Step 1: Write the failing test**

`CodeRegionsTest.java`:
```java
package com.wikantik.markdown.extensions.math;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeRegionsTest {

    @Test
    void masksInlineCodeSpan() {
        final String body = "use `$$x$$` here";
        final CodeRegions cr = CodeRegions.scan(body);
        assertTrue(cr.isMasked(body.indexOf("$$x$$")), "inside backticks must be masked");
        assertFalse(cr.isMasked(0), "prose before the code span is not masked");
    }

    @Test
    void masksFencedCodeButNotMathFence() {
        final String fenced = "```java\n$$x$$\n```";
        assertTrue(CodeRegions.scan(fenced).isMasked(fenced.indexOf("$$x$$")),
                   "java fence content is masked");

        final String math = "```math\n\\frac{a}{b}\n```";
        assertFalse(CodeRegions.scan(math).isMasked(math.indexOf("\\frac")),
                    "a ```math fence is NOT code — it is math");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=CodeRegionsTest -q`
Expected: FAIL — `CodeRegions` does not exist.

- [ ] **Step 3: Implement CodeRegions**

`CodeRegions.java` (Apache header, then):
```java
package com.wikantik.markdown.extensions.math;

/**
 * Marks the byte offsets of a Markdown body that fall inside code — fenced code blocks
 * ({@code ```} / {@code ~~~}, but NOT {@code ```math}, which is display math) and inline
 * {@code `code`} spans. Math delimiters inside these regions are literal examples, not math,
 * and must be ignored by every validator. This is the primary false-positive defense.
 */
public final class CodeRegions {

    private final boolean[] masked;

    private CodeRegions(final boolean[] masked) { this.masked = masked; }

    /** True when the character at {@code offset} is inside a code region. */
    public boolean isMasked(final int offset) {
        return offset >= 0 && offset < masked.length && masked[offset];
    }

    public static CodeRegions scan(final String body) {
        final boolean[] masked = new boolean[body.length()];
        final String[] lines = body.split("\n", -1);
        int offset = 0;
        boolean inFence = false;
        String fenceMarker = null;   // "```" or "~~~"
        boolean fenceIsMath = false;

        for (final String line : lines) {
            final String trimmed = line.strip();
            final boolean isFence = trimmed.startsWith("```") || trimmed.startsWith("~~~");

            if (!inFence && isFence) {
                inFence = true;
                fenceMarker = trimmed.startsWith("```") ? "```" : "~~~";
                final String info = trimmed.substring(3).strip().toLowerCase();
                fenceIsMath = info.equals("math");
                if (!fenceIsMath) { maskLine(masked, offset, line.length()); }
            } else if (inFence && isFence && trimmed.startsWith(fenceMarker)) {
                if (!fenceIsMath) { maskLine(masked, offset, line.length()); }
                inFence = false; fenceMarker = null; fenceIsMath = false;
            } else if (inFence) {
                if (!fenceIsMath) { maskLine(masked, offset, line.length()); }
            } else {
                maskInlineCode(masked, line, offset);
            }
            offset += line.length() + 1;   // +1 for the '\n' consumed by split
        }
        return new CodeRegions(masked);
    }

    private static void maskLine(final boolean[] masked, final int start, final int len) {
        for (int p = start; p < start + len && p < masked.length; p++) { masked[p] = true; }
    }

    /** Masks paired backtick runs (run of N backticks closed by a run of exactly N). */
    private static void maskInlineCode(final boolean[] masked, final String line, final int base) {
        int i = 0;
        while (i < line.length()) {
            if (line.charAt(i) != '`') { i++; continue; }
            final int runStart = i;
            int n = 0;
            while (i < line.length() && line.charAt(i) == '`') { i++; n++; }
            int j = i;
            boolean closed = false;
            while (j < line.length()) {
                if (line.charAt(j) == '`') {
                    int k = j, m = 0;
                    while (k < line.length() && line.charAt(k) == '`') { k++; m++; }
                    if (m == n) {
                        for (int p = base + runStart; p < base + k && p < masked.length; p++) { masked[p] = true; }
                        i = k; closed = true; break;
                    }
                    j = k;
                } else { j++; }
            }
            if (!closed) { /* unterminated run: leave unmasked, scan continues past opener */ }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=CodeRegionsTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/markdown/extensions/math/CodeRegions.java \
        wikantik-main/src/test/java/com/wikantik/markdown/extensions/math/CodeRegionsTest.java
git commit -m "feat(math): code-region masking for false-positive avoidance"
```

---

## Task 3: MathSpanExtractor

**Files:**
- Create: `.../math/MathSpanExtractor.java`
- Test: `.../math/MathSpanExtractorTest.java`

- [ ] **Step 1: Write the failing test**

`MathSpanExtractorTest.java`:
```java
package com.wikantik.markdown.extensions.math;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathSpanExtractorTest {

    private final MathSpanExtractor extractor = new MathSpanExtractor();

    @Test
    void extractsLineIsolatedDisplayBlock() {
        final String body = "intro\n$$\n\\frac{a}{b}\n$$\noutro";
        final List<MathSpan> spans = extractor.extract(body);
        assertEquals(1, spans.size());
        assertEquals(MathSpan.Kind.DISPLAY_DOLLAR, spans.get(0).kind());
        assertEquals("\\frac{a}{b}", spans.get(0).content());
    }

    @Test
    void extractsMathFence() {
        final String body = "```math\nx^2\n```";
        final List<MathSpan> spans = extractor.extract(body);
        assertEquals(1, spans.size());
        assertEquals(MathSpan.Kind.MATH_FENCE, spans.get(0).kind());
        assertEquals("x^2", spans.get(0).content());
    }

    @Test
    void extractsInlineButSkipsCodeAndCurrency() {
        final String body = "value $x+1$ costs `$5` not $5 dollars";
        final List<MathSpan> spans = extractor.extract(body);
        assertEquals(1, spans.size(), "only the real inline span; backtick $5 masked; bare $ is not a span");
        assertEquals(MathSpan.Kind.INLINE_DOLLAR, spans.get(0).kind());
        assertEquals("x+1", spans.get(0).content());
    }

    @Test
    void doesNotExtractInlineDisplayGlue() {
        // $$ glued inline is NOT a valid span (it is a structure error, handled elsewhere)
        final String body = "text:$$\\frac{a}{b}$$more";
        final List<MathSpan> spans = extractor.extract(body);
        assertTrue(spans.isEmpty(), "inline-glued $$ is not a recognised span");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=MathSpanExtractorTest -q`
Expected: FAIL — `MathSpanExtractor` does not exist.

- [ ] **Step 3: Implement MathSpanExtractor**

`MathSpanExtractor.java` (Apache header, then):
```java
package com.wikantik.markdown.extensions.math;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts recognised math spans from a Markdown body, skipping code regions ({@link CodeRegions}).
 * Recognises: line-isolated {@code $$…$$} display blocks, {@code ```math} fences, and inline
 * {@code $…$}. Deliberately does NOT recognise inline-glued {@code $$} — that is a structure error
 * surfaced by {@link MathStructureValidator}, not a span to lint.
 */
public class MathSpanExtractor {

    /** Mirrors InlineMathParser: non-empty content not starting/ending with a space, no inner '$'. */
    private static final Pattern INLINE = Pattern.compile("\\$([^ $](?:[^$]*[^ $])?)\\$|\\$([^ $])\\$");

    public List<MathSpan> extract(final String body) {
        if (body == null || body.isEmpty()) { return List.of(); }
        final CodeRegions code = CodeRegions.scan(body);
        final List<MathSpan> spans = new ArrayList<>();
        final String[] lines = body.split("\n", -1);

        int offset = 0;
        int blockStart = -1;          // offset of opening delimiter line
        int contentStart = -1;        // offset of first content char
        String closer = null;         // "$$" or "```"
        MathSpan.Kind blockKind = null;
        final StringBuilder content = new StringBuilder();

        for (final String line : lines) {
            final String trimmed = line.strip();
            if (closer == null) {
                if (trimmed.equals("$$")) {
                    closer = "$$"; blockKind = MathSpan.Kind.DISPLAY_DOLLAR;
                    blockStart = offset; contentStart = offset + line.length() + 1; content.setLength(0);
                } else if (trimmed.equals("```math")) {
                    closer = "```"; blockKind = MathSpan.Kind.MATH_FENCE;
                    blockStart = offset; contentStart = offset + line.length() + 1; content.setLength(0);
                } else if (!isFullyMasked(code, offset, line.length()) && !trimmed.startsWith("```")
                           && !trimmed.startsWith("~~~")) {
                    extractInline(line, offset, code, spans);
                }
            } else if (("$$".equals(closer) && trimmed.equals("$$"))
                       || ("```".equals(closer) && trimmed.startsWith("```"))) {
                final int contentEnd = offset > contentStart ? offset - 1 : contentStart;
                final String text = content.length() > 0
                        ? content.substring(0, content.length() - 1) : "";   // drop trailing '\n'
                spans.add(new MathSpan(blockKind, text,
                        MathSourceRange.from(body, blockStart, offset + line.length())));
                closer = null; blockKind = null; content.setLength(0);
            } else {
                content.append(line).append('\n');
            }
            offset += line.length() + 1;
        }
        return spans;
    }

    private void extractInline(final String line, final int base, final CodeRegions code,
                               final List<MathSpan> out) {
        final Matcher m = INLINE.matcher(line);
        int from = 0;
        while (m.find(from)) {
            // Skip a $$ display marker (two adjacent $ at the match start should not be inline)
            if (m.start() > 0 && line.charAt(m.start() - 1) == '$') { from = m.end(); continue; }
            if (m.end() < line.length() && line.charAt(m.end()) == '$') { from = m.end(); continue; }
            final int s = base + m.start();
            if (code.isMasked(s)) { from = m.end(); continue; }
            final String c = m.group(1) != null ? m.group(1) : m.group(2);
            out.add(new MathSpan(MathSpan.Kind.INLINE_DOLLAR, c,
                    MathSourceRange.from(line, m.start(), m.end())
                            .startOffset() >= 0 ? rangeIn(line, base, m.start(), m.end()) : null));
            from = m.end();
        }
    }

    private static MathSourceRange rangeIn(final String line, final int base, final int s, final int e) {
        // line-local line/col is always line 1 for an inline span; offsets are body-absolute
        return new MathSourceRange(base + s, base + e, 1, s + 1, 1, e + 1);
    }

    private static boolean isFullyMasked(final CodeRegions code, final int start, final int len) {
        for (int p = start; p < start + len; p++) { if (!code.isMasked(p)) { return false; } }
        return len > 0;
    }
}
```

> Note: inline `MathSourceRange` line/column is line-local (the extractor's inline ranges are
> used only to lint content; the structure validator computes body-absolute line/col itself).
> The `rangeIn` helper keeps body-absolute offsets, which is what Phase 2 needs.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=MathSpanExtractorTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/markdown/extensions/math/MathSpanExtractor.java \
        wikantik-main/src/test/java/com/wikantik/markdown/extensions/math/MathSpanExtractorTest.java
git commit -m "feat(math): span extractor (display/fence/inline), code-aware"
```

---

## Task 4: MathStructureValidator (the two ERROR patterns + warnings)

**Files:**
- Create: `.../math/MathStructureValidator.java`
- Test: `.../math/MathStructureValidatorTest.java`

- [ ] **Step 1: Write the failing test**

`MathStructureValidatorTest.java`:
```java
package com.wikantik.markdown.extensions.math;

import com.wikantik.api.frontmatter.schema.Severity;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathStructureValidatorTest {

    private final MathStructureValidator validator = new MathStructureValidator();

    private boolean hasError(final List<MathViolation> v, final String code) {
        return v.stream().anyMatch(x -> x.severity() == Severity.ERROR && x.code().equals(code));
    }

    @Test
    void blocksInlineGluedDisplayWithCommand() {
        // The FastenerEngineering bug.
        final String body = "friction:$$\\text{T} = \\text{F}_p \\left( x \\right)$$Where";
        assertTrue(hasError(validator.validate(body), "math.display.notIsolated"));
    }

    @Test
    void blocksUnterminatedDisplay() {
        final String body = "intro\n$$\n\\frac{a}{b}\nno closer here";
        assertTrue(hasError(validator.validate(body), "math.display.unterminated"));
    }

    @Test
    void allowsLineIsolatedDisplay() {
        final String body = "intro\n$$\n\\frac{a}{b}\n$$\noutro";
        assertEquals(List.of(), validator.validate(body));
    }

    @Test
    void allowsCurrencyProse() {
        final String body = "It costs $5 and $10 to ship, total $15.";
        assertEquals(List.of(), validator.validate(body));
    }

    @Test
    void ignoresDisplayInsideCodeFence() {
        final String body = "```\nfriction:$$\\text{T}=x$$Where\n```";
        assertEquals(List.of(), validator.validate(body));
    }

    @Test
    void warnsOnEmptyDisplay() {
        final String body = "a\n$$\n$$\nb";
        final List<MathViolation> v = validator.validate(body);
        assertTrue(v.stream().anyMatch(x -> x.severity() == Severity.WARNING
                && x.code().equals("math.display.empty")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=MathStructureValidatorTest -q`
Expected: FAIL — `MathStructureValidator` does not exist.

- [ ] **Step 3: Implement MathStructureValidator**

`MathStructureValidator.java` (Apache header, then):
```java
package com.wikantik.markdown.extensions.math;

import com.wikantik.api.frontmatter.schema.Severity;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects high-confidence math *structure* problems by scanning code-masked {@code $$} pairs.
 * <ul>
 *   <li>ERROR {@code math.display.notIsolated}: a {@code $$…$$} pair whose delimiters are glued to
 *       surrounding text (not on their own line) and whose content contains a {@code \\command} —
 *       this renders as literal text (the FastenerEngineering bug).</li>
 *   <li>ERROR {@code math.display.unterminated}: an odd, unpaired {@code $$}.</li>
 *   <li>WARNING {@code math.display.empty}: a {@code $$…$$} pair with blank content.</li>
 * </ul>
 * Bare single-{@code $} balance is deliberately NOT checked — currency (<code>$5 and $10</code>)
 * would false-positive, and false positives are worse than a few escapes.
 */
public class MathStructureValidator {

    public List<MathViolation> validate(final String body) {
        final List<MathViolation> out = new ArrayList<>();
        if (body == null || body.isEmpty()) { return out; }
        final CodeRegions code = CodeRegions.scan(body);

        // Collect non-masked $$ delimiter offsets.
        final List<Integer> marks = new ArrayList<>();
        for (int i = 0; i + 1 < body.length(); i++) {
            if (body.charAt(i) == '$' && body.charAt(i + 1) == '$' && !code.isMasked(i)) {
                marks.add(i);
                i++;   // consume both
            }
        }

        int k = 0;
        for (; k + 1 < marks.size(); k += 2) {
            final int open = marks.get(k);
            final int close = marks.get(k + 1);
            final String content = body.substring(open + 2, close);
            if (content.isBlank()) {
                out.add(new MathViolation("math.display.empty", Severity.WARNING,
                        "Empty display-math block ($$ $$).",
                        MathSourceRange.from(body, open, close + 2)));
                continue;
            }
            final boolean openIsolated = lineIsBlankExceptDelimAt(body, open, true);
            final boolean closeIsolated = lineIsBlankExceptDelimAt(body, close, false);
            if ((!openIsolated || !closeIsolated) && content.contains("\\")) {
                out.add(new MathViolation("math.display.notIsolated", Severity.ERROR,
                        "Display math ($$…$$) is glued to surrounding text and will render as "
                                + "literal text. Put the $$ delimiters on their own lines.",
                        MathSourceRange.from(body, open, close + 2)));
            }
        }
        if (k < marks.size()) {   // dangling, unpaired $$
            final int open = marks.get(k);
            out.add(new MathViolation("math.display.unterminated", Severity.ERROR,
                    "Unterminated display-math block: an opening $$ has no matching closing $$.",
                    MathSourceRange.from(body, open, Math.min(open + 2, body.length()))));
        }
        return out;
    }

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
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=MathStructureValidatorTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/markdown/extensions/math/MathStructureValidator.java \
        wikantik-main/src/test/java/com/wikantik/markdown/extensions/math/MathStructureValidatorTest.java
git commit -m "feat(math): structure validator (notIsolated + unterminated $$ errors)"
```

---

## Task 5: LatexSyntaxLinter

**Files:**
- Create: `.../math/LatexSyntaxLinter.java`
- Test: `.../math/LatexSyntaxLinterTest.java`

- [ ] **Step 1: Write the failing test**

`LatexSyntaxLinterTest.java`:
```java
package com.wikantik.markdown.extensions.math;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatexSyntaxLinterTest {

    private final LatexSyntaxLinter linter = new LatexSyntaxLinter();

    private boolean warns(final String latex, final String code) {
        return linter.lint(latex).stream().anyMatch(v -> v.code().equals(code));
    }

    @Test
    void cleanLatexHasNoWarnings() {
        assertTrue(linter.lint("\\frac{a}{b} + \\sqrt{x^2}").isEmpty());
        assertTrue(linter.lint("\\left( \\frac{a}{b} \\right)").isEmpty());
        assertTrue(linter.lint("\\begin{matrix} a & b \\end{matrix}").isEmpty());
    }

    @Test
    void unbalancedBraces() { assertTrue(warns("\\frac{a}{b", "math.syntax.unbalancedBraces")); }

    @Test
    void leftWithoutRight() { assertTrue(warns("\\left( x", "math.syntax.leftRight")); }

    @Test
    void beginWithoutEnd() { assertTrue(warns("\\begin{matrix} a", "math.syntax.beginEnd")); }

    @Test
    void mismatchedBeginEnd() { assertTrue(warns("\\begin{matrix} a \\end{pmatrix}", "math.syntax.beginEnd")); }

    @Test
    void unknownCommand() { assertTrue(warns("\\frooble{x}", "math.syntax.unknownCommand")); }

    @Test
    void knownCommandsPass() { assertFalse(warns("\\alpha \\cdot \\beta", "math.syntax.unknownCommand")); }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=LatexSyntaxLinterTest -q`
Expected: FAIL — `LatexSyntaxLinter` does not exist.

- [ ] **Step 3: Implement LatexSyntaxLinter**

`LatexSyntaxLinter.java` (Apache header, then):
```java
package com.wikantik.markdown.extensions.math;

import com.wikantik.api.frontmatter.schema.Severity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pragmatic, savable WARNING-level checks for one LaTeX expression. Approximate vs real KaTeX by
 * design — the corpus is the spec, and {@code katexExpect}/{@code linterExpect} divergences are
 * tracked as TODOs to close. Never produces an ERROR; never blocks a save.
 */
public class LatexSyntaxLinter {

    private static final Pattern COMMAND = Pattern.compile("\\\\([a-zA-Z]+)");

    /** A pragmatic allowlist of KaTeX commands seen in the corpus. Expand as the corpus grows. */
    private static final Set<String> KNOWN = Set.of(
            "frac", "sqrt", "sum", "int", "prod", "lim", "infty", "partial", "nabla",
            "left", "right", "begin", "end", "text", "mathrm", "mathbf", "mathbb", "mathcal",
            "alpha", "beta", "gamma", "delta", "epsilon", "theta", "lambda", "mu", "nu", "pi",
            "rho", "sigma", "tau", "phi", "psi", "omega", "Delta", "Gamma", "Phi", "Omega",
            "cdot", "times", "div", "pm", "mp", "leq", "geq", "neq", "approx", "equiv",
            "rightarrow", "leftarrow", "Rightarrow", "cos", "sin", "tan", "log", "ln", "exp",
            "hat", "bar", "vec", "dot", "ddot", "overline", "underline", "binom", "cases", "matrix",
            "pmatrix", "bmatrix", "vmatrix", "quad", "qquad", "space", "displaystyle");

    public List<MathViolation> lint(final String latex) {
        final List<MathViolation> out = new ArrayList<>();
        if (latex == null || latex.isBlank()) { return out; }
        final MathSourceRange whole = new MathSourceRange(0, latex.length(), 1, 1, 1, latex.length() + 1);

        if (!bracesBalanced(latex)) {
            out.add(new MathViolation("math.syntax.unbalancedBraces", Severity.WARNING,
                    "Unbalanced { } braces.", whole));
        }
        if (count(latex, "\\left") != count(latex, "\\right")) {
            out.add(new MathViolation("math.syntax.leftRight", Severity.WARNING,
                    "\\left without a matching \\right (or vice versa).", whole));
        }
        if (!beginEndMatched(latex)) {
            out.add(new MathViolation("math.syntax.beginEnd", Severity.WARNING,
                    "\\begin{…} / \\end{…} environments do not match.", whole));
        }
        final Matcher m = COMMAND.matcher(latex);
        while (m.find()) {
            if (!KNOWN.contains(m.group(1))) {
                out.add(new MathViolation("math.syntax.unknownCommand", Severity.WARNING,
                        "Unknown/unsupported command \\" + m.group(1) + ".", whole));
                break;   // one unknown-command warning per expression is enough
            }
        }
        return out;
    }

    private static boolean bracesBalanced(final String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\\') { i++; continue; }   // skip escaped char (e.g. \{ \})
            if (c == '{') { depth++; }
            else if (c == '}') { if (--depth < 0) { return false; } }
        }
        return depth == 0;
    }

    private static int count(final String s, final String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    private static boolean beginEndMatched(final String s) {
        final Pattern env = Pattern.compile("\\\\(begin|end)\\{([^}]*)\\}");
        final Matcher m = env.matcher(s);
        final Deque<String> stack = new ArrayDeque<>();
        while (m.find()) {
            if ("begin".equals(m.group(1))) { stack.push(m.group(2)); }
            else { if (stack.isEmpty() || !stack.pop().equals(m.group(2))) { return false; } }
        }
        return stack.isEmpty();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=LatexSyntaxLinterTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/markdown/extensions/math/LatexSyntaxLinter.java \
        wikantik-main/src/test/java/com/wikantik/markdown/extensions/math/LatexSyntaxLinterTest.java
git commit -m "feat(math): pragmatic LaTeX syntax linter (braces/left-right/begin-end/commands)"
```

---

## Task 6: The shared corpus + corpus-driven grading

**Files:**
- Create: `wikantik-main/src/test/resources/math/math-validation-corpus.json`
- Test: `.../math/MathValidationCorpusTest.java`

### Corpus schema

A JSON array. Each row:
```jsonc
{
  "id": "struct-display-inline-glued",   // unique kebab id
  "source": "friction:$$\\text{T}=x$$Where",  // a Markdown body snippet (JSON-escaped)
  "category": "structure",               // "structure" | "syntax" | "valid"
  "structureExpect": "error",            // "ok" | "error"  -> MathStructureValidator emits an ERROR?
  "katexExpect": "ok",                   // "ok" | "error"  -> would real KaTeX reject? (recorded; graded by JS mirror in Phase 3)
  "linterExpect": "ok",                  // "ok" | "warn"   -> LatexSyntaxLinter emits a WARNING?
  "note": "FastenerEngineering bug"      // free text
}
```

### Coverage matrix (target ≈50 valid + ≈50 invalid = ~100 rows)

The executor MUST author rows covering every cell below. Counts are floors; hit them.

**VALID rows (~50)** — `structureExpect: ok`, `linterExpect: ok`, `katexExpect: ok`:
- 12 × line-isolated display blocks (`$$\n…\n$$`): fractions, sums, integrals, matrices, cases, aligned, sqrt, Greek, sub/superscripts, `\left\right`, `\text`, binomials.
- 10 × inline `$…$` in prose with surrounding spaces: variables, simple expressions, Greek, sub/superscripts.
- 8 × `` ```math `` fences with the same variety as display.
- 8 × currency / prose-with-`$`: `$5 and $10`, `costs $15`, `$x` mid-word currency, price ranges.
- 6 × math-looking content **inside code regions** (fenced ``` and inline `` `…` ``) — MUST be ignored (no error, no warn).
- 6 × edge: empty body, no math at all, escaped `\$`, `$$` documented in plain prose **inside backticks**.

**INVALID rows (~50):**
- *Structure errors (~18)* — `structureExpect: error`:
  - 10 × inline-glued display `$$…$$` with `\command` (FastenerEngineering variants: glued open, glued close, glued both, inside a list item, after a colon).
  - 8 × unterminated `$$` (odd delimiter; opener with no closer; closer-looking but mismatched).
- *Syntax warnings (~24)* — `linterExpect: warn`, `structureExpect: ok` (wrap the bad LaTeX in a valid line-isolated `$$` so only the linter fires):
  - 7 × unbalanced braces, 6 × `\left`/`\right` mismatch, 6 × `\begin`/`\end` mismatch, 5 × unknown command.
- *Blind-spot rows (~8)* — `katexExpect: error`, `linterExpect: ok`, `structureExpect: ok`: real KaTeX errors the pragmatic linter does NOT catch (e.g. `\frac` with one arg `\frac{a}`, `x^` with no exponent, `\sqrt[` malformed optional arg, double subscript `x_a_b`). These are the **TODO inventory** — they must be present and the inventory test reports them.

### Seed rows (author the rest following these exact shapes)

`math-validation-corpus.json` — start with these, then expand to the matrix above:
```json
[
  { "id": "valid-display-frac", "source": "$$\n\\frac{a}{b}\n$$", "category": "valid", "structureExpect": "ok", "katexExpect": "ok", "linterExpect": "ok", "note": "canonical display fraction" },
  { "id": "valid-display-integral", "source": "$$\n\\int_0^1 x^2 \\, dx\n$$", "category": "valid", "structureExpect": "ok", "katexExpect": "ok", "linterExpect": "ok", "note": "definite integral" },
  { "id": "valid-display-leftright", "source": "$$\n\\left( \\frac{P}{2\\pi} \\right)\n$$", "category": "valid", "structureExpect": "ok", "katexExpect": "ok", "linterExpect": "ok", "note": "balanced left/right" },
  { "id": "valid-inline-spaced", "source": "the value $x + 1$ is positive", "category": "valid", "structureExpect": "ok", "katexExpect": "ok", "linterExpect": "ok", "note": "inline with spaces" },
  { "id": "valid-mathfence-sum", "source": "```math\n\\sum_{i=1}^n i\n```", "category": "valid", "structureExpect": "ok", "katexExpect": "ok", "linterExpect": "ok", "note": "math fence" },
  { "id": "valid-currency-pair", "source": "It costs $5 and $10 to ship.", "category": "valid", "structureExpect": "ok", "katexExpect": "ok", "linterExpect": "ok", "note": "currency must not trip $-balance" },
  { "id": "valid-code-inline-dollars", "source": "render with `$$x$$` on its own line", "category": "valid", "structureExpect": "ok", "katexExpect": "ok", "linterExpect": "ok", "note": "math syntax documented inside inline code" },
  { "id": "valid-code-fence-display", "source": "```\nfriction:$$\\text{T}=x$$Where\n```", "category": "valid", "structureExpect": "ok", "katexExpect": "ok", "linterExpect": "ok", "note": "the FE bug, but inside a code fence -> ignored" },
  { "id": "struct-inline-glued-both", "source": "friction:$$\\text{T} = \\text{F}_p \\left( x \\right)$$Where", "category": "structure", "structureExpect": "error", "katexExpect": "ok", "linterExpect": "ok", "note": "FastenerEngineering line 28" },
  { "id": "struct-inline-glued-open", "source": "text:$$\\frac{a}{b}$$\n", "category": "structure", "structureExpect": "error", "katexExpect": "ok", "linterExpect": "ok", "note": "glued opener only" },
  { "id": "struct-unterminated", "source": "intro\n$$\n\\frac{a}{b}\nno closer", "category": "structure", "structureExpect": "error", "katexExpect": "ok", "linterExpect": "ok", "note": "opener with no closer" },
  { "id": "syntax-unbalanced-braces", "source": "$$\n\\frac{a}{b\n$$", "category": "syntax", "structureExpect": "ok", "katexExpect": "error", "linterExpect": "warn", "note": "missing closing brace" },
  { "id": "syntax-left-no-right", "source": "$$\n\\left( x\n$$", "category": "syntax", "structureExpect": "ok", "katexExpect": "error", "linterExpect": "warn", "note": "left without right" },
  { "id": "syntax-begin-no-end", "source": "$$\n\\begin{matrix} a & b\n$$", "category": "syntax", "structureExpect": "ok", "katexExpect": "error", "linterExpect": "warn", "note": "begin without end" },
  { "id": "syntax-unknown-command", "source": "$$\n\\frooble{x}\n$$", "category": "syntax", "structureExpect": "ok", "katexExpect": "error", "linterExpect": "warn", "note": "unknown command" },
  { "id": "blindspot-frac-one-arg", "source": "$$\n\\frac{a}\n$$", "category": "syntax", "structureExpect": "ok", "katexExpect": "error", "linterExpect": "ok", "note": "TODO: linter misses \\frac arity; KaTeX rejects" },
  { "id": "blindspot-empty-superscript", "source": "$$\nx^\n$$", "category": "syntax", "structureExpect": "ok", "katexExpect": "error", "linterExpect": "ok", "note": "TODO: linter misses empty superscript; KaTeX rejects" }
]
```

- [ ] **Step 1: Write the failing test**

`MathValidationCorpusTest.java`:
```java
package com.wikantik.markdown.extensions.math;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.wikantik.api.frontmatter.schema.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathValidationCorpusTest {

    private static final MathSpanExtractor EXTRACTOR = new MathSpanExtractor();
    private static final MathStructureValidator STRUCTURE = new MathStructureValidator();
    private static final LatexSyntaxLinter LINTER = new LatexSyntaxLinter();

    record Row(String id, String source, String category,
               String structureExpect, String katexExpect, String linterExpect, String note) {}

    static List<Row> corpus() throws Exception {
        final ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = MathValidationCorpusTest.class.getClassLoader()
                .getResourceAsStream("math/math-validation-corpus.json")) {
            assertNotNull(in, "corpus resource missing");
            final JsonNode arr = mapper.readTree(in);
            final List<Row> rows = new ArrayList<>();
            for (final JsonNode n : arr) {
                rows.add(new Row(n.get("id").asText(), n.get("source").asText(),
                        n.get("category").asText(), n.get("structureExpect").asText(),
                        n.get("katexExpect").asText(), n.get("linterExpect").asText(),
                        n.path("note").asText("")));
            }
            return rows;
        }
    }

    static Stream<Arguments> rows() throws Exception {
        return corpus().stream().map(Arguments::of);
    }

    private static boolean structureErrors(final String body) {
        return STRUCTURE.validate(body).stream().anyMatch(v -> v.severity() == Severity.ERROR);
    }

    private static boolean linterWarns(final String body) {
        return EXTRACTOR.extract(body).stream()
                .flatMap(s -> LINTER.lint(s.content()).stream())
                .anyMatch(v -> v.severity() == Severity.WARNING);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void structureMatchesCorpus(final Row row) {
        final boolean expectError = "error".equals(row.structureExpect());
        assertEquals(expectError, structureErrors(row.source()),
                "structureExpect mismatch for '" + row.id() + "' (" + row.note() + ")");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void linterMatchesCorpus(final Row row) {
        final boolean expectWarn = "warn".equals(row.linterExpect());
        assertEquals(expectWarn, linterWarns(row.source()),
                "linterExpect mismatch for '" + row.id() + "' (" + row.note() + ")");
    }

    @Test
    void corpusHasFiftyOfEach() throws Exception {
        final List<Row> rows = corpus();
        final long valid = rows.stream().filter(r ->
                "ok".equals(r.structureExpect()) && !"warn".equals(r.linterExpect())
                && !"error".equals(r.katexExpect())).count();
        final long invalid = rows.size() - valid;
        assertTrue(valid >= 45, "expected ~50 fully-valid rows, got " + valid);
        assertTrue(invalid >= 45, "expected ~50 invalid rows, got " + invalid);
    }

    /** Inventory (does not fail): rows real-KaTeX rejects but the pragmatic linter misses — the TODO list. */
    @Test
    void reportsLinterBlindSpots() throws Exception {
        final List<String> blind = corpus().stream()
                .filter(r -> "error".equals(r.katexExpect()) && !"warn".equals(r.linterExpect()))
                .map(r -> r.id() + " — " + r.note())
                .toList();
        System.out.println("[math-linter blind spots / TODO] " + blind.size() + " rows:");
        blind.forEach(b -> System.out.println("  - " + b));
        // Intentionally no assertion: this is a tracked-TODO inventory, not a gate.
    }
}
```

- [ ] **Step 2: Author the corpus resource**

Create `math/math-validation-corpus.json` with the seed rows above, then expand to the full coverage matrix (~50 valid + ~50 invalid). Keep `source` values JSON-escaped (`\\frac`, `\n` for newlines).

- [ ] **Step 3: Run the corpus test**

Run: `mvn test -pl wikantik-main -Dtest=MathValidationCorpusTest -q`
Expected: PASS on every row. If a row fails, decide deliberately: is the validator wrong, or is the corpus expectation wrong? Fix whichever is genuinely incorrect — this is the "decide what we render" step. A `katexExpect: error` / `linterExpect: ok` row must NOT be "fixed" by tightening the linter now; it belongs in the blind-spot inventory.

- [ ] **Step 4: Commit**
```bash
git add wikantik-main/src/test/resources/math/math-validation-corpus.json \
        wikantik-main/src/test/java/com/wikantik/markdown/extensions/math/MathValidationCorpusTest.java
git commit -m "test(math): shared 50/50 validation corpus + blind-spot inventory"
```

---

## Task 7: Fix FastenerEngineering (proof the errors are actionable)

**Files:**
- Modify: `docs/wikantik-pages/FastenerEngineering.md` (lines 28, 31)
- Test: add `fastenerEngineeringPageIsClean()` to `MathValidationCorpusTest.java`

- [ ] **Step 1: Write the failing test**

Add to `MathValidationCorpusTest.java`:
```java
    @Test
    void fastenerEngineeringPageIsClean() throws Exception {
        final java.nio.file.Path page = java.nio.file.Path.of(
                System.getProperty("user.dir")).resolveSibling("jspwiki")
                .resolve("docs/wikantik-pages/FastenerEngineering.md");
        // user.dir is the module dir (wikantik-main) during surefire; walk up to repo root.
        final java.nio.file.Path repoRoot = java.nio.file.Path.of(System.getProperty("user.dir")).getParent();
        final java.nio.file.Path md = repoRoot.resolve("docs/wikantik-pages/FastenerEngineering.md");
        final String body = java.nio.file.Files.readString(md);
        final List<MathViolation> v = STRUCTURE.validate(body);
        assertTrue(v.stream().noneMatch(x -> x.severity() == Severity.ERROR),
                "FastenerEngineering must have zero structure ERRORs after the fix; found: "
                        + v.stream().filter(x -> x.severity() == Severity.ERROR).toList());
    }
```
> Drop the unused `page` line; keep only the `repoRoot`-based `md` path. (Cleaned in Step 3.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=MathValidationCorpusTest#fastenerEngineeringPageIsClean -q`
Expected: FAIL — line 28's inline-glued `$$…$$` produces `math.display.notIsolated`.

- [ ] **Step 3: Fix the page**

In `docs/wikantik-pages/FastenerEngineering.md`, rewrite the two glued display blocks so the `$$` delimiters sit on their own lines and the surrounding inline `$…$` have spaces. Line 28 becomes:
```markdown
The conversion of applied torque ($\text{T}$) to preload is governed by the **Motosh Equation**, which accounts for thread geometry and friction:

$$
\text{T} = \text{F}_p \left( \frac{\text{P}}{2\pi} + \frac{\mu_t \cdot r_t}{\cos \beta} + \mu_h \cdot r_h \right)
$$

Where $\text{P}$ is the thread pitch, $\mu_t$ and $\mu_h$ are the coefficients of friction for threads and head, and $r_t, r_h$ are the effective radii. Experts must treat $\mu$ as a stochastic variable, as it is highly sensitive to temperature and surface condition.
```
Apply the same own-line treatment to the `$$\text{F}_{bolt} = …$$` block on line 31, with spaces around the surrounding inline math. Also clean up the test per the Step 1 note (single `md` path).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=MathValidationCorpusTest#fastenerEngineeringPageIsClean -q`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add docs/wikantik-pages/FastenerEngineering.md \
        wikantik-main/src/test/java/com/wikantik/markdown/extensions/math/MathValidationCorpusTest.java
git commit -m "fix(content): isolate FastenerEngineering display-math delimiters; add regression"
```

---

## Task 8: Final verification

- [ ] **Step 1: Run the whole math test package**

Run: `mvn test -pl wikantik-main -Dtest='com.wikantik.markdown.extensions.math.*' -q`
Expected: all PASS. Confirm the blind-spot inventory line prints in the output.

- [ ] **Step 2: Compile-check the module cleanly**

Run: `mvn -q -pl wikantik-main -am test-compile`
Expected: BUILD SUCCESS (per the project rule: `test-compile`, not just `compile`, to catch test-source breakage).

- [ ] **Step 3: Sanity-check counts**

Confirm `corpusHasFiftyOfEach` passed (≈50/50). If short, add rows from the coverage matrix and re-run Task 6 Step 3.

---

## Self-Review (completed during authoring)

- **Spec coverage:** `MathSpanExtractor` (code-region exclusion ✓), `MathStructureValidator` (two ERROR patterns ✓, currency-safe ✓, code-masked ✓), `LatexSyntaxLinter` (four WARNING checks ✓), shared corpus with `structureExpect`/`katexExpect`/`linterExpect` ✓, blind-spot TODO inventory ✓, FastenerEngineering regression ✓. Phase 2 wiring and Phase 3 editor/JS-mirror intentionally absent.
- **Type consistency:** `MathSourceRange`, `MathSpan`, `MathViolation`, `Severity` (ERROR/WARNING) used identically across all tasks; method names `extract`, `validate`, `lint`, `scan`, `isMasked`, `from` are stable.
- **No placeholders:** every code step shows complete code; corpus expansion is a concrete coverage matrix with counts + seed rows, not a TODO.
- **Out of scope (Phase 1):** no `MathValidationPageFilter`, no save-path/MCP wiring, no editor changes, no GraalJS.
