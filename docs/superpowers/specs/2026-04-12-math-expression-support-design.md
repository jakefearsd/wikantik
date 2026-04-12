# Math Expression Support — Design Spec

## Context

Wiki content increasingly includes mathematical expressions that are currently
rendered as raw text. The Markdown parser (Flexmark 0.64.8) has no math awareness,
so dollar-sign-delimited LaTeX like `$x^2$` passes through as literal characters.

**Goal**: Support inline math (`$...$`) and display math (`$$...$$`) using standard
LaTeX syntax, rendered beautifully in the browser via KaTeX.

## Approach

**Backend**: Flexmark's existing `flexmark-ext-gitlab` extension (same 0.64.8 version)
provides inline math parsing (`$...$`) and block math rendering (` ```math ` fenced
blocks). A small custom pre-processor bridges `$$...$$` display syntax by converting
it to ` ```math ` fenced blocks before the parser runs.

**Frontend**: KaTeX processes the server-rendered HTML, replacing math-classed elements
with typeset formulas. The editor preview uses `remark-math` + `rehype-katex` for
live math rendering while editing.

## Backend Changes

### 1. Add `flexmark-ext-gitlab` Dependency

**Files**: `pom.xml` (dependency management), `wikantik-main/pom.xml` (dependency)

Add `flexmark-ext-gitlab` at version `${flexmark.version}` (0.64.8) alongside the
existing Flexmark extensions.

### 2. Register GitLab Extension in Flexmark Config

**File**: `wikantik-main/src/main/java/com/wikantik/parser/markdown/MarkdownDocument.java`

In the `options()` method (line 74), add `GitLabExtension.create()` to the extensions
list. Configure:

```java
options.set( GitLabExtension.INLINE_MATH_PARSER, true );
options.set( GitLabExtension.RENDER_BLOCK_MATH, true );
options.set( GitLabExtension.INLINE_MATH_CLASS, "math-inline" );
options.set( GitLabExtension.BLOCK_MATH_CLASS, "math-display" );
// Disable unrelated GitLab features
options.set( GitLabExtension.DEL_PARSER, false );
options.set( GitLabExtension.INS_PARSER, false );
options.set( GitLabExtension.BLOCK_QUOTE_PARSER, false );
options.set( GitLabExtension.RENDER_VIDEO_IMAGES, false );
options.set( GitLabExtension.RENDER_VIDEO_LINK, false );
options.set( GitLabExtension.RENDER_BLOCK_MERMAID, false );
```

Only the math features are enabled; other GitLab-flavored features (strikethrough
via `{- -}`, video embeds, colored blockquotes) are disabled to avoid conflicts
with existing Wikantik behavior.

### 3. `$$...$$` Display Math Pre-Processor

**New file**: `wikantik-main/src/main/java/com/wikantik/markdown/extensions/math/DisplayMathPreProcessor.java`

A Flexmark `Preprocessor` that transforms `$$`-delimited blocks into ` ```math `
fenced blocks before the main parser runs:

**Input**:
```
Some text

$$
\int_0^1 f(x)\,dx
$$

More text
```

**Output** (what the parser sees):
```
Some text

```math
\int_0^1 f(x)\,dx
```

More text
```

**Rules**:
- Match `$$` only when it appears as the entire content of a line (optional
  leading/trailing whitespace allowed)
- Alternate between opening and closing `$$` delimiters
- Leave inline `$$` within paragraph text untouched (only trigger on standalone lines)
- Handle edge cases: empty blocks, EOF without closing `$$`, nested `$$`

**Registration**: Implement `DocumentPreProcessorFactory` and register it in
`MarkdownForWikantikExtension.extend(Parser.Builder)` via
`parserBuilder.documentPreProcessorFactory()`. This runs before the main
parser, transforming the raw source text.

### 4. HTML Sanitizer — No Changes Needed

`WikantikHtmlSanitizer.java` already allows `<code>`, `<pre>`, `<span>`, and the
`class` attribute globally. Math-classed elements will pass through unmodified.
Verified in testing.

## Frontend Changes

### 5. Add KaTeX Dependency

**File**: `wikantik-frontend/package.json`

Add `katex` npm package. This provides both the rendering library and CSS.

### 6. Math Rendering Utility

**New file**: `wikantik-frontend/src/utils/math.js`

```javascript
import katex from 'katex';

export function renderMath(container) {
  // Inline math: <code class="math-inline">...</code>
  container.querySelectorAll('code.math-inline').forEach(el => {
    try {
      katex.render(el.textContent, el, { displayMode: false, throwOnError: false });
      el.classList.add('math-rendered');
    } catch (e) {
      el.classList.add('math-error');
    }
  });

  // Display math: elements with class "math-display"
  container.querySelectorAll('.math-display').forEach(el => {
    try {
      katex.render(el.textContent, el, { displayMode: true, throwOnError: false });
      el.classList.add('math-rendered');
    } catch (e) {
      el.classList.add('math-error');
    }
  });
}
```

`throwOnError: false` ensures malformed LaTeX shows a red error annotation
rather than crashing the page.

### 7. Integrate in PageView

**File**: `wikantik-frontend/src/components/PageView.jsx`

Add a `useEffect` that calls `renderMath()` on the article container after
`contentHtml` is set:

```javascript
import { renderMath } from '../utils/math';

// Inside component, after the dangerouslySetInnerHTML render:
useEffect(() => {
  if (articleRef.current) {
    renderMath(articleRef.current);
  }
}, [page?.contentHtml]);
```

### 8. KaTeX CSS

**File**: `wikantik-frontend/src/main.jsx` (or article styles import)

```javascript
import 'katex/dist/katex.min.css';
```

### 9. Editor Preview Math Support

**File**: `wikantik-frontend/src/components/PageEditor.jsx`

Add `remark-math` and `rehype-katex` plugins to the `react-markdown` component
used for live preview:

```javascript
import remarkMath from 'remark-math';
import rehypeKatex from 'rehype-katex';

<ReactMarkdown remarkPlugins={[remarkMath]} rehypePlugins={[rehypeKatex]}>
  {content}
</ReactMarkdown>
```

**Additional npm packages**: `remark-math`, `rehype-katex`

## Testing

### Unit Tests (TDD)

**New file**: `wikantik-main/src/test/java/com/wikantik/markdown/extensions/math/DisplayMathPreProcessorTest.java`

- `$$` block converts to ` ```math ` fenced block
- Multiple `$$` blocks in one document
- `$$` inside a paragraph (inline) is not transformed
- Empty `$$` block
- Unclosed `$$` at end of document
- `$$` with leading/trailing whitespace on the delimiter line

**Additions to existing**: `wikantik-main/src/test/java/com/wikantik/render/markdown/MarkdownRendererTest.java`

- `$x^2$` produces `<code class="math-inline">x^2</code>`
- `$$\n\\int_0^1\n$$` produces element with class `math-display`
- ` ```math ` fenced block produces element with class `math-display`
- Escaped `\$` is not parsed as math delimiter
- Math inside lists, blockquotes, and table cells
- Sanitizer preserves math classes when `allowHTML=true`

### Manual Verification

1. Deploy locally
2. Create a wiki page with inline math, display math, and mixed content
3. Verify KaTeX renders correctly in page view
4. Verify math renders in editor preview
5. Verify no regressions in non-math pages

## Files Summary

| Action | File |
|--------|------|
| Modify | `pom.xml` — add `flexmark-ext-gitlab` to dependency management |
| Modify | `wikantik-main/pom.xml` — add `flexmark-ext-gitlab` dependency |
| Modify | `MarkdownDocument.java` — register GitLab extension, configure math options |
| Modify | `MarkdownForWikantikExtension.java` — register display math pre-processor |
| Create | `DisplayMathPreProcessor.java` — `$$` to ` ```math ` transformation |
| Create | `DisplayMathPreProcessorTest.java` — unit tests for pre-processor |
| Modify | `MarkdownRendererTest.java` — math rendering integration tests |
| Modify | `wikantik-frontend/package.json` — add katex, remark-math, rehype-katex |
| Create | `wikantik-frontend/src/utils/math.js` — KaTeX DOM rendering utility |
| Modify | `wikantik-frontend/src/components/PageView.jsx` — call renderMath in useEffect |
| Modify | `wikantik-frontend/src/main.jsx` — import KaTeX CSS |
| Modify | `wikantik-frontend/src/components/PageEditor.jsx` — add remark-math/rehype-katex |
