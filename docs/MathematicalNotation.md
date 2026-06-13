# Mathematical Notation

Wikantik renders LaTeX math inside Markdown pages. Server-side parsing is handled
by the Flexmark GitLab extension; client-side rendering is handled by
[KaTeX](https://katex.org/) inside the React SPA.

## Syntax

Three forms are supported. Use whichever fits the flow of your prose.

### Inline math: `$...$`

Wrap an expression in single `$` delimiters to render it inline with the
surrounding text.

```markdown
The mass–energy relation is$E = mc^2$, familiar from special relativity.
```

Rules enforced by the parser:

- The content between the dollars must be non-empty.
- It must not start or end with a whitespace character.
- `$$` is **not** matched as inline math — it's a display-block delimiter (see
  below). This avoids accidental matches when prose contains currency values
  followed by a block expression.

### Display math: `$$...$$`

Use a pair of `$$` delimiters on their own lines for centred, block-level
equations.

```markdown$$\int_0^\infty e^{-x^2}\,dx = \frac{\sqrt{\pi}}{2}$$```

Both the opening and closing `$$` must occupy their own line (leading and
trailing whitespace is tolerated). Inline `$$…$$` inside a paragraph is left
untouched — it does **not** become a display block.

Display blocks are preprocessed into ` ```math` fenced blocks before Flexmark
parses them, so the two forms are equivalent. Leading indentation on the
opening delimiter is preserved on the generated fences, which lets you nest
display math inside list items:

```markdown
1. Solve the Gaussian integral:$$\int_0^\infty e^{-x^2}\,dx = \frac{\sqrt{\pi}}{2}$$2. Now the next step…
```

### Fenced math: ```` ```math ````

The explicit fenced form is handy when you prefer code-block discipline or
need to paste a long expression.

````markdown
```math
\sum_{i=1}^{n} i = \frac{n(n+1)}{2}
```
````

## Output format

Server-rendered HTML uses the CSS classes configured on the GitLab extension:

- Inline math: `<code class="math-inline">…</code>` (LaTeX source as text)
- Display math: `<pre class="math-display"><code>…</code></pre>` (LaTeX source
  as text)

The React SPA re-processes this output through
[`remark-math`](https://github.com/remarkjs/remark-math) +
[`rehype-katex`](https://github.com/remarkjs/rehype-katex) when rendering
article content, which is what produces the typeset mathematics you see in the
browser. KaTeX CSS and fonts are bundled from the `katex` npm package into the
SPA build — no external CDN is needed.

## Supported LaTeX

The rendering uses KaTeX, which implements a subset of LaTeX focused on
mathematical typesetting. See the
[KaTeX support table](https://katex.org/docs/supported.html) for the exhaustive
list. Common features that work:

- Greek letters (`\alpha`, `\Sigma`), operators (`\sum`, `\prod`, `\int`)
- Fractions (`\frac{a}{b}`), roots (`\sqrt{x}`, `\sqrt[n]{x}`)
- Superscripts and subscripts (`x^2`, `a_{i,j}`)
- Matrices and arrays (`\begin{pmatrix}…\end{pmatrix}`)
- Aligned equations (`\begin{aligned}…\end{aligned}`)
- Math operators (`\sin`, `\log`, `\lim`)
- Mathematical symbols (`\infty`, `\partial`, `\nabla`, `\in`, `\subset`)

Unsupported LaTeX (e.g. `\newcommand`, arbitrary TikZ, custom packages) is
rejected by KaTeX and surfaces as a visible error in the rendered output so
authors notice the problem.

## Examples

Basic inline:

```markdown
For a right triangle,$a^2 + b^2 = c^2$.
```

Aligned system:

```markdown$$\begin{aligned}
\nabla \cdot \mathbf{E} &= \frac{\rho}{\varepsilon_0} \\
\nabla \cdot \mathbf{B} &= 0 \\
\nabla \times \mathbf{E} &= -\frac{\partial \mathbf{B}}{\partial t} \\
\nabla \times \mathbf{B} &= \mu_0 \mathbf{J} + \mu_0 \varepsilon_0 \frac{\partial \mathbf{E}}{\partial t}
\end{aligned}$$```

Matrix:

````markdown
```math
A = \begin{bmatrix}
1 & 2 & 3 \\
4 & 5 & 6 \\
7 & 8 & 9
\end{bmatrix}
```
````

Summation inside a list:

```markdown
- The arithmetic series identity:$\sum_{i=1}^{n} i = \tfrac{n(n+1)}{2}$- The geometric series:$\sum_{i=0}^{n} r^{i} = \tfrac{1 - r^{n+1}}{1 - r}$for$r \neq 1$```

## Validation on save

Authoring mistakes that *parse* as valid Markdown but render as broken math are
easy to ship unnoticed, so every page save runs a LaTeX structure check
(`MathValidationPageFilter`, KaTeX-oracle-derived and false-positive-guarded)
before the content is stored. Two failure modes are caught:

- **Single-line or text-glued `$$ … $$` display math is a blocking ERROR.** When
  the opening or closing `$$` is not on its own line (e.g. `Then $$E = mc^2$$ follows`),
  the parser treats it as inline text rather than a display block: the `=` is
  HTML-escaped to `&#61;` and the attribute extension silently swallows bracketed
  letters (`\mathbb{E}` becomes `\mathbb`). Because the result is always wrong, the
  save is rejected and you must isolate the delimiters on their own lines (or use a
  ```` ```math ```` fence).
- **Prose inside inline `$…$` is an advisory WARNING.** A common trigger is an
  unescaped currency figure — `it cost $5 and $10` parses the span between the two
  dollars as math. The save still succeeds, but the warning nudges you to escape
  the dollar (`\$5`) so the text isn't typeset as an equation.

Violations carry a body-relative line/column and surface everywhere a page is
written:

- **In the editor** — the `MathValidationSummary` panel lists each violation with
  click-to-jump; an ERROR disables Save until it is fixed.
- **Over REST** — `PUT /api/pages` returns structured `ContentViolation`s (HTTP
  `422` when any are errors, `200` with warnings otherwise).
- **Over the admin MCP write tools** — `write_pages` / `update_page` enforce the
  same gate and cite the offending span in their refusal.

## Configuration

Math is enabled by default. The relevant Flexmark options are set in
`MarkdownDocument.options(...)`:

| Option | Value | Effect |
|--------|-------|--------|
| `GitLabExtension.INLINE_MATH_PARSER` | `false` | Disables GitLab's ``$`…`$`` syntax in favour of Wikantik's custom `$…$` parser |
| `GitLabExtension.RENDER_BLOCK_MATH` | `true` | Emits display math via the GitLab renderer |
| `GitLabExtension.INLINE_MATH_CLASS` | `math-inline` | CSS class on inline math elements |
| `GitLabExtension.BLOCK_MATH_CLASS` | `math-display` | CSS class on display math elements |

No wiki properties currently override these — the behaviour is fixed by code.

## Implementation

| Concern | File |
|---------|------|
| Flexmark extension wiring | `wikantik-main/src/main/java/com/wikantik/parser/markdown/MarkdownDocument.java` |
| `$$…$$` → ```` ```math ```` preprocessor | `wikantik-main/src/main/java/com/wikantik/markdown/extensions/math/DisplayMathPreProcessor.java` |
| `$…$` inline parser | `wikantik-main/src/main/java/com/wikantik/markdown/extensions/math/InlineMathParser.java` |
| Preprocessor tests | `wikantik-main/src/test/java/com/wikantik/markdown/extensions/math/DisplayMathPreProcessorTest.java` |
| Frontend rendering pipeline | `wikantik-frontend/src/components/PageView.jsx` (uses `remark-math` + `rehype-katex`) |
| Frontend dependencies | `wikantik-frontend/package.json` (`katex`, `remark-math`, `rehype-katex`) |

## Tips for authors

- Escape a literal dollar sign with a backslash: `\$` — so `\$5` renders as
  `$5` and never triggers inline math.
- Keep display-math delimiters on their own lines. `$$x = 1$$` on a single line
  is treated as an inline construction and will not render as a block — and the
  save-time validator now rejects it as an error (see
  [Validation on save](#validation-on-save)).
- When in doubt, use the explicit ```` ```math ```` fence: the rules are the
  clearest and it composes well with lists, quotes, and definitions.
