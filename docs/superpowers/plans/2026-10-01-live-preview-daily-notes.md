# Live Preview & Daily Notes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an Obsidian-style Live Preview mode to the CodeMirror page editor (markdown rendered in place, raw syntax on the lines being edited), a Source/Live toggle, GFM parsing in the editor, and an "Open today's daily note" command.

**Architecture:** A pure function (`livePreviewSpecs` / `blockSpecs`) walks the Lezer GFM tree plus per-line regexes and returns plain `{from, to, kind, …}` specs; a ViewPlugin turns the inline specs for `view.visibleRanges` into decorations, and a StateField supplies the block widgets (block math, page embeds). The mode lives in a StateField toggled by a StateEffect so it survives react-codemirror's per-render reconfigure. Daily notes are a pure helper plus one global command.

**Tech Stack:** React 19, CodeMirror 6 (`@codemirror/view|state|language|lang-markdown`, `@uiw/react-codemirror` 4.25), KaTeX 0.16, vitest 4 + happy-dom.

**Spec:** `docs/superpowers/specs/2026-10-01-live-preview-daily-notes-design.md` (binding). Dependency: `docs/superpowers/specs/2026-10-01-native-wikilinks-design.md` — its frontend work (`[[ ]]` syntax, `GET /api/pages/{name}/embed`, a `WikiEmbed` preview component with a shared, deduped embed fetch cache, `linkAt` recognising `[[ ]]`) is on `main` before this plan runs. **Locate the actual module/function names with `grep -a` before using them; never guess.**

All paths below are relative to `wikantik-frontend/` unless they start with `docs/` or `CHANGELOG.md`.

## Global Constraints

- TDD: every task writes its failing test first, runs it red, then implements.
- Never swallow errors: every `catch` logs `console.warn('[live-preview] …', context, err?.message || err)` (or the module's own prefix) — no empty catches.
- Stage files by name (`git add <paths>`, paths relative to the repo root); never `git add -A`. Commit as:
  ```bash
  git commit -m "<subject>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_0116wAS9b7XPfXDLMyHmLmAg"
  ```
- Colours only through tokens `src/styles/globals.css` declares in **both** `:root` and `[data-theme="dark"]` (guards: `src/styles/tokens.test.js`, `src/utils/editorTheme.test.js`). Add `--font-display` to the editor guard's `THEME_INDEPENDENT` set (Task 6).
- Every `editorChromeSpec` selector starts with `&.cm-editor ` (guarded by `editorTheme.test.js`).
- No new npm dependencies. `markdownLanguage` comes from `@codemirror/lang-markdown` (already a dependency) — no direct `@lezer/markdown` dependency is needed.
- The document is changed **only** through view transactions. Live mode's single edit (task checkbox) dispatches on the view; never call `setBody` for a live-mode action (react-codemirror's typing latch defers `value` prop changes).
- Live mode never changes document text except the explicit checkbox click; saved text in live mode is byte-identical to source mode.
- Replace/hide decorations never span a line break; block widgets come only from a `StateField` with `block: true`.
- Every task ends green on: `cd wikantik-frontend && npx vitest run <its test files>`. Task 10 runs the full `npx vitest run`, `npm run lint`, `npm run build`.
- Shell `grep` is ugrep and silently skips some files — always use `grep -a`.
- Do not bump `katex`. Import `katex/dist/katex.min.css` only in `src/utils/livePreview/widgets.js`.

## Verified facts (spike run while planning, 2026-10-01)

- happy-dom + a real `EditorView` on a 3,000-line doc: `view.viewport` = `{from:0,to:1039}`, `view.visibleRanges` = one range of the same span, 35 `.cm-line` elements rendered. Visible-range-bounded building is therefore testable.
- An inline `Decoration.replace({widget})` and a `StateField`-provided `Decoration.replace({widget, block:true})` both render their widget DOM in happy-dom. `view.posAtDOM(el)` works (no layout needed); `posAtCoords`/`coordsAtPos` do not.
- `markdown({ base: markdownLanguage })` node names: `BulletList > ListItem > ListMark, Task > TaskMarker`; `Strikethrough > StrikethroughMark`; bare URLs → `URL` (no `Link` parent); `Table/TableHeader/TableRow/TableCell/TableDelimiter`; `Blockquote > QuoteMark, Paragraph` with continuation-line `QuoteMark`s **inside** the Paragraph; `> [!note] T` parses `[!note]` as a `Link` **without** a `URL` child; `$$\nx\n$$` is a plain `Paragraph`; `Image > LinkMark("![") LinkMark("]") LinkMark("(") URL LinkMark(")")`; `InlineCode > CodeMark`.
- `@uiw/react-codemirror` dispatches `StateEffect.reconfigure.of(allExtensions)` whenever `theme`, `extensions`, `basicSetup`, `onChange`, … change identity — and `CodeEditor` passes an inline `basicSetup` object, so this happens on **every** `CodeEditor` render. A `Compartment`'s content would be reset by it; a `StateField`'s value survives it. Its typing latch arms only on `docChanged` transactions, so effect-only transactions are harmless.
- Keymaps: no `@codemirror/*` keymap binds `Mod-e` (`Ctrl-e` is a mac-only emacs binding) or `Mod-Alt-d`/`Mod-Alt-n`. `PageEditor.jsx` has a window `keydown` handler for Mod-s (~line 486); `useGlobalHotkeys` handles Mod-K/O/P and bails on `altKey`.

## Rulings

- **Ruling:** live mode is held in a `StateField` (`liveModeField`, flipped by a `setLiveMode` StateEffect dispatched on the view) instead of a `Compartment` — react-codemirror reconfigures with the full extension list on every `CodeEditor` render, which resets compartment contents; field values survive reconfiguration. The spec's guarantees (editor never recreated, document/undo/selection/folds kept) hold and are tested.
- **Ruling:** inline math follows the server's `InlineMathParser` rule (content must not start or end with a space, `$$` never starts inline math, `\$` is literal) rather than `remark-math` — remark-math renders `$5 and $10` as math, which contradicts Review Focus 4; the server is the published renderer.
- **Ruling:** the daily-note hotkey is `Mod-Alt-n`, not `Mod-Alt-d` — Cmd-Opt-D is reserved by macOS (Dock hiding). `Mod-Alt-n` is free in CodeMirror, Chrome/Firefox and GNOME defaults. Matched on `event.code === 'KeyN'` because Option rewrites `event.key` on macOS.
- **Ruling:** `Mod-e` is handled by `PageEditor`'s existing window keydown handler (next to Mod-s), not a CodeMirror keymap, so it works whether or not the editor has focus (CodeMirror does not consume Mod-e, so the event reaches `window`).
- **Ruling:** a page embed `![[T]]` / `![[T#H]]` becomes a block widget only when it is the whole content of a top-level single-line paragraph; inside prose, lists or quotes it renders as a styled link (`T` / `T > H`) — block replace decorations must cover whole lines.
- **Ruling:** the callout title widget shows one generic callout glyph tinted with the callout colour; the per-type icons are `.article-prose`-scoped CSS masks in `article.css` and are not duplicated into the editor theme.
- **Ruling:** embed loading is injected through `livePreviewContext.loadEmbed` (a thin adapter over the native-wikilinks shared embed cache) so the CodeMirror extension imports neither React nor the API client and is testable with a fake loader.

## Review Focus

1. **A long page (2,000+ lines)** — typing stays interactive: inline decorations are built only for `view.visibleRanges`; block specs scan only top-level paragraphs. Pinned in Task 5 (`bounded to the visible ranges on a 3,000-line page`).
2. **Cursor inside a hidden range after an external edit** (rename/convert via `applyChanges`) — the line reveals, nothing throws. Pinned in Task 5 (`an external edit that leaves the caret inside a hidden marker reveals the line`).
3. **IME / mobile typing on a decorated line** — the caret line shows raw source, so composition never lands in a replaced range; typing never loses input. Pinned in Task 5 (`typing at the end of a decorated line keeps the raw source`) and Task 8 (interleaving test runs a third of its seeds in live mode).
4. **A `$` currency sign in prose** (`$5 and $10`) — not math. Pinned in Task 3 (`currency is never math`).
5. **Undo across a mode toggle** — history intact. Pinned in Task 7 (`undo across a live toggle restores the text and keeps the mode`).

## File structure

New, under `src/utils/`: `editorMarkdown.js` (GFM config, Task 1); `livePreview/ranges.js` (pure spec builders, Tasks 2–3); `livePreview/widgets.js` + `livePreview/embedSource.js` (WidgetTypes, embed adapter, Task 4); `livePreview/plugin.js` + `livePreview/index.js` (mode field, ViewPlugin, block StateField, Task 5); `dailyNote.js` (Task 9). New `src/hooks/useEditorMode.js` (Task 8). Modified: `editorTheme.js` (6), `CodeEditor.jsx` (1, 7), `EditorToolbar.jsx` / `editorCommands.js` / `PageEditor.jsx` / `globals.css` (8), `useGlobalCommands.js` / `useGlobalHotkeys.js` / `App.jsx` (9), `CHANGELOG.md` (10).

Task order: 1 → 2 → 3 → 5 → 7 → 8 → 10. Task 4 depends only on 1; Task 6 on nothing; Task 9 on nothing (4, 6, 9 may run in parallel with 2–3 — they touch disjoint files). Task 5 needs 2, 3, 4.

### Spec shape (shared by Tasks 2–5)
```js
/**
 * @typedef {Object} LiveSpec
 * @property {'line'|'mark'|'hide'|'widget'|'block'} kind
 * @property {number} from   // 'line': the line's start; others: range start
 * @property {number} to     // 'line': === from
 * @property {string} [cls]  // 'line' and 'mark': ONE css class
 * @property {WidgetData} [widget] // 'widget' (inline replace) and 'block' (block replace)
 * @property {string} [group]  // internal: ties one construct's specs together (Task 3 drops a cut construct whole)
 *
 * @typedef {{type:'bullet'} | {type:'checkbox', checked:boolean} | {type:'rule'}
 *   | {type:'callout-title', style:string, title:string}
 *   | {type:'image', src:string, alt:string, width?:number, height?:number}
 *   | {type:'math', tex:string, display:boolean}
 *   | {type:'text', text:string, cls:string}
 *   | {type:'embed', target:string, section:(string|null)}} WidgetData
 *
 * @typedef {{pageName?:string, attachments?:string[],
 *   loadEmbed?:(target:string, section:(string|null)) => Promise<{state:'ok'|'missing'|'restricted'|'error', html?:string}>}} LiveContext
 */
```
`line` and `mark` specs apply on active and inactive lines alike; `hide`, `widget` and `block` are emitted only when the line(s) they cover are inactive.

---

### Task 1: GFM dialect in the editor

**Files:**
- Create: `src/utils/editorMarkdown.js`
- Modify: `src/components/CodeEditor.jsx` (imports ~line 3; extensions memo ~403-411)
- Modify (tests): `src/utils/markdownFold.test.js`, `src/utils/calloutMarkers.test.js`, `src/utils/linkInteraction.test.js`, `src/components/CodeEditor.realCodeMirror.test.jsx`

**Interfaces:**
- Produces: `export const editorMarkdownConfig = { base: markdownLanguage, extensions: editorFoldConfig }` — tests build states with `markdown(editorMarkdownConfig)`; `CodeEditor` uses `markdown({ ...editorMarkdownConfig, codeLanguages: languages })`.
- [ ] **Step 1: Write the failing tests.** In `CodeEditor.realCodeMirror.test.jsx` add (imports: `syntaxTree, ensureSyntaxTree` from `@codemirror/language`):
```js
it('parses GitHub-flavoured markdown (task lists, strikethrough, tables)', () => {
  const { view } = mount('- [ ] todo ~~gone~~\n\n| a | b |\n|---|---|\n| 1 | 2 |\n');
  ensureSyntaxTree(view.state, view.state.doc.length, 5000);
  const names = new Set();
  syntaxTree(view.state).iterate({ enter: (n) => { names.add(n.name); } });
  expect([...names]).toEqual(expect.arrayContaining(['Task', 'TaskMarker', 'Strikethrough', 'Table']));
});
```
In `markdownFold.test.js`, `calloutMarkers.test.js` and `linkInteraction.test.js`, make every state builder run under both dialects so existing assertions are proven against the GFM tree. Pattern (adapt the helper name each file uses — `stateOf`, `at`, `mk`, the `DOC` state):
```js
import { markdownLanguage } from '@codemirror/lang-markdown';
const DIALECTS = [['CommonMark', {}], ['GFM', { base: markdownLanguage }]];
describe.each(DIALECTS)('calloutMarkerRanges (%s)', (_label, base) => {
  function stateOf(doc) {
    const state = EditorState.create({ doc, extensions: [markdown(base)] });
    ensureSyntaxTree(state, state.doc.length, 5000);
    return state;
  }
  // …existing it(...) blocks unchanged, now inside this describe…
});
```
For `markdownFold.test.js` pass `markdown({ ...base, extensions: editorFoldConfig })` where it currently passes `markdown({ extensions: editorFoldConfig })`, and `markdown(base)` where it passes `markdown()`. If the native-wikilinks work already changed `linkInteraction.test.js`, wrap whatever helper it now has the same way.
- [ ] **Step 2: Run red.** `cd wikantik-frontend && npx vitest run src/components/CodeEditor.realCodeMirror.test.jsx src/utils/markdownFold.test.js src/utils/calloutMarkers.test.js src/utils/linkInteraction.test.js`
Expected: the new GFM test FAILS (no `Task`/`Strikethrough`/`Table`); the GFM-parametrised suites PASS (if any GFM case fails, stop — that is a real incompatibility the spec says must stay green; fix the plugin, not the test).
- [ ] **Step 3: Implement.** `src/utils/editorMarkdown.js`:
```js
import { markdownLanguage } from '@codemirror/lang-markdown';
import { editorFoldConfig } from './markdownFold';
/**
 * The editor's markdown dialect: GitHub-flavoured (tables, strikethrough, task lists, autolinks) so the source
 * tree matches what the server and preview render, plus the fold-limiting parser extensions. Used in both
 * source and live mode. Tests build states with markdown(editorMarkdownConfig).
 */
export const editorMarkdownConfig = { base: markdownLanguage, extensions: editorFoldConfig };
```
In `CodeEditor.jsx`: `import { markdown } from '@codemirror/lang-markdown';` stays; replace the `editorFoldConfig` import usage in the memo with `markdown({ ...editorMarkdownConfig, codeLanguages: languages })` and import `editorMarkdownConfig` from `../utils/editorMarkdown` (keep `frontmatterFold, revealEffects` imported from `markdownFold`).
- [ ] **Step 4: Run green.** Same command as Step 2 — all PASS. Then `npx vitest run src/components` (CodeEditor/PageEditor suites use the real tree) — PASS.
- [ ] **Step 5: Commit** — `git add wikantik-frontend/src/utils/editorMarkdown.js wikantik-frontend/src/components/CodeEditor.jsx wikantik-frontend/src/components/CodeEditor.realCodeMirror.test.jsx wikantik-frontend/src/utils/markdownFold.test.js wikantik-frontend/src/utils/calloutMarkers.test.js wikantik-frontend/src/utils/linkInteraction.test.js`; subject `feat(editor): parse GitHub-flavoured markdown in the editor` (+ trailer, Global Constraints).

---

### Task 2: Pure live-preview specs — tree constructs

**Files:**
- Create: `src/utils/livePreview/ranges.js`
- Test: `src/utils/livePreview/ranges.test.js`

**Interfaces:**
- Consumes: `editorMarkdownConfig` (Task 1); `MARKER, styleOf, defaultTitle` from `src/utils/remarkCallouts.js`.
- Produces (exact exports):
  - `activeLinesOf(state): Set<number>` — 1-based line numbers touched by any selection range.
  - `livePreviewSpecs(state, activeLines, { from = 0, to = state.doc.length, context = {} } = {}): LiveSpec[]` — sorted by `from` then `to`; never a `hide`/`widget` containing `\n`; never two overlapping `hide`/`widget`.
  - `isBlockMathText(text): boolean`, `resolveImageSrc(url, context): string|null`, `attachmentUrl(owner, file): string`.
  - Internal (not exported) `collect…` structure that Task 3 extends: an `excluded` array of `[from, to)` ranges (code, tables, HTML, frontmatter, block-math paragraphs, inline code).
- [ ] **Step 1: Write the failing tests** (`ranges.test.js`):
```js
import { describe, it, expect } from 'vitest';
import { EditorState, EditorSelection } from '@codemirror/state';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { editorMarkdownConfig } from '../editorMarkdown';
import { activeLinesOf, livePreviewSpecs, resolveImageSrc } from './ranges';
function stateOf(doc, selection) {
  const state = EditorState.create({
    doc, selection,
    extensions: [markdown(editorMarkdownConfig), EditorState.allowMultipleSelections.of(true)],
  });
  ensureSyntaxTree(state, state.doc.length, 5000);
  return state;
}
const specsOf = (doc, active, context) => {
  const state = stateOf(doc);
  return { state, specs: livePreviewSpecs(state, new Set(active), { context }) };
};
/** Line `n` as the reader sees it: hidden ranges removed, widgets shown as [type] (text widgets as their text). */
function visible(state, specs, n) {
  const line = state.doc.line(n);
  let out = '';
  let pos = line.from;
  const cuts = specs.filter((s) => (s.kind === 'hide' || s.kind === 'widget') && s.from >= line.from && s.to <= line.to);
  for (const s of cuts) {
    out += state.sliceDoc(pos, s.from) + (s.kind === 'widget' ? (s.widget.type === 'text' ? s.widget.text : `[${s.widget.type}]`) : '');
    pos = s.to;
  }
  return out + state.sliceDoc(pos, line.to);
}
const show = (state, specs) => specs.map((s) => (s.kind === 'line'
  ? `line ${s.cls} @${state.doc.lineAt(s.from).number}`
  : `${s.kind}${s.cls ? ` ${s.cls}` : ''}${s.widget ? ` ${s.widget.type}` : ''} ${JSON.stringify(state.sliceDoc(s.from, s.to))}`));
const replacing = (specs) => specs.filter((s) => s.kind === 'hide' || s.kind === 'widget');
describe('activeLinesOf', () => {
  it('collects every line any selection range touches, for every cursor', () => {
    const doc = 'a\nb\nc\nd\ne\nf';
    const state = stateOf(doc, EditorSelection.create([EditorSelection.cursor(0), EditorSelection.range(4, 8)]));
    expect([...activeLinesOf(state)].sort()).toEqual([1, 3, 4, 5]);
  });
});
describe('livePreviewSpecs — inline formatting', () => {
  const DOC = '# Title\n\nSome **bold** and *it* and ~~del~~ and `code`\n\nend';
  it('hides markers on inactive lines and styles the text', () => {
    const { state, specs } = specsOf(DOC, [5]);
    expect(show(state, specs)).toEqual(expect.arrayContaining([
      'line cm-lp-h1 @1', 'hide "# "',
      'mark cm-lp-strong "**bold**"', 'mark cm-lp-em "*it*"', 'mark cm-lp-del "~~del~~"', 'mark cm-lp-code "`code`"',
    ]));
    expect(visible(state, specs, 1)).toBe('Title');
    expect(visible(state, specs, 3)).toBe('Some bold and it and del and code');
  });
  it('keeps styling but hides nothing on an active line', () => {
    const { state, specs } = specsOf(DOC, [1, 3]);
    expect(replacing(specs)).toEqual([]);
    expect(show(state, specs)).toEqual(expect.arrayContaining(['line cm-lp-h1 @1', 'mark cm-lp-strong "**bold**"']));
  });
  it('handles nested emphasis', () => {
    const { state, specs } = specsOf('***both*** and **a *b* c**\n\nend', [3]);
    expect(visible(state, specs, 1)).toBe('both and a b c');
    expect(show(state, specs)).toEqual(expect.arrayContaining(['mark cm-lp-em "*b*"', 'mark cm-lp-strong "**a *b* c**"']));
  });
});
describe('livePreviewSpecs — links and images', () => {
  it('shows only the text of an inline link', () => {
    const { state, specs } = specsOf('see [the hub](IndexFundsHub) now\n\nend', [3]);
    expect(visible(state, specs, 1)).toBe('see the hub now');
    expect(show(state, specs)).toContain('mark cm-lp-link "the hub"');
  });
  it('leaves reference, shortcut and callout-marker brackets as source', () => {
    const { state, specs } = specsOf('[x][r] and [y]\n\n[r]: http://a.example\n\nend', [5]);
    expect(visible(state, specs, 1)).toBe('[x][r] and [y]');
  });
  it('replaces an image with an image widget, resolving attachments like remarkAttachments', () => {
    const { specs } = specsOf('![A cat](Cat.PNG)\n\nend', [3], { pageName: 'Pets', attachments: ['cat.png'] });
    expect(specs.find((s) => s.kind === 'widget').widget).toEqual({ type: 'image', src: '/attach/Pets/cat.png', alt: 'A cat' });
  });
  it('resolveImageSrc keeps absolute URLs and refuses script schemes', () => {
    expect(resolveImageSrc('https://x.example/a.png', {})).toBe('https://x.example/a.png');
    expect(resolveImageSrc('/attach/P/a.png', {})).toBe('/attach/P/a.png');
    expect(resolveImageSrc('javascript:alert(1)', {})).toBeNull();
    expect(resolveImageSrc('missing.png', { pageName: 'P', attachments: [] })).toBe('missing.png');
  });
});
describe('livePreviewSpecs — blocks', () => {
  it('quotes: line class + hidden "> " marks', () => {
    const { state, specs } = specsOf('> a quote\n> more\n\nend', [4]);
    expect(show(state, specs)).toEqual(expect.arrayContaining(['line cm-lp-quote @1', 'line cm-lp-quote @2']));
    expect(visible(state, specs, 2)).toBe('more');
  });
  it('callouts: tinted lines, title line, marker replaced by a title widget (aliases resolved)', () => {
    const { state, specs } = specsOf('> [!caution] Careful\n> body\n\n> [!faq]\n> x\n\nend', [7]);
    expect(show(state, specs)).toEqual(expect.arrayContaining([
      'line cm-lp-callout @1', 'line cm-lp-callout-warning @1', 'line cm-lp-callout-title @1', 'line cm-lp-callout-warning @2',
      'line cm-lp-callout-question @4',
    ]));
    expect(visible(state, specs, 1)).toBe('[callout-title]Careful');
    const titles = specs.filter((s) => s.widget?.type === 'callout-title').map((s) => s.widget);
    expect(titles).toEqual([{ type: 'callout-title', style: 'warning', title: '' }, { type: 'callout-title', style: 'question', title: 'Faq' }]);
  });
  it('rules, bullets and tasks become widgets; ordered markers stay', () => {
    const doc = 'a\n\n***\n\n- one\n  - nested\n\n1. first\n\n- [ ] todo\n- [x] done\n\nend';
    const { state, specs } = specsOf(doc, [13]);
    expect(visible(state, specs, 3)).toBe('[rule]');
    expect(visible(state, specs, 5)).toBe('[bullet] one');
    expect(visible(state, specs, 6)).toBe('  [bullet] nested');
    expect(visible(state, specs, 8)).toBe('1. first');
    expect(visible(state, specs, 10)).toBe('[checkbox] todo');
    expect(specs.filter((s) => s.widget?.type === 'checkbox').map((s) => s.widget.checked)).toEqual([false, true]);
  });
  it('fenced code: fence and body line classes, nothing inside decorated', () => {
    const { state, specs } = specsOf('```js\nconst a = **1**;\n```\n\nend', [5]);
    expect(show(state, specs)).toEqual(['line cm-lp-fence @1', 'line cm-lp-codeblock @2', 'line cm-lp-fence @3']);
  });
  it('tables, HTML and frontmatter stay untouched source', () => {
    const doc = '---\ntitle: x\n---\n\n| a | **b** |\n|---|---|\n| 1 | 2 |\n\n<div>**x**</div>\n\nend';
    const { specs } = specsOf(doc, [11]);
    expect(specs).toEqual([]);
  });
  it('limits the walk to the requested range', () => {
    const state = stateOf('**a**\n\n**b**\n');
    const third = state.doc.line(3);
    const specs = livePreviewSpecs(state, new Set(), { from: third.from, to: third.to });
    expect(specs.every((s) => s.from >= third.from)).toBe(true);
  });
  it('never emits a replacing spec that spans a line break or overlaps another', () => {
    const doc = 'p *a\nb* [t](\nu) **x** ~~y~~\n> [!note] t\n> - [ ] q\n';
    const { state, specs } = specsOf(doc, []);
    const rep = replacing(specs);
    expect(rep.every((s) => !state.sliceDoc(s.from, s.to).includes('\n'))).toBe(true);
    for (let i = 1; i < rep.length; i += 1) expect(rep[i].from).toBeGreaterThanOrEqual(rep[i - 1].to);
  });
});
```
- [ ] **Step 2: Run red.** `npx vitest run src/utils/livePreview/ranges.test.js` → FAIL (module not found).
- [ ] **Step 3: Implement `ranges.js`.**
```js
import { syntaxTree } from '@codemirror/language';
import { MARKER, styleOf, defaultTitle } from '../remarkCallouts';
/** Nodes whose whole range stays source and is never scanned by the line regexes (Task 3). */
const SKIP = new Set(['Frontmatter', 'Table', 'HTMLBlock', 'CommentBlock', 'ProcessingInstructionBlock', 'CodeBlock', 'LinkReference']);
const HEADING = /^ATXHeading([1-6])$/;
const BLOCK_MATH = /^\$\$[ \t]*\n[\s\S]*\n[ \t]*\$\$[ \t]*$/;
const CLOSING_FENCE = /^(?:[ \t]*>)*[ \t]*(`{3,}|~{3,})[ \t]*$/;
const SCHEME = /^[a-z][a-z0-9+.-]*:/i;
export function activeLinesOf(state) {
  const lines = new Set();
  for (const r of state.selection.ranges) {
    const last = state.doc.lineAt(r.to).number;
    for (let n = state.doc.lineAt(r.from).number; n <= last; n += 1) lines.add(n);
  }
  return lines;
}
/** A paragraph that is display math: `$$` alone on its first and last line. */
export function isBlockMathText(text) { return BLOCK_MATH.test(text); }
export function attachmentUrl(owner, file) { return `/attach/${owner}/${file}`; }
/** The image URL the preview would use (remarkAttachments rules); null for a non-http scheme. */
export function resolveImageSrc(url, { pageName, attachments = [] } = {}) {
  if (/^https?:\/\//i.test(url) || url.startsWith('/')) return url;
  if (SCHEME.test(url)) return null;
  const hit = attachments.find((a) => a.toLowerCase() === url.toLowerCase());
  return hit && pageName ? attachmentUrl(pageName, hit) : url;
}
/** Drops replacing specs that cross a line break or overlap an earlier one; sorts by from, then to. */
function finalize(doc, specs) {
  const out = [];
  let lastEnd = -1;
  for (const s of [...specs].sort((a, b) => a.from - b.from || a.to - b.to)) {
    if (s.kind === 'hide' || s.kind === 'widget') {
      if (doc.sliceString(s.from, s.to).includes('\n') || s.from < lastEnd) continue;
      lastEnd = s.to;
    }
    out.push(s);
  }
  return out;
}
export function livePreviewSpecs(state, activeLines, { from = 0, to = state.doc.length, context = {} } = {}) {
  const doc = state.doc;
  const specs = [];
  const excluded = [];
  const lineClasses = new Map(); // line start -> Set<cls>
  const inactive = (pos) => !activeLines.has(doc.lineAt(pos).number);
  const spaceAfter = (pos) => (doc.sliceString(pos, pos + 1) === ' ' ? 1 : 0);
  // `group` ties a construct's specs together (e.g. an emphasis mark and its two hidden markers) so Task 3 can
  // drop a construct as a whole when it cuts through a wikilink/math range.
  const hide = (a, b, group) => { if (b > a && inactive(a)) specs.push({ kind: 'hide', from: a, to: b, group }); };
  const mark = (a, b, cls, group) => { if (b > a) specs.push({ kind: 'mark', from: a, to: b, cls, group }); };
  const widget = (a, b, w) => { if (b > a && inactive(a)) specs.push({ kind: 'widget', from: a, to: b, widget: w }); };
  const addLine = (lineStart, cls) => {
    if (!lineClasses.has(lineStart)) lineClasses.set(lineStart, new Set());
    lineClasses.get(lineStart).add(cls);
  };
  const eachLine = (a, b, fn) => {
    const last = doc.lineAt(Math.min(b, to)).number;
    for (let n = doc.lineAt(Math.max(a, from)).number; n <= last; n += 1) fn(doc.line(n));
  };
  const markedInline = (node, cls, markName) => {
    const g = `${node.name}@${node.from}`;
    mark(node.from, node.to, cls, g);
    for (let c = node.node.firstChild; c; c = c.nextSibling) if (c.name === markName) hide(c.from, c.to, g);
  };
  const heading = (node, level) => {
    addLine(doc.lineAt(node.from).from, `cm-lp-h${level}`);
    const m = node.node.firstChild;
    if (m && m.name === 'HeaderMark') hide(m.from, m.to + spaceAfter(m.to));
  };
  const link = (node) => {
    const n = node.node;
    const marks = n.getChildren('LinkMark');
    if (!n.getChild('URL') || marks.length < 2 || doc.sliceString(marks[0].from, marks[0].to) !== '[') return;
    const g = `Link@${n.from}`;
    mark(marks[0].to, marks[1].from, 'cm-lp-link', g);
    hide(marks[0].from, marks[0].to, g);
    hide(marks[1].from, n.to, g);
  };
  const image = (node) => {
    const n = node.node;
    const url = n.getChild('URL');
    const marks = n.getChildren('LinkMark');
    if (!url || marks.length < 2) return;
    const src = resolveImageSrc(doc.sliceString(url.from, url.to), context);
    if (src) widget(n.from, n.to, { type: 'image', src, alt: doc.sliceString(marks[0].to, marks[1].from) });
  };
  const blockquote = (node) => {
    const n = node.node;
    let child = n.firstChild;
    while (child && child.name === 'QuoteMark') child = child.nextSibling;
    const m = child && child.name === 'Paragraph'
      ? MARKER.exec(doc.sliceString(child.from, Math.min(child.to, child.from + 80))) : null;
    const firstLine = doc.lineAt(n.from).number;
    eachLine(n.from, n.to, (line) => {
      if (!m) { addLine(line.from, 'cm-lp-quote'); return; }
      addLine(line.from, 'cm-lp-callout');
      addLine(line.from, `cm-lp-callout-${styleOf(m[1])}`);
      if (line.number === firstLine) addLine(line.from, 'cm-lp-callout-title');
    });
    if (m) {
      const markerTo = child.from + m[0].length;
      const rest = doc.sliceString(markerTo, doc.lineAt(child.from).to).trim();
      widget(child.from, markerTo, { type: 'callout-title', style: styleOf(m[1]), title: rest ? '' : defaultTitle(m[1]) });
    }
  };
  const listItem = (node) => {
    const n = node.node;
    const listMark = n.getChild('ListMark');
    if (!listMark) return;
    const marker = n.getChild('Task')?.getChild('TaskMarker');
    if (marker) {
      const ch = doc.sliceString(marker.from + 1, marker.from + 2);
      widget(listMark.from, marker.to, { type: 'checkbox', checked: ch === 'x' || ch === 'X' });
    } else if (n.parent?.name === 'BulletList') {
      widget(listMark.from, listMark.to, { type: 'bullet' });
    }
  };
  const fenced = (node) => {
    const first = doc.lineAt(node.from).number;
    const last = doc.lineAt(node.to).number;
    const closed = last > first && CLOSING_FENCE.test(doc.line(last).text);
    for (let n = first; n <= last; n += 1) {
      const line = doc.line(n);
      if (line.to < from || line.from > to) continue;
      addLine(line.from, n === first || (closed && n === last) ? 'cm-lp-fence' : 'cm-lp-codeblock');
    }
  };
  syntaxTree(state).iterate({
    from, to,
    enter: (node) => {
      const { name } = node;
      if (SKIP.has(name)) { excluded.push([node.from, node.to]); return false; }
      if (name === 'FencedCode') { fenced(node); excluded.push([node.from, node.to]); return false; }
      if (name === 'InlineCode') { markedInline(node, 'cm-lp-code', 'CodeMark'); excluded.push([node.from, node.to]); return false; }
      if (name === 'Paragraph' && isBlockMathText(doc.sliceString(node.from, node.to))) { excluded.push([node.from, node.to]); return false; }
      if (name === 'Image') { image(node); return false; }
      const h = HEADING.exec(name);
      if (h) { heading(node, h[1]); return undefined; }
      switch (name) {
        case 'Emphasis': markedInline(node, 'cm-lp-em', 'EmphasisMark'); break;
        case 'StrongEmphasis': markedInline(node, 'cm-lp-strong', 'EmphasisMark'); break;
        case 'Strikethrough': markedInline(node, 'cm-lp-del', 'StrikethroughMark'); break;
        case 'Link': link(node); break;
        case 'Blockquote': blockquote(node); break;
        case 'QuoteMark': hide(node.from, node.to + spaceAfter(node.to)); break;
        case 'HorizontalRule': widget(node.from, node.to, { type: 'rule' }); break;
        case 'ListItem': listItem(node); break;
        default: break;
      }
      return undefined;
    },
  });
  for (const [lineStart, classes] of lineClasses) {
    for (const cls of classes) specs.push({ kind: 'line', from: lineStart, to: lineStart, cls });
  }
  return finalize(doc, specs);
}
```
Notes for the implementer:
- `node.node` is the `SyntaxNode` (iterate hands you a `SyntaxNodeRef`); `getChild` / `getChildren` / `parent` live on it.
- Do **not** hide closing heading `#`s — the spec hides only the leading `#…` and its space.
- The `[!note]` callout marker is a URL-less `Link`; `link()` ignores it because it requires a `URL` child.
- Keep `excluded` and the `group` field — Task 3 consumes both (`// consumed by the line-regex pass`).
- [ ] **Step 4: Run green.** `npx vitest run src/utils/livePreview/ranges.test.js` → PASS. If a node-name assumption fails, inspect with a throwaway `syntaxTree(state).iterate` print, fix the code, and keep the test.
- [ ] **Step 5: Commit** — `git add wikantik-frontend/src/utils/livePreview/ranges.js wikantik-frontend/src/utils/livePreview/ranges.test.js`; subject `feat(editor): pure live-preview specs for markdown tree constructs` (+ trailer, Global Constraints).

---

### Task 3: Pure live-preview specs — wikilinks, math, plugins, block specs

**Files:**
- Modify: `src/utils/livePreview/ranges.js`
- Test: `src/utils/livePreview/ranges.test.js` (append)

**Interfaces:**
- Consumes: Task 2's `livePreviewSpecs` internals (`excluded`, `specs`, `inactive`, `hide`, `mark`, `widget`, `finalize`), `resolveImageSrc`, `attachmentUrl`, `isBlockMathText`.
- Produces:
  - `parseWikiTarget(inner: string): { target: string, heading: string|null, alias: string|null }`
  - `pageEmbedLine(text: string, context): { target, heading } | null` — the paragraph text is exactly one page embed.
  - `blockSpecs(state, activeLines, context = {}): LiveSpec[]` — `kind: 'block'` specs (`math` display, `embed`), each covering whole lines; scans only top-level paragraphs.
  - `livePreviewSpecs` additionally emits wikilink, inline-math and plugin-pill specs; tree specs that partially overlap one of these "claimed" ranges are dropped.

Before writing the wikilink regex, run `grep -a -rn "\\[\\[" src/utils --include=*.js -l | grep -v test` and open what the native-wikilinks work added (e.g. `remarkWikiLinks`). If it exports a target parser with the same semantics (`T`, `T#H`, `T|A`, `T#H|A`, `#H`; empty target only with a heading; no leading whitespace), import it in place of `parseWikiTarget`'s body and keep `parseWikiTarget` as a thin wrapper returning the shape above. Record which you did in the commit message.
- [ ] **Step 1: Write the failing tests** (append; reuse `stateOf`, `specsOf`, `visible`, `show`, `replacing`):
```js
import { blockSpecs, parseWikiTarget } from './ranges';
describe('parseWikiTarget', () => {
  it('splits target, heading and alias', () => {
    expect(parseWikiTarget('Page')).toEqual({ target: 'Page', heading: null, alias: null });
    expect(parseWikiTarget('Page#Setup Steps|Go')).toEqual({ target: 'Page', heading: 'Setup Steps', alias: 'Go' });
    expect(parseWikiTarget('#Intro')).toEqual({ target: '', heading: 'Intro', alias: null });
  });
});
describe('livePreviewSpecs — wikilinks', () => {
  const ctx = { pageName: 'Here', attachments: ['pic.png'] };
  it.each([
    ['[[Page]]', 'Page'], ['[[Page|Alias]]', 'Alias'], ['[[Page#Setup]]', 'Page > Setup'],
    ['[[#Setup]]', 'Setup'], ['[[Page#Setup|Go]]', 'Go'],
  ])('%s reads as %s', (src, shown) => {
    const { state, specs } = specsOf(`x ${src} y\n\nend`, [3], ctx);
    expect(visible(state, specs, 1)).toBe(`x ${shown} y`);
    expect(specs.some((s) => s.kind === 'mark' && s.cls === 'cm-lp-link')).toBe(true);
  });
  it('keeps the link style but hides nothing on the active line', () => {
    const { specs } = specsOf('x [[Page]] y', [1], ctx);
    expect(replacing(specs)).toEqual([]);
    expect(specs.some((s) => s.cls === 'cm-lp-link')).toBe(true);
  });
  it('leaves prose brackets, inline code and fenced code alone', () => {
    const doc = 'if [[ -f "$x" ]] then\n\n`[[Page]]`\n\n```\n[[Page]]\n```\n\nend';
    const { state, specs } = specsOf(doc, [9], ctx);
    expect(visible(state, specs, 1)).toBe('if [[ -f "$x" ]] then');
    expect(specs.filter((s) => s.cls === 'cm-lp-link')).toEqual([]);
  });
  it('renders attachment image embeds inline, honouring the size', () => {
    const { specs } = specsOf('![[Owner/a.png|300]] ![[pic.png|300x200]] ![[pic.png|A cat]]\n\nend', [3], ctx);
    expect(specs.filter((s) => s.kind === 'widget').map((s) => s.widget)).toEqual([
      { type: 'image', src: '/attach/Owner/a.png', alt: 'a.png', width: 300 },
      { type: 'image', src: '/attach/Here/pic.png', alt: 'pic.png', width: 300, height: 200 },
      { type: 'image', src: '/attach/Here/pic.png', alt: 'A cat' },
    ]);
  });
  it('shows a non-image attachment embed and an inline page embed as links', () => {
    const { state, specs } = specsOf('get ![[Owner/doc.pdf]] or see ![[Other#Intro]] here\n\nend', [3], ctx);
    expect(visible(state, specs, 1)).toBe('get Owner/doc.pdf or see Other > Intro here');
  });
  it('leaves a whole-line page embed to blockSpecs (no inline specs)', () => {
    const { specs } = specsOf('![[Other]]\n\nend', [3], ctx);
    expect(specs).toEqual([]);
  });
  it('drops emphasis that would cut through a wikilink, keeps emphasis around one', () => {
    const { state, specs } = specsOf('**bold [[Page|P]]** and *a [[B*c]]*\n\nend', [3], ctx);
    // Either Lezer's emphasis wraps the whole wikilink (kept: both * hidden) or it ends inside it (dropped as a
    // whole: both * visible) — never one marker hidden and the other shown.
    expect(visible(state, specs, 1)).toMatch(/^bold P and (\*a B\*c\*|a B\*c)$/);
  });
});
describe('livePreviewSpecs — math and plugins', () => {
  it('renders inline math on inactive lines', () => {
    const { specs } = specsOf('a $x+y$ b\n\nend', [3]);
    expect(specs.filter((s) => s.kind === 'widget').map((s) => s.widget)).toEqual([{ type: 'math', tex: 'x+y', display: false }]);
  });
  it.each(['$5 and $10', 'costs $5 or $6 today', '$ x $', '\\$5$', '`$x$`', '$$x$ y'])('currency is never math: %s', (src) => {
    const { specs } = specsOf(`${src}\n\nend`, [3]);
    expect(specs.filter((s) => s.widget?.type === 'math' && s.widget.tex !== 'x')).toEqual([]);
    if (src !== '$$x$ y') expect(specs.filter((s) => s.widget?.type === 'math')).toEqual([]);
  });
  it('does not style emphasis inside math, and math on the active line stays source', () => {
    expect(specsOf('$a*b*c$\n\nend', [3]).specs.filter((s) => s.cls === 'cm-lp-em')).toEqual([]);
    expect(replacing(specsOf('$x$', [1]).specs)).toEqual([]);
  });
  it('marks wiki plugins as a pill and leaves them visible', () => {
    const { state, specs } = specsOf('[{TableOfContents}] and [{Image src=a}]()\n\nend', [3]);
    expect(show(state, specs)).toEqual(expect.arrayContaining(['mark cm-lp-plugin "[{TableOfContents}]"', 'mark cm-lp-plugin "[{Image src=a}]()"']));
    expect(visible(state, specs, 1)).toBe('[{TableOfContents}] and [{Image src=a}]()');
  });
  it('a $$ block paragraph gets no inline specs', () => {
    expect(specsOf('$$\na*b*c\n$$\n\nend', [5]).specs).toEqual([]);
  });
});
describe('blockSpecs', () => {
  const block = (doc, active, ctx) => { const state = stateOf(doc); return { state, specs: blockSpecs(state, new Set(active), ctx) }; };
  it('replaces an inactive $$ paragraph with a display-math block covering whole lines', () => {
    const { specs } = block('$$\nx^2\n$$\n\nend', [5]);
    expect(specs).toEqual([{ kind: 'block', from: 0, to: 9, widget: { type: 'math', tex: 'x^2', display: true } }]);
  });
  it('is inactive only while no line of the block is active', () => {
    expect(block('$$\nx^2\n$$\n\nend', [2]).specs).toEqual([]);
  });
  it('embeds a page (or section) alone on its line, not attachments or inline embeds', () => {
    const { specs } = block('![[Other#Intro]]\n\n![[Owner/a.png]]\n\nsee ![[X]]\n\n![[pic.png]]\n\nend', [9], { attachments: ['pic.png'] });
    expect(specs.map((s) => s.widget)).toEqual([{ type: 'embed', target: 'Other', section: 'Intro' }]);
  });
});
```
- [ ] **Step 2: Run red.** `npx vitest run src/utils/livePreview/ranges.test.js` → new tests FAIL (`blockSpecs`/`parseWikiTarget` missing; no wikilink/math specs).
- [ ] **Step 3: Implement** (add to `ranges.js`):
```js
const WIKILINK = /(!?)\[\[([^[\]\n]+?)\]\]/g;
const INLINE_MATH = /\$([^ $\n](?:[^$\n]*[^ $\n])?)\$/y; // the server's InlineMathParser rule
const PLUGIN = /\[\{[^\n]*?\}\](?:\(\))?/g;
const IMAGE_EXT = /\.(png|jpe?g|gif|svg|webp|avif|bmp)$/i;
const SIZE = /^(\d+)(?:x(\d+))?$/;
export function parseWikiTarget(inner) {
  const bar = inner.indexOf('|');
  const main = bar >= 0 ? inner.slice(0, bar) : inner;
  const hash = main.indexOf('#');
  return {
    target: (hash >= 0 ? main.slice(0, hash) : main).trim(),
    heading: hash >= 0 ? main.slice(hash + 1).trim() : null,
    alias: bar >= 0 ? inner.slice(bar + 1) : null,
  };
}
const isAttachment = (target, context) => target.includes('/') || (context.attachments ?? []).includes(target);
const validInner = (inner) => !/^\s/.test(inner) && (parseWikiTarget(inner).target !== '' || parseWikiTarget(inner).heading);
export function pageEmbedLine(text, context = {}) {
  const m = /^!\[\[([^[\]\n]+)\]\]$/.exec(text.trim());
  if (!m || !validInner(m[1])) return null;
  const { target, heading } = parseWikiTarget(m[1]);
  return !target || isAttachment(target, context) ? null : { target, heading: heading || null };
}
export function blockSpecs(state, activeLines, context = {}) {
  const doc = state.doc;
  const out = [];
  const anyActive = (a, b) => {
    for (let n = doc.lineAt(a).number, e = doc.lineAt(b).number; n <= e; n += 1) if (activeLines.has(n)) return true;
    return false;
  };
  for (let node = syntaxTree(state).topNode.firstChild; node; node = node.nextSibling) {
    if (node.name !== 'Paragraph' || anyActive(node.from, node.to)) continue;
    const from = doc.lineAt(node.from).from;
    const to = doc.lineAt(node.to).to;
    const text = doc.sliceString(node.from, node.to);
    if (isBlockMathText(text)) {
      const tex = text.replace(/^\$\$[ \t]*\n/, '').replace(/\n[ \t]*\$\$[ \t]*$/, '');
      out.push({ kind: 'block', from, to, widget: { type: 'math', tex, display: true } });
      continue;
    }
    const embed = doc.lineAt(node.from).number === doc.lineAt(node.to).number ? pageEmbedLine(text, context) : null;
    if (embed) out.push({ kind: 'block', from, to, widget: { type: 'embed', target: embed.target, section: embed.heading } });
  }
  return out;
}
```
Inside `livePreviewSpecs`, after the tree walk and **before** pushing line classes / `finalize`, add the line-regex pass:
```js
  const claimed = [];
  const isExcluded = (a, b) => excluded.some(([x, y]) => a < y && b > x);
  const claim = (a, b) => claimed.push([a, b]);
  eachLine(from, to, (line) => {
    if (isExcluded(line.from, line.to) && excluded.some(([x, y]) => x <= line.from && y >= line.to)) return; // fully inside code/table/…
    const text = line.text;
    for (const m of text.matchAll(WIKILINK)) {
      const a = line.from + m.index;
      const b = a + m[0].length;
      const inner = m[2];
      if (!validInner(inner) || isExcluded(a, b)) continue;
      claim(a, b);
      wikiSpecs(a, b, m[1] === '!', inner, text.trim() === m[0]);
    }
    for (const m of text.matchAll(PLUGIN)) {
      const a = line.from + m.index;
      if (isExcluded(a, a + m[0].length)) continue;
      claim(a, a + m[0].length);
      mark(a, a + m[0].length, 'cm-lp-plugin');
    }
    for (let i = 0; i < text.length; i += 1) {
      if (text[i] !== '$' || text[i + 1] === '$' || (i > 0 && text[i - 1] === '\\')) continue;
      INLINE_MATH.lastIndex = i;
      const m = INLINE_MATH.exec(text);
      if (!m) continue;
      const a = line.from + i;
      const b = a + m[0].length;
      if (isExcluded(a, b) || claimed.some(([x, y]) => a < y && b > x)) continue;
      claim(a, b);
      widget(a, b, { type: 'math', tex: m[1], display: false });
      i += m[0].length - 1;
    }
  });
```
Note the `$$` rule mirrors the server exactly: a `$` followed by `$` never *starts* inline math, but scanning continues at the next character (the server's `parse()` returns false and moves on), so `$$x$ y` yields math `x` from the second `$` — the test tolerates that one case on purpose.

`wikiSpecs(a, b, embed, inner, wholeLine)` (a closure inside `livePreviewSpecs`, declared before the line-regex pass — `const` arrows are not hoisted):
```js
  const wikiSpecs = (a, b, embed, inner, wholeLine) => {
    const open = a + (embed ? 3 : 2);
    const close = b - 2;
    const { target, heading, alias } = parseWikiTarget(inner);
    const attachment = target && isAttachment(target, context);
    if (embed && attachment && IMAGE_EXT.test(target)) {
      const size = alias != null ? SIZE.exec(alias.trim()) : null;
      const owner = target.includes('/') ? target.slice(0, target.lastIndexOf('/')) : context.pageName;
      const file = target.slice(target.lastIndexOf('/') + 1);
      const w = { type: 'image', src: attachmentUrl(owner, file), alt: alias != null && !size ? alias : file };
      if (size) { w.width = Number(size[1]); if (size[2]) w.height = Number(size[2]); }
      widget(a, b, w);
      return;
    }
    if (embed && !attachment && wholeLine) return; // blockSpecs renders it
    const bar = inner.indexOf('|');
    if (alias != null && alias !== '') { // [[T|A]] / [[T#H|A]] → A
      hide(a, open + bar + 1); mark(open + bar + 1, close, 'cm-lp-link'); hide(close, b);
      return;
    }
    const main = bar >= 0 ? inner.slice(0, bar) : inner;
    const hash = main.indexOf('#');
    if (hash < 0) { hide(a, open); mark(open, open + main.length, 'cm-lp-link'); hide(open + main.length, b); return; }
    if (!target) { hide(a, open + hash + 1); mark(open + hash + 1, open + main.length, 'cm-lp-link'); hide(open + main.length, b); return; }
    hide(a, open);
    mark(open, open + hash, 'cm-lp-link');
    widget(open + hash, open + hash + 1, { type: 'text', text: ' > ', cls: 'cm-lp-link' });
    mark(open + hash + 1, open + main.length, 'cm-lp-link');
    hide(open + main.length, b);
  };
```
Finally, before `finalize`, drop tree constructs that cut through a claimed range (a spec that **contains** a claimed range is kept — e.g. `**bold [[P]]**`). A cut drops the whole construct (its `group`: the mark *and* its hidden markers), never half of it. Tree specs are everything pushed before the regex pass; record `const treeCount = specs.length;` right after the walk:
```js
  const cuts = (s) => claimed.some(([x, y]) => s.from < y && s.to > x && !(s.from <= x && s.to >= y));
  const cutGroups = new Set(specs.slice(0, treeCount).filter((s) => s.kind !== 'line' && cuts(s)).map((s) => s.group ?? s));
  const kept = specs.filter((s, i) => i >= treeCount || s.kind === 'line' || !cutGroups.has(s.group ?? s));
  // …then push line-class specs into `kept` and return finalize(doc, kept)
```
(`wikiSpecs` and the math/plugin pushes come after `treeCount`, so they are never filtered.)
- [ ] **Step 4: Run green.** `npx vitest run src/utils/livePreview/ranges.test.js` → PASS.
- [ ] **Step 5: Commit** — `git add wikantik-frontend/src/utils/livePreview/ranges.js wikantik-frontend/src/utils/livePreview/ranges.test.js`; subject `feat(editor): live-preview specs for wikilinks, math, plugins and block widgets` (+ trailer, Global Constraints).

---

### Task 4: Widgets and the embed adapter

**Files:**
- Create: `src/utils/livePreview/widgets.js`, `src/utils/livePreview/embedSource.js`
- Test: `src/utils/livePreview/widgets.test.js`, `src/utils/livePreview/embedSource.test.js`

**Interfaces:**
- Consumes: `editorMarkdownConfig` (tests only); `hrefFor` from `src/utils/linkInteraction.js`; `renderMath` from `src/utils/math.js`.
- Produces:
  - Classes `BulletWidget, CheckboxWidget(checked), RuleWidget, CalloutTitleWidget(style, title), ImageWidget({src, alt, width, height}), MathWidget(tex, display), TextWidget(text, cls), EmbedWidget(target, section, load)`, each with `eq`.
  - `widgetFor(data: WidgetData, context: LiveContext): WidgetType` (throws on an unknown `type`).
  - `toggleTaskAt(view, pos): boolean` — flips `[ ]`↔`[x]` of the list item whose marker starts at `pos`, as one isolated undo step.
  - `revealOnMouseDown(view, el)` — mousedown on `el` (not on a link) places the caret at the widget and focuses.
  - `embedSource.js`: `loadEmbedState(target, section): Promise<{state:'ok'|'missing'|'restricted'|'error', html?:string}>`.
- [ ] **Step 1: Locate the shared embed loader.** Run `grep -a -rln "/embed" src --include=*.js --include=*.jsx | grep -v test` and `grep -a -rn "WikiEmbed" src -l`. Open the loader module and `WikiEmbed`. Note: the exported fetch function's name and path, what it resolves with, and how `WikiEmbed` maps outcomes to loading/missing/restricted/error. `embedSource.js` must reuse **that** function (shared cache, dedupe) and the same state mapping. The code below assumes the loader resolves `{ html }` and rejects with an error carrying `.status` (as `api` request errors do); adapt the mapping to the real shape and record "Ruling: embed adapter maps <shape> — matches WikiEmbed" in the commit if it differs.
- [ ] **Step 2: Write the failing tests.**

`embedSource.test.js` (replace `'../../<loader-module>'` / `loadEmbed` with the real path/export from Step 1):
```js
import { describe, it, expect, vi, beforeEach } from 'vitest';
vi.mock('../../<loader-module>', () => ({ loadEmbed: vi.fn() }));
import { loadEmbed } from '../../<loader-module>';
import { loadEmbedState } from './embedSource';
describe('loadEmbedState', () => {
  beforeEach(() => vi.clearAllMocks());
  it('passes target and section to the shared loader and returns its html', async () => {
    loadEmbed.mockResolvedValue({ html: '<p>hi</p>' });
    await expect(loadEmbedState('Other', 'Intro')).resolves.toEqual({ state: 'ok', html: '<p>hi</p>' });
    expect(loadEmbed).toHaveBeenCalledWith('Other', 'Intro');
  });
  it.each([[404, 'missing'], [403, 'restricted'], [500, 'error']])('maps HTTP %i to %s', async (status, state) => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    loadEmbed.mockRejectedValue(Object.assign(new Error('x'), { status }));
    await expect(loadEmbedState('Other', null)).resolves.toEqual({ state });
    if (state === 'error') expect(warn).toHaveBeenCalledWith(expect.stringContaining('[live-preview]'), 'Other', null, 'x');
    warn.mockRestore();
  });
});
```
`widgets.test.js`:
```js
import { describe, it, expect, vi, afterEach } from 'vitest';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { history, undo } from '@codemirror/commands';
import {
  BulletWidget, CheckboxWidget, ImageWidget, MathWidget, EmbedWidget, TextWidget, CalloutTitleWidget, RuleWidget,
  widgetFor, toggleTaskAt,
} from './widgets';
const views = [];
function viewOf(doc) {
  const parent = document.createElement('div');
  document.body.appendChild(parent);
  const view = new EditorView({ parent, state: EditorState.create({ doc, extensions: [history()] }) });
  views.push(view);
  return view;
}
afterEach(() => { while (views.length) views.pop().destroy(); vi.restoreAllMocks(); });
const flush = () => new Promise((r) => setTimeout(r, 0));
describe('widget equality', () => {
  it('compares by rendered content only', () => {
    expect(new CheckboxWidget(true).eq(new CheckboxWidget(true))).toBe(true);
    expect(new CheckboxWidget(true).eq(new CheckboxWidget(false))).toBe(false);
    expect(new MathWidget('x', false).eq(new MathWidget('x', true))).toBe(false);
    expect(new EmbedWidget('A', null, () => {}).eq(new EmbedWidget('A', null, () => {}))).toBe(true);
    expect(new ImageWidget({ src: 'a', alt: '' }).eq(new ImageWidget({ src: 'a', alt: '', width: 3 }))).toBe(false);
  });
});
describe('widget DOM', () => {
  it('bullet, rule, text and callout title', () => {
    expect([new BulletWidget().toDOM().textContent, new RuleWidget().toDOM().className]).toEqual(['•', 'cm-lp-rule']);
    const t = new TextWidget(' > ', 'cm-lp-link').toDOM();
    expect([t.textContent, t.className]).toEqual([' > ', 'cm-lp-link']);
    const c = new CalloutTitleWidget('warning', 'Caution').toDOM();
    expect(c.querySelector('.cm-lp-callout-icon')).not.toBeNull();
    expect(c.textContent).toBe('Caution');
  });
  it('renders KaTeX, and shows the source in cm-lp-math-error for invalid TeX', () => {
    expect(new MathWidget('x^2', false).toDOM().querySelector('.katex')).not.toBeNull();
    const bad = new MathWidget('\\frac{', false).toDOM();
    expect(bad.classList.contains('cm-lp-math-error')).toBe(true);
    expect(bad.textContent).toBe('$\\frac{$');
  });
  it('image: size attributes, and a readable fallback when it fails to load', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const wrap = new ImageWidget({ src: '/attach/P/a.png', alt: 'A', width: 300 }).toDOM();
    const img = wrap.querySelector('img');
    expect([img.getAttribute('src'), img.getAttribute('width')]).toEqual(['/attach/P/a.png', '300']);
    img.dispatchEvent(new Event('error'));
    expect(wrap.classList.contains('cm-lp-image-missing')).toBe(true);
    expect(wrap.textContent).toBe('A');
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('[live-preview]'), '/attach/P/a.png');
  });
  it.each([
    [{ state: 'ok', html: '<p class="x">Body</p>' }, '.x', 'Body'],
    [{ state: 'missing' }, '.cm-lp-embed-missing', null],
    [{ state: 'restricted' }, '.cm-lp-embed-restricted', "You don't have access to this page."],
    [{ state: 'error' }, '.cm-lp-embed-error', null],
  ])('embed renders the %o state', async (result, selector, text) => {
    const view = viewOf('x');
    const dom = new EmbedWidget('Other', 'Intro', () => Promise.resolve(result)).toDOM(view);
    expect(dom.querySelector('.cm-lp-embed-title').textContent).toBe('Other › Intro');
    expect(dom.querySelector('.cm-lp-embed-loading')).not.toBeNull();
    await flush();
    expect(dom.querySelector(selector)).not.toBeNull();
    if (text) expect(dom.querySelector(selector).textContent).toBe(text);
  });
  it('embed: a rejected loader renders the error state and warns with the target', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const dom = new EmbedWidget('Other', null, () => Promise.reject(new Error('down'))).toDOM(viewOf('x'));
    await flush();
    expect(dom.querySelector('.cm-lp-embed-error')).not.toBeNull();
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('[live-preview]'), 'Other', null, 'down');
  });
  it('widgetFor maps every data type and rejects unknown ones', () => {
    for (const w of [{ type: 'bullet' }, { type: 'checkbox', checked: false }, { type: 'rule' },
      { type: 'callout-title', style: 'note', title: '' }, { type: 'image', src: 'a', alt: '' },
      { type: 'math', tex: 'x', display: true }, { type: 'text', text: '>', cls: 'c' }, { type: 'embed', target: 'A', section: null }]) {
      expect(widgetFor(w, {})).toBeTruthy();
    }
    expect(() => widgetFor({ type: 'nope' }, {})).toThrow(/nope/);
  });
});
describe('toggleTaskAt', () => {
  it('flips the box as one undo step', () => {
    const view = viewOf('- [ ] a\n* [x] b\n1. [ ] c');
    expect(toggleTaskAt(view, 0)).toBe(true);
    expect(view.state.doc.toString()).toBe('- [x] a\n* [x] b\n1. [ ] c');
    expect(toggleTaskAt(view, 8)).toBe(true);
    expect(toggleTaskAt(view, 16)).toBe(true);
    expect(view.state.doc.toString()).toBe('- [x] a\n* [ ] b\n1. [x] c');
    undo(view);
    expect(view.state.doc.toString()).toBe('- [x] a\n* [ ] b\n1. [ ] c');
  });
  it('returns false (and changes nothing) when no task marker starts at pos', () => {
    const view = viewOf('plain');
    expect(toggleTaskAt(view, 0)).toBe(false);
    expect(view.state.doc.toString()).toBe('plain');
  });
});
```
- [ ] **Step 3: Run red.** `npx vitest run src/utils/livePreview/widgets.test.js src/utils/livePreview/embedSource.test.js` → FAIL (modules missing).
- [ ] **Step 4: Implement.**

`embedSource.js`:
```js
import { loadEmbed } from '../../<loader-module>'; // the native-wikilinks shared, deduped embed cache (Step 1)
/** The shared embed cache, normalised to the four states the live-preview EmbedWidget renders. */
export async function loadEmbedState(target, section) {
  try {
    const res = await loadEmbed(target, section);
    return { state: 'ok', html: res?.html ?? '' };
  } catch (err) {
    if (err?.status === 404) return { state: 'missing' };
    if (err?.status === 403) return { state: 'restricted' };
    console.warn('[live-preview] embed fetch failed', target, section, err?.message || err);
    return { state: 'error' };
  }
}
```
`widgets.js`:
```js
import { WidgetType } from '@codemirror/view';
import { isolateHistory } from '@codemirror/commands';
import katex from 'katex';
import 'katex/dist/katex.min.css';
import { hrefFor } from '../linkInteraction';
import { renderMath } from '../math';
const el = (tag, cls, text) => {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text != null) node.textContent = text;
  return node;
};
export class BulletWidget extends WidgetType {
  eq(other) { return other instanceof BulletWidget; }
  toDOM() { const s = el('span', 'cm-lp-bullet', '•'); s.setAttribute('aria-hidden', 'true'); return s; }
  ignoreEvent() { return false; } // a click lets CodeMirror place the caret, which reveals the line
}
// Same shape (eq on constructor args, ignoreEvent false):
//   RuleWidget → <span class="cm-lp-rule"></span>;  TextWidget(text, cls) → <span class={cls}>{text}</span>;
//   CalloutTitleWidget(style, title) → <span class="cm-lp-callout-title-widget"><span class="cm-lp-callout-icon"
//     aria-hidden="true"></span>{title}</span>;
//   ImageWidget({src, alt = '', width, height}) → <span class="cm-lp-image-wrap"><img class="cm-lp-image" src alt
//     width? height?></span>; on the img 'error' event: console.warn('[live-preview] image failed to load', src), add
//     cm-lp-image-missing to the wrap and set wrap.textContent = alt || src. eq compares all four fields.
const TASK_AT = /^(?:[-*+]|\d+[.)])[ \t]+\[([ xX])\]/;
export function toggleTaskAt(view, pos) {
  const m = TASK_AT.exec(view.state.sliceDoc(pos, view.state.doc.lineAt(pos).to));
  if (!m) return false;
  const at = pos + m[0].length - 2;
  view.dispatch({ changes: { from: at, to: at + 1, insert: m[1] === ' ' ? 'x' : ' ' },
    annotations: isolateHistory.of('full'), userEvent: 'input.toggle-task' });
  return true;
}
export class CheckboxWidget extends WidgetType {
  constructor(checked) { super(); this.checked = checked; }
  eq(other) { return other instanceof CheckboxWidget && other.checked === this.checked; }
  toDOM(view) {
    const box = el('input', 'cm-lp-task');
    box.type = 'checkbox';
    box.checked = this.checked;
    box.setAttribute('aria-label', this.checked ? 'Completed task' : 'Task');
    box.addEventListener('mousedown', (e) => {
      e.preventDefault(); // keep editor focus; the document edit re-renders the box
      try { toggleTaskAt(view, view.posAtDOM(box)); } catch (err) { console.warn('[live-preview] task toggle failed', err?.message || err); }
    });
    box.addEventListener('click', (e) => e.preventDefault()); // the document, not the input, owns the state
    return box;
  }
  ignoreEvent() { return true; } // handled above
}
/** Block widgets: a mousedown (not on a link) puts the caret at the widget, which reveals its source. */
export function revealOnMouseDown(view, dom) {
  dom.addEventListener('mousedown', (e) => {
    e.preventDefault();
    const a = e.target.closest?.('a');
    if (a) { if (e.ctrlKey || e.metaKey) window.open(a.href, '_blank', 'noopener'); return; }
    try {
      view.dispatch({ selection: { anchor: view.posAtDOM(dom) } });
      view.focus();
    } catch (err) {
      console.warn('[live-preview] could not place the caret at a block widget', err?.message || err);
    }
  });
}
export class MathWidget extends WidgetType {
  constructor(tex, display) { super(); this.tex = tex; this.display = display; }
  eq(other) { return other instanceof MathWidget && other.tex === this.tex && other.display === this.display; }
  toDOM(view) {
    const node = el(this.display ? 'div' : 'span', this.display ? 'cm-lp-math cm-lp-math-block' : 'cm-lp-math');
    try {
      katex.render(this.tex, node, { displayMode: this.display, throwOnError: true });
    } catch (err) { // invalid TeX: show the source, explain on hover
      node.classList.add('cm-lp-math-error');
      node.textContent = this.display ? `$$\n${this.tex}\n$$` : `$${this.tex}$`;
      node.title = err?.message || 'Invalid TeX';
    }
    if (this.display && view) revealOnMouseDown(view, node);
    return node;
  }
  ignoreEvent() { return this.display; }
}
export class EmbedWidget extends WidgetType {
  constructor(target, section, load) { super(); this.target = target; this.section = section || null; this.load = load; }
  eq(o) { return o instanceof EmbedWidget && o.target === this.target && o.section === this.section; }
  get estimatedHeight() { return 120; }
  toDOM(view) {
    const box = el('div', 'cm-lp-embed');
    const body = el('div', 'cm-lp-embed-body cm-lp-embed-loading', 'Loading…');
    box.append(el('div', 'cm-lp-embed-title', this.section ? `${this.target} › ${this.section}` : this.target), body);
    const show = (cls, text) => { body.className = `cm-lp-embed-body ${cls}`; body.textContent = text; };
    const load = this.load ? Promise.resolve().then(() => this.load(this.target, this.section))
      : Promise.reject(new Error('no embed loader configured'));
    load.then((r) => {
      if (r?.state === 'ok') {
        body.className = 'cm-lp-embed-body article-prose';
        body.innerHTML = r.html || ''; // server-rendered, view-ACL'd HTML — exactly what the preview's WikiEmbed shows
        renderMath(body);
      } else if (r?.state === 'missing') show('cm-lp-embed-missing', `"${this.target}" does not exist yet.`);
      else if (r?.state === 'restricted') show('cm-lp-embed-restricted', "You don't have access to this page.");
      else show('cm-lp-embed-error', 'Could not load this embed.');
    }).catch((err) => {
      console.warn('[live-preview] embed failed', this.target, this.section, err?.message || err);
      show('cm-lp-embed-error', 'Could not load this embed.');
    });
    box.addEventListener('mousedown', (e) => { // Ctrl/Cmd-click opens the embedded page, like a link
      if (!(e.ctrlKey || e.metaKey) || e.target.closest?.('a')) return;
      e.preventDefault();
      e.stopImmediatePropagation();
      const href = hrefFor(this.section ? `${this.target}#${this.section}` : this.target);
      if (href) window.open(href, '_blank', 'noopener');
    }, true);
    revealOnMouseDown(view, box);
    return box;
  }
  ignoreEvent() { return true; } // the widget handles its own mouse events
}
export function widgetFor(w, context = {}) {
  switch (w.type) {
    case 'bullet': return new BulletWidget();
    case 'checkbox': return new CheckboxWidget(w.checked);
    case 'rule': return new RuleWidget();
    case 'callout-title': return new CalloutTitleWidget(w.style, w.title);
    case 'image': return new ImageWidget(w);
    case 'math': return new MathWidget(w.tex, !!w.display);
    case 'text': return new TextWidget(w.text, w.cls);
    case 'embed': return new EmbedWidget(w.target, w.section, context.loadEmbed);
    default: throw new Error(`unknown live-preview widget type: ${w.type}`);
  }
}
```
In the embed-state test the loader resolves synchronously-ish; `await flush()` (one macrotask) settles the two chained microtasks. If `renderMath` throws in happy-dom for some HTML, it is inside `.then` → caught → error state + warn; fix the HTML, not the catch.
- [ ] **Step 5: Run green.** `npx vitest run src/utils/livePreview/widgets.test.js src/utils/livePreview/embedSource.test.js` → PASS.
- [ ] **Step 6: Commit** — `git add wikantik-frontend/src/utils/livePreview/widgets.js wikantik-frontend/src/utils/livePreview/widgets.test.js wikantik-frontend/src/utils/livePreview/embedSource.js wikantik-frontend/src/utils/livePreview/embedSource.test.js`; subject `feat(editor): live-preview widgets (bullets, tasks, math, images, embeds)` (+ trailer, Global Constraints).

---

### Task 5: The live-preview extension (mode field, ViewPlugin, block StateField)

**Files:**
- Create: `src/utils/livePreview/plugin.js`, `src/utils/livePreview/index.js`
- Test: `src/utils/livePreview/plugin.test.js`

**Interfaces:**
- Consumes: `activeLinesOf`, `livePreviewSpecs`, `blockSpecs` (Tasks 2–3); `widgetFor` (Task 4); `editorMarkdownConfig` (Task 1, tests).
- Produces (all module-level singletons — required so they survive react-codemirror reconfigures):
  - `setLiveMode: StateEffectType<boolean>`, `refreshLivePreview: StateEffectType<null>`, `liveModeField: StateField<boolean>` (default `false`).
  - `livePreviewConfig: Facet<{getContext: () => LiveContext}>`.
  - `livePreviewPlugin` (ViewPlugin; instance has `.decorations`), `blockField` (StateField<DecorationSet>), `buildInline(view): DecorationSet`.
  - `index.js`: `livePreview({ getContext } = {}): Extension`, `setLivePreview(view, on: boolean): void` (dispatches only when the value differs), re-exports `setLiveMode, refreshLivePreview, liveModeField`.
  - The editor root gets class `cm-live-preview` while live.
- [ ] **Step 1: Write the failing tests** (`plugin.test.js`):
```js
import { describe, it, expect, vi, afterEach } from 'vitest';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { history, undo } from '@codemirror/commands';
import { fireEvent } from '@testing-library/react';
import { editorMarkdownConfig } from '../editorMarkdown';
import { livePreview, setLivePreview } from './index';
import { livePreviewPlugin } from './plugin';
const views = [];
function liveView(doc, { context = {}, live = true, caret = doc.length } = {}) {
  const parent = document.createElement('div');
  document.body.appendChild(parent);
  const view = new EditorView({
    parent,
    state: EditorState.create({ doc, extensions: [markdown(editorMarkdownConfig), history(), livePreview({ getContext: () => context })] }),
  });
  ensureSyntaxTree(view.state, view.state.doc.length, 5000);
  view.dispatch({ selection: { anchor: caret } });
  if (live) setLivePreview(view, true);
  views.push(view);
  return view;
}
afterEach(() => { while (views.length) views.pop().destroy(); vi.restoreAllMocks(); });
const lineText = (view, n) => view.contentDOM.querySelectorAll('.cm-line')[n - 1].textContent;
describe('live preview extension', () => {
  it('is off until enabled, and toggling never changes the document', () => {
    const view = liveView('# T\n\n**b**\n', { live: false });
    expect(view.dom.classList.contains('cm-live-preview')).toBe(false);
    expect(lineText(view, 1)).toBe('# T');
    setLivePreview(view, true);
    expect(view.dom.classList.contains('cm-live-preview')).toBe(true);
    expect(lineText(view, 1)).toBe('T');
    expect(view.contentDOM.querySelector('.cm-line.cm-lp-h1')).not.toBeNull();
    setLivePreview(view, false);
    expect(lineText(view, 3)).toBe('**b**');
    expect(view.state.doc.toString()).toBe('# T\n\n**b**\n');
  });
  it('moving the caret moves the revealed line', () => {
    const view = liveView('**a**\n\n**b**\n');
    expect([lineText(view, 1), lineText(view, 3)]).toEqual(['a', 'b']);
    view.dispatch({ selection: { anchor: 8 } });
    expect([lineText(view, 1), lineText(view, 3)]).toEqual(['a', '**b**']);
  });
  it('renders block math from the StateField and reveals it when the caret enters', () => {
    const view = liveView('$$\nx^2\n$$\n\nend');
    expect(view.dom.querySelector('.cm-lp-math-block .katex')).not.toBeNull();
    view.dispatch({ selection: { anchor: 4 } });
    expect(view.dom.querySelector('.cm-lp-math-block')).toBeNull();
  });
  it('a mousedown on a block widget places the caret in it, revealing the source', () => {
    const view = liveView('$$\nx^2\n$$\n\nend');
    fireEvent.mouseDown(view.dom.querySelector('.cm-lp-math-block'));
    expect(view.state.selection.main.head).toBe(0);
    expect(view.dom.querySelector('.cm-lp-math-block')).toBeNull();
  });
  it('renders a page embed through the injected loader', async () => {
    const loadEmbed = vi.fn(() => Promise.resolve({ state: 'ok', html: '<p class="emb">Hi</p>' }));
    const view = liveView('![[Other#Intro]]\n\nend', { context: { loadEmbed } });
    await new Promise((r) => setTimeout(r, 0));
    expect(loadEmbed).toHaveBeenCalledWith('Other', 'Intro');
    expect(view.dom.querySelector('.cm-lp-embed .emb').textContent).toBe('Hi');
  });
  it('a checkbox click toggles the task in the document, undoable in one step', () => {
    const view = liveView('- [ ] task\n\nend');
    fireEvent.mouseDown(view.dom.querySelector('input.cm-lp-task'));
    expect(view.state.doc.toString()).toBe('- [x] task\n\nend');
    expect(view.dom.querySelector('input.cm-lp-task').checked).toBe(true);
    undo(view);
    expect(view.state.doc.toString()).toBe('- [ ] task\n\nend');
  });
  it('is bounded to the visible ranges on a 3,000-line page', () => {
    const doc = Array.from({ length: 3000 }, (_, i) => `- item **${i}**`).join('\n');
    const view = liveView(doc, { caret: 0 });
    const rendered = view.contentDOM.querySelectorAll('.cm-line').length;
    expect(rendered).toBeLessThan(200); // happy-dom renders ~35 lines of this doc
    expect(view.contentDOM.querySelectorAll('.cm-lp-bullet').length).toBeLessThanOrEqual(rendered);
    expect(view.plugin(livePreviewPlugin).decorations.size).toBeLessThan(rendered * 6);
    const end = view.state.doc.length;
    view.dispatch({ changes: { from: end, insert: '!' }, selection: { anchor: end + 1 }, userEvent: 'input.type' });
    expect(view.plugin(livePreviewPlugin).decorations.size).toBeLessThan(rendered * 6);
  });
  it('an external edit that leaves the caret inside a hidden marker reveals the line', () => {
    const view = liveView('x\n\nsee **bold** now\n', { caret: 0 });
    expect(lineText(view, 3)).toBe('see bold now');
    // a background rewrite (rename/convert) that lands the caret inside the hidden "**"
    expect(() => view.dispatch({ changes: { from: 0, to: 1, insert: 'y' }, selection: { anchor: 8 } })).not.toThrow();
    expect(lineText(view, 3)).toBe('see **bold** now');
  });
  it('typing at the end of a decorated line keeps the raw source on that line', () => {
    const view = liveView('**bold**\n\nend', { caret: 8 });
    view.dispatch({ changes: { from: 8, insert: 'x' }, selection: { anchor: 9 }, userEvent: 'input.type' });
    expect(lineText(view, 1)).toBe('**bold**x');
    expect(view.state.doc.toString()).toBe('**bold**x\n\nend');
  });
  it('a decoration-builder failure falls back to plain source and warns', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const parent = document.createElement('div');
    document.body.appendChild(parent);
    const broken = new EditorView({
      parent,
      state: EditorState.create({ doc: '**a**\n\nend', extensions: [markdown(editorMarkdownConfig), livePreview({ getContext: () => { throw new Error('boom'); } })] }),
    });
    views.push(broken);
    broken.dispatch({ selection: { anchor: 9 } });
    setLivePreview(broken, true);
    expect(broken.contentDOM.querySelector('.cm-line').textContent).toBe('**a**');
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('[live-preview]'), 'boom');
  });
});
```
- [ ] **Step 2: Run red.** `npx vitest run src/utils/livePreview/plugin.test.js` → FAIL (modules missing).
- [ ] **Step 3: Implement `plugin.js`.**
```js
import { StateEffect, StateField, Facet } from '@codemirror/state';
import { ViewPlugin, Decoration, EditorView } from '@codemirror/view';
import { syntaxTree } from '@codemirror/language';
import { activeLinesOf, livePreviewSpecs, blockSpecs } from './ranges';
import { widgetFor } from './widgets';
export const setLiveMode = StateEffect.define();
export const refreshLivePreview = StateEffect.define();
/** Live mode on/off. A field (not a Compartment): its value survives react-codemirror's full reconfigures. */
export const liveModeField = StateField.define({
  create: () => false,
  update: (value, tr) => tr.effects.reduce((v, e) => (e.is(setLiveMode) ? !!e.value : v), value),
});
export const livePreviewConfig = Facet.define({ combine: (values) => values[0] ?? { getContext: () => ({}) } });
const hidden = Decoration.replace({});
const lineDecos = new Map();
const markDecos = new Map();
const cached = (map, cls, make) => { if (!map.has(cls)) map.set(cls, make(cls)); return map.get(cls); };
function toDecorations(specs, context) {
  const ranges = [];
  for (const s of specs) {
    if (s.kind === 'line') ranges.push(cached(lineDecos, s.cls, (c) => Decoration.line({ class: c })).range(s.from));
    else if (s.kind === 'mark') ranges.push(cached(markDecos, s.cls, (c) => Decoration.mark({ class: c })).range(s.from, s.to));
    else if (s.kind === 'hide') ranges.push(hidden.range(s.from, s.to));
    else if (s.kind === 'widget') ranges.push(Decoration.replace({ widget: widgetFor(s.widget, context) }).range(s.from, s.to));
    else if (s.kind === 'block') ranges.push(Decoration.replace({ widget: widgetFor(s.widget, context), block: true }).range(s.from, s.to));
  }
  // sort=true: specs come from several passes; Decoration.set orders by (from, startSide) for us. (A RangeSetBuilder
  // would require adding ranges pre-sorted by from AND startSide — line decorations sort before marks at one position.)
  return Decoration.set(ranges, true);
}
const getContext = (state) => state.facet(livePreviewConfig).getContext() || {};
const touchesMode = (tr) => tr.effects.some((e) => e.is(setLiveMode) || e.is(refreshLivePreview));
const treeChanged = (a, b) => syntaxTree(a) !== syntaxTree(b);
export function buildInline(view) {
  const { state } = view;
  if (!state.field(liveModeField, false)) return Decoration.none;
  try {
    const context = getContext(state);
    const active = activeLinesOf(state);
    const seen = new Set();
    const specs = [];
    for (const { from, to } of view.visibleRanges) {
      for (const s of livePreviewSpecs(state, active, { from, to, context })) {
        const key = `${s.kind}:${s.from}:${s.to}:${s.cls || s.widget?.type || ''}`;
        if (!seen.has(key)) { seen.add(key); specs.push(s); }
      }
    }
    return toDecorations(specs, context);
  } catch (err) {
    console.warn('[live-preview] could not build decorations; showing source for this update', err?.message || err);
    return Decoration.none;
  }
}
function buildBlocks(state) {
  if (!state.field(liveModeField, false)) return Decoration.none;
  try {
    const context = getContext(state);
    return toDecorations(blockSpecs(state, activeLinesOf(state), context), context);
  } catch (err) {
    console.warn('[live-preview] could not build block widgets; showing source for this update', err?.message || err);
    return Decoration.none;
  }
}
/** Block widgets (display math, page embeds) must come from a StateField: plugins may not provide block decorations. */
export const blockField = StateField.define({
  create: buildBlocks,
  update(decos, tr) {
    // Not on tr.reconfigured: react-codemirror reconfigures on every CodeEditor render; context changes arrive
    // as refreshLivePreview instead.
    const relevant = tr.docChanged || tr.selection || touchesMode(tr) || treeChanged(tr.startState, tr.state);
    return relevant ? buildBlocks(tr.state) : decos;
  },
  provide: (f) => EditorView.decorations.from(f),
});
export const livePreviewPlugin = ViewPlugin.fromClass(class {
  constructor(view) { this.decorations = buildInline(view); }
  update(u) {
    const relevant = u.docChanged || u.viewportChanged || u.selectionSet || treeChanged(u.startState, u.state) || u.transactions.some(touchesMode);
    if (relevant) this.decorations = buildInline(u.view);
  }
}, { decorations: (v) => v.decorations });
export const liveAttributes = EditorView.editorAttributes.compute([liveModeField],
  (state) => (state.field(liveModeField) ? { class: 'cm-live-preview' } : {}));
```
`index.js`:
```js
import { livePreviewConfig, liveModeField, setLiveMode, blockField, livePreviewPlugin, liveAttributes } from './plugin';
export { setLiveMode, refreshLivePreview, liveModeField } from './plugin';
/** The live-preview extension. Inactive until setLivePreview(view, true). getContext() → LiveContext. */
export function livePreview({ getContext = () => ({}) } = {}) {
  return [liveModeField, livePreviewConfig.of({ getContext }), blockField, livePreviewPlugin, liveAttributes];
}
/** Turn live mode on/off through a view transaction (effects only: no document change, no history entry). */
export function setLivePreview(view, on) {
  if (view.state.field(liveModeField, false) === undefined) return;
  if (view.state.field(liveModeField) !== !!on) view.dispatch({ effects: setLiveMode.of(!!on) });
}
```
Note on the error test: `getContext` throws inside both builders, so both log and return `Decoration.none`; the line renders `**a**`. The `blockField` warning and the inline warning both match `expect.stringContaining('[live-preview]'), 'boom'`.
- [ ] **Step 4: Run green.** `npx vitest run src/utils/livePreview` → PASS (all live-preview suites).
- [ ] **Step 5: Commit** — `git add wikantik-frontend/src/utils/livePreview/plugin.js wikantik-frontend/src/utils/livePreview/index.js wikantik-frontend/src/utils/livePreview/plugin.test.js`; subject `feat(editor): live-preview CodeMirror extension (visible-range plugin + block field)` (+ trailer, Global Constraints).

---

### Task 6: Live-preview styles in the editor theme

**Files:**
- Modify: `src/utils/editorTheme.js` (add rules to `editorChromeSpec`, before `...iconRules()`; per-style tints inside `iconRules()`'s callout loop)
- Modify: `src/utils/editorTheme.test.js` (`THEME_INDEPENDENT`, new test)

**Interfaces:**
- Consumes: class names emitted by Tasks 2–4 (listed in the test below — they are the contract).
- Produces: `editorChromeSpec` rules for every `cm-lp-*` class; nothing else changes.
- [ ] **Step 1: Write the failing test** (append to `editorTheme.test.js`; also change the set to `new Set(['--font-ui', '--font-mono', '--font-display', '--radius-sm', '--radius-md'])`):
```js
it('styles every live-preview class', () => {
  const classes = ['cm-lp-h1', 'cm-lp-h2', 'cm-lp-h3', 'cm-lp-h4', 'cm-lp-h5', 'cm-lp-h6', 'cm-lp-em', 'cm-lp-strong',
    'cm-lp-del', 'cm-lp-code', 'cm-lp-link', 'cm-lp-quote', 'cm-lp-callout', 'cm-lp-callout-title',
    'cm-lp-callout-title-widget', 'cm-lp-callout-icon', 'cm-lp-bullet', 'cm-lp-task', 'cm-lp-rule', 'cm-lp-image',
    'cm-lp-image-missing', 'cm-lp-math-block', 'cm-lp-math-error', 'cm-lp-fence', 'cm-lp-codeblock', 'cm-lp-plugin',
    'cm-lp-embed', 'cm-lp-embed-title', 'cm-lp-embed-loading', 'cm-lp-embed-missing', 'cm-lp-embed-restricted',
    'cm-lp-embed-error'];
  const keys = Object.keys(editorChromeSpec);
  expect(classes.filter((c) => !keys.some((k) => k.split(/[\s,:]/).includes(`.${c}`)))).toEqual([]);
  for (const style of CALLOUT_STYLES) {
    expect(editorChromeSpec[`&.cm-editor .cm-lp-callout-${style}`]).toEqual({ '--cm-callout-tint': `var(--callout-${style})` });
  }
  expect(editorChromeSpec['&.cm-editor .cm-lp-h1'].fontFamily).toBe('var(--font-display)');
});
```
- [ ] **Step 2: Run red.** `npx vitest run src/utils/editorTheme.test.js` → the new test FAILS.
- [ ] **Step 3: Implement.** In `iconRules()`'s `for (const style of CALLOUT_STYLES)` loop add `rules[`${ROOT} .cm-lp-callout-${style}`] = { '--cm-callout-tint': `var(--callout-${style})` };`. Add a `// ── Live preview ──` section to `editorChromeSpec`:
```js
  // ── Live preview ─────────────────────────────────────────────────────────
  ...Object.fromEntries([['1', '1.6em'], ['2', '1.38em'], ['3', '1.2em'], ['4', '1.08em'], ['5', '1em'], ['6', '0.95em']]
    .map(([n, size]) => [`${ROOT} .cm-lp-h${n}`, {
      fontFamily: 'var(--font-display)', fontSize: size, fontWeight: '700', lineHeight: '1.35',
      ...(n === '6' ? { color: 'var(--text-secondary)' } : {}),
    }])),
  [`${ROOT} .cm-lp-strong`]: { fontWeight: '700' },
  [`${ROOT} .cm-lp-em`]: { fontStyle: 'italic' },
  [`${ROOT} .cm-lp-del`]: { textDecoration: 'line-through', color: 'var(--text-secondary)' },
  [`${ROOT} .cm-lp-code`]: { fontFamily: 'var(--font-mono)', backgroundColor: 'var(--code-bg)', borderRadius: 'var(--radius-sm)', padding: '0 3px' },
  [`${ROOT} .cm-lp-link`]: { color: 'var(--accent)', textDecoration: 'underline', textDecorationColor: 'color-mix(in srgb, var(--accent) 45%, transparent)' },
  [`${ROOT} .cm-lp-quote`]: { borderLeft: '3px solid var(--border)', paddingLeft: '0.75em', color: 'var(--text-secondary)' },
  [`${ROOT} .cm-lp-callout`]: { '--cm-callout-tint': 'var(--callout-note)', borderLeft: '3px solid var(--cm-callout-tint)', paddingLeft: '0.75em', backgroundColor: 'color-mix(in srgb, var(--cm-callout-tint) 8%, transparent)' },
  [`${ROOT} .cm-lp-callout-title`]: { fontWeight: '600', color: 'var(--cm-callout-tint)' },
  [`${ROOT} .cm-lp-callout-title-widget`]: { display: 'inline-flex', alignItems: 'center', gap: '0.35em', fontWeight: '600', color: 'var(--cm-callout-tint)', marginRight: '0.35em' },
  [`${ROOT} .cm-lp-callout-icon`]: { display: 'inline-block', width: '1em', height: '1em', backgroundColor: 'currentColor',
    WebkitMask: `${svgMask(MASK_ICONS['slash-callout'])} center / contain no-repeat`, mask: `${svgMask(MASK_ICONS['slash-callout'])} center / contain no-repeat` },
  [`${ROOT} .cm-lp-bullet`]: { color: 'var(--text-secondary)', fontWeight: '700' },
  [`${ROOT} .cm-lp-task`]: { margin: '0 0.4em 0 0', verticalAlign: 'middle', accentColor: 'var(--accent)', cursor: 'pointer' },
  [`${ROOT} .cm-lp-rule`]: { display: 'inline-block', width: '100%', verticalAlign: 'middle', borderTop: '1px solid var(--border)' },
  [`${ROOT} .cm-lp-image`]: { maxWidth: '100%', verticalAlign: 'middle', borderRadius: 'var(--radius-sm)' },
  [`${ROOT} .cm-lp-image-missing`]: { color: 'var(--text-secondary)', fontStyle: 'italic' },
  [`${ROOT} .cm-lp-math-block`]: { display: 'block', textAlign: 'center', padding: '0.4em 0', cursor: 'text' },
  [`${ROOT} .cm-lp-math-error`]: { color: 'var(--callout-danger)', fontFamily: 'var(--font-mono)' },
  [`${ROOT} .cm-lp-fence`]: { color: 'var(--text-secondary)', backgroundColor: 'var(--code-bg)' },
  [`${ROOT} .cm-lp-codeblock`]: { backgroundColor: 'var(--code-bg)' },
  [`${ROOT} .cm-lp-plugin`]: { padding: '0 6px', borderRadius: '999px', color: 'var(--text-secondary)', backgroundColor: 'color-mix(in srgb, var(--text) 7%, transparent)', fontFamily: 'var(--font-ui)', fontSize: '0.85em' },
  [`${ROOT} .cm-lp-embed`]: { margin: '0.4em 0', padding: '0.5em 0.75em', border: '1px solid var(--border)', borderLeft: '3px solid var(--accent)', borderRadius: 'var(--radius-md)', backgroundColor: 'var(--bg-elevated)', cursor: 'text' },
  [`${ROOT} .cm-lp-embed-title`]: { fontFamily: 'var(--font-ui)', fontSize: '0.8em', fontWeight: '600', color: 'var(--text-secondary)', marginBottom: '0.3em' },
  ...Object.fromEntries(['loading', 'missing', 'restricted', 'error'].map((s) => [`${ROOT} .cm-lp-embed-${s}`, { color: 'var(--text-secondary)', fontStyle: 'italic' }])),
```
`--callout-danger` is declared in both themes (globals.css ~94/~101), so the token guard accepts it.
- [ ] **Step 4: Run green.** `npx vitest run src/utils/editorTheme.test.js src/styles/tokens.test.js` → PASS.
- [ ] **Step 5: Commit** — `git add wikantik-frontend/src/utils/editorTheme.js wikantik-frontend/src/utils/editorTheme.test.js`; subject `feat(editor): live-preview styles in the editor theme tokens` (+ trailer, Global Constraints).

---

### Task 7: CodeEditor — `livePreview` and `livePreviewContext` props

**Files:**
- Modify: `src/components/CodeEditor.jsx` (props destructure ~line 95; refs ~100-115; `handleCreateEditor` ~133; extensions memo ~403; header doc comment)
- Test: `src/components/CodeEditor.realCodeMirror.test.jsx` (append)

**Interfaces:**
- Consumes: `livePreview, setLivePreview, refreshLivePreview, liveModeField` from `../utils/livePreview` (Task 5).
- Produces: `<CodeEditor livePreview={boolean} livePreviewContext={LiveContext} … />`. Toggling never recreates the view, never changes the document, keeps history/selection/folds, and survives later re-renders (react-codemirror reconfigures).
- [ ] **Step 1: Write the failing tests** (append; imports: `undo` and `foldEffect, foldedRanges` are partly imported already — add `foldEffect` from `@codemirror/language`, `linkAt` from `../utils/linkInteraction`):
```js
describe('CodeEditor live preview', () => {
  const liveMount = (value, props = {}) => {
    const ref = createRef();
    const utils = render(<CodeEditor ref={ref} value={value} onChange={vi.fn()} {...props} />);
    const view = EditorView.findFromDOM(utils.container.querySelector('.cm-editor'));
    return { ref, view, ...utils };
  };
  const firstLine = (view) => view.contentDOM.querySelector('.cm-line').textContent;
  it('starts live when mounted with livePreview, and the toggle keeps the same view, doc and selection', () => {
    const { view, rerender } = liveMount('**a**\n\nend', { livePreview: true });
    act(() => { view.dispatch({ selection: { anchor: 9 } }); });
    expect(firstLine(view)).toBe('a');
    rerender(<CodeEditor value={'**a**\n\nend'} onChange={vi.fn()} livePreview={false} />);
    expect(EditorView.findFromDOM(document.querySelector('.cm-editor'))).toBe(view);
    expect(firstLine(view)).toBe('**a**');
    expect(view.state.doc.toString()).toBe('**a**\n\nend');
    expect(view.state.selection.main.head).toBe(9);
  });
  it('stays live across a re-render that reconfigures the editor (theme change)', () => {
    const { view, rerender } = liveMount('**a**\n\nend', { livePreview: true });
    act(() => { view.dispatch({ selection: { anchor: 9 } }); });
    rerender(<CodeEditor value={'**a**\n\nend'} onChange={vi.fn()} livePreview dark />);
    expect(view.dom.classList.contains('cm-live-preview')).toBe(true);
    expect(firstLine(view)).toBe('a');
  });
  it('undo across a live toggle restores the text and keeps the mode', () => {
    const { view, rerender } = liveMount('hello\n\nend');
    act(() => { view.dispatch({ changes: { from: 5, insert: ' world' }, selection: { anchor: 11 }, userEvent: 'input.type' }); });
    rerender(<CodeEditor value={'hello world\n\nend'} onChange={vi.fn()} livePreview />);
    act(() => { undo(view); });
    expect(view.state.doc.toString()).toBe('hello\n\nend');
    expect(view.dom.classList.contains('cm-live-preview')).toBe(true);
  });
  it('keeps folds through a toggle', () => {
    const { view, rerender } = liveMount('# A\none\ntwo\n# B\nthree');
    act(() => { view.dispatch({ effects: foldEffect.of({ from: 3, to: 11 }) }); });
    rerender(<CodeEditor value={'# A\none\ntwo\n# B\nthree'} onChange={vi.fn()} livePreview />);
    expect(foldedRanges(view.state).size).toBe(1);
  });
  it('Ctrl-hover link marks still wrap the visible link text in live mode', () => {
    const { view } = liveMount('see [the hub](IndexFundsHub) now\n\nend', { livePreview: true });
    act(() => { view.dispatch({ selection: { anchor: view.state.doc.length } }); });
    expect([...view.contentDOM.querySelectorAll('.cm-link-range')].map((e) => e.textContent).join('')).toBe('the hub');
    expect(linkAt(view.state, 6).url).toBe('IndexFundsHub');
  });
  it('a new livePreviewContext refreshes widgets (attachment list arrives late)', () => {
    const doc = '![a](pic.png)\n\nend';
    const { view, rerender } = liveMount(doc, { livePreview: true, livePreviewContext: { pageName: 'P', attachments: [] } });
    act(() => { view.dispatch({ selection: { anchor: doc.length } }); });
    expect(view.dom.querySelector('img.cm-lp-image').getAttribute('src')).toBe('pic.png');
    rerender(<CodeEditor value={doc} onChange={vi.fn()} livePreview livePreviewContext={{ pageName: 'P', attachments: ['pic.png'] }} />);
    expect(view.dom.querySelector('img.cm-lp-image').getAttribute('src')).toBe('/attach/P/pic.png');
  });
});
```
- [ ] **Step 2: Run red.** `npx vitest run src/components/CodeEditor.realCodeMirror.test.jsx` → new tests FAIL (`livePreview` prop ignored).
- [ ] **Step 3: Implement** in `CodeEditor.jsx`:
```js
import { useEffect } from 'react'; // add to the existing react import
import { livePreview as livePreviewExtension, setLivePreview, refreshLivePreview, liveModeField } from '../utils/livePreview';
// props: add `livePreview = false, livePreviewContext,`
  // Live preview (decorations only). The context (page name, attachment names, embed loader) is read through a ref
  // so the extension is built once; the mode itself lives in an editor StateField that survives reconfigures.
  const livePreviewContextRef = useRef(livePreviewContext);
  livePreviewContextRef.current = livePreviewContext;
  const livePreviewRef = useRef(livePreview);
  livePreviewRef.current = livePreview;
  const liveExtension = useMemo(
    () => livePreviewExtension({ getContext: () => livePreviewContextRef.current || {} }),
    [],
  );
  const handleCreateEditor = useCallback((view) => {
    viewRef.current = view;
    if (livePreviewRef.current) setLivePreview(view, true);
  }, []);
  useEffect(() => {
    const view = viewRef.current;
    if (view) setLivePreview(view, !!livePreview);
  }, [livePreview]);
  useEffect(() => {
    const view = viewRef.current;
    if (view && view.state.field(liveModeField, false)) view.dispatch({ effects: refreshLivePreview.of(null) });
  }, [livePreviewContext]);
```
Add `liveExtension` to the extensions memo array (after `calloutMarkers`, before `editorChrome`) and to its deps. Update the header comment's Props list with `livePreview` and `livePreviewContext`. If the react-codemirror view does not exist yet when the `[livePreview]` effect first runs, `handleCreateEditor` covers the initial mode — both paths are idempotent (`setLivePreview` dispatches only on a difference).
- [ ] **Step 4: Run green.** `npx vitest run src/components/CodeEditor.realCodeMirror.test.jsx src/components/CodeEditor.test.jsx` → PASS.
- [ ] **Step 5: Commit** — `git add wikantik-frontend/src/components/CodeEditor.jsx wikantik-frontend/src/components/CodeEditor.realCodeMirror.test.jsx`; subject `feat(editor): CodeEditor livePreview prop backed by an editor state field` (+ trailer, Global Constraints).

---

### Task 8: Mode toggle in the page editor (hook, toolbar, command, Mod-e, wiring)

**Files:**
- Create: `src/hooks/useEditorMode.js`, `src/hooks/useEditorMode.test.js`
- Create: `src/components/PageEditor.livePreview.realCodeMirror.test.jsx`
- Modify: `src/components/EditorToolbar.jsx` (+ `EditorToolbar.test.jsx`: button count 8 → 9), `src/utils/editorCommands.js` (+ `editorCommands.test.js`), `src/components/PageEditor.jsx` (~52 imports, ~89 hooks, ~486 keydown handler, ~549 commands, ~937 toolbar, ~955 `<CodeEditor>`), `src/styles/globals.css` (after `.editor-format-btn:hover` ~1047), `src/components/PageEditor.interleaving.realCodeMirror.test.jsx`

**Interfaces:**
- Consumes: `CodeEditor` props from Task 7; `loadEmbedState` from `src/utils/livePreview/embedSource.js` (Task 4).
- Produces:
  - `useEditorMode(): ['source'|'live', () => void]` — key `wikantik.editor.mode`, default `'source'`.
  - `EditorToolbar` prop `liveMode: boolean`; button id `toggle-live-preview`, text `Live`, `aria-pressed`.
  - `buildEditorCommands({ …, toggleLivePreview })` adds `{ id: 'toggle-live-preview', title: 'Toggle live preview', section: 'View', keys: 'Mod-E' }`.
- [ ] **Step 1: Write the failing tests.**

`useEditorMode.test.js`:
```js
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useEditorMode } from './useEditorMode';
describe('useEditorMode', () => {
  beforeEach(() => { localStorage.clear(); vi.restoreAllMocks(); });
  it('defaults to source and persists the toggle', () => {
    const { result } = renderHook(() => useEditorMode());
    expect(result.current[0]).toBe('source');
    act(() => result.current[1]());
    expect(result.current[0]).toBe('live');
    expect(localStorage.getItem('wikantik.editor.mode')).toBe('live');
    expect(renderHook(() => useEditorMode()).result.current[0]).toBe('live');
  });
  it('ignores a garbage stored value', () => {
    localStorage.setItem('wikantik.editor.mode', 'wysiwyg'); expect(renderHook(() => useEditorMode()).result.current[0]).toBe('source');
  });
  it('still works (and warns) when storage throws', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied'); });
    const { result } = renderHook(() => useEditorMode());
    act(() => result.current[1]());
    expect(result.current[0]).toBe('live');
    expect(warn).toHaveBeenCalledTimes(2);
  });
});
```
`EditorToolbar.test.jsx` — change `toBe(8)` to `toBe(9)` and add:
```js
it('the Live toggle reflects the mode and runs toggle-live-preview', () => {
  const onRun = vi.fn();
  const { rerender } = render(<EditorToolbar onRun={onRun} liveMode={false} />);
  const live = screen.getByRole('button', { name: /live preview/i });
  expect(live).toHaveAttribute('aria-pressed', 'false');
  expect(live.textContent).toBe('Live');
  fireEvent.mouseDown(live);
  expect(onRun).toHaveBeenCalledWith('toggle-live-preview');
  rerender(<EditorToolbar onRun={onRun} liveMode />);
  expect(screen.getByRole('button', { name: /live preview/i })).toHaveAttribute('aria-pressed', 'true');
});
```
`editorCommands.test.js` — add `'toggle-live-preview'` to the expected id list (line ~14) and assert `cmds.find((c) => c.id === 'toggle-live-preview')` has `{ title: 'Toggle live preview', section: 'View', keys: 'Mod-E' }` and its `run` calls the injected `toggleLivePreview`.

`PageEditor.livePreview.realCodeMirror.test.jsx` — copy the `vi.mock(...)` block, imports and `beforeEach` from `PageEditor.interleaving.realCodeMirror.test.jsx` (lines 1-110 pattern; `INITIAL = '# Title\n\n- [ ] task **b**\n\nend'`; `useAttachments` returns `list: []`; `api.savePage.mockRejectedValue(new Error('probe'))`), then:
```js
import { fireEvent, screen } from '@testing-library/react';
import { mountRealEditor, saveAndExpectVisibleText, placeCaret, docOf, until, flush } from '../test/realEditorHarness';
const isLive = () => document.querySelector('.cm-editor.cm-live-preview') !== null;
it('the toolbar button toggles live mode and remembers it', async () => {
  const view = await mountRealEditor(PageEditor, NavigationGuardProvider, INITIAL);
  expect(isLive()).toBe(false);
  fireEvent.mouseDown(screen.getByRole('button', { name: /live preview/i }));
  await until(isLive);
  expect(localStorage.getItem('wikantik.editor.mode')).toBe('live');
  expect(docOf(view)).toBe(INITIAL);
});
it('Mod-e toggles live mode from anywhere on the page', async () => {
  await mountRealEditor(PageEditor, NavigationGuardProvider, INITIAL);
  fireEvent.keyDown(window, { key: 'e', code: 'KeyE', ctrlKey: true });
  await until(isLive);
  fireEvent.keyDown(window, { key: 'e', code: 'KeyE', ctrlKey: true });
  await until(() => !isLive());
});
it('saving in live mode sends exactly the source text, including a checkbox toggle', async () => {
  localStorage.setItem('wikantik.editor.mode', 'live');
  const view = await mountRealEditor(PageEditor, NavigationGuardProvider, INITIAL);
  placeCaret(view, INITIAL.length);
  await until(() => document.querySelector('input.cm-lp-task') !== null);
  saveAndExpectVisibleText(api, view);
  expect(api.savePage.mock.calls[0][1].content).toContain('- [ ] task **b**');
  fireEvent.mouseDown(document.querySelector('input.cm-lp-task'));
  expect(docOf(view)).toBe('# Title\n\n- [x] task **b**\n\nend');
  await flush(); // let the rejected first probe settle so the next Ctrl+S is not ignored
  const payload = saveAndExpectVisibleText(api, view);
  expect(payload.content).toContain('- [x] task **b**');
});
```
(`saveAndExpectVisibleText` asserts the payload equals `view.state.doc.toString()` — i.e. live decorations never leak into the saved text.)

Interleaving test (`PageEditor.interleaving.realCodeMirror.test.jsx`) — run every third seed in live mode:
```js
// stats: add  liveSeeds: 0, liveWidgetSeeds: 0
// afterAll floors (inside the full-run guard):
expect(stats.liveSeeds).toBeGreaterThanOrEqual(15);
expect(stats.liveWidgetSeeds).toBeGreaterThanOrEqual(15); // live seeds really typed around rendered widgets
// at the top of the it.each body, BEFORE mountRealEditor:
const live = SEEDS.indexOf(seed) % 3 === 0;
if (live) localStorage.setItem('wikantik.editor.mode', 'live');
// right after placeCaret(view, INITIAL.length):
if (live) {
  expect(document.querySelector('.cm-editor.cm-live-preview'), `seed ${seed}: live mode`).not.toBeNull();
  stats.liveSeeds += 1;
  if (document.querySelector('.cm-lp-image-wrap')) stats.liveWidgetSeeds += 1;
}
```
Also extend the test's header comment: "A third of the seeds run in live preview, so typing/renames interleave with rendered image widgets."
- [ ] **Step 2: Run red.** `npx vitest run src/hooks/useEditorMode.test.js src/components/EditorToolbar.test.jsx src/utils/editorCommands.test.js src/components/PageEditor.livePreview.realCodeMirror.test.jsx src/components/PageEditor.interleaving.realCodeMirror.test.jsx` → new tests FAIL.
- [ ] **Step 3: Implement.**

`useEditorMode.js` (mirrors `useRailOpen`):
```js
import { useCallback, useState } from 'react';
const KEY = 'wikantik.editor.mode';
function initialMode() {
  try {
    const stored = localStorage.getItem(KEY);
    if (stored === 'live' || stored === 'source') return stored;
  } catch (err) {
    console.warn('[editor-mode] could not read the saved editor mode', err?.message || err);
  }
  return 'source';
}
/** Source vs live-preview editing, remembered per browser; defaults to source. */
export function useEditorMode() {
  const [mode, setMode] = useState(initialMode);
  const toggle = useCallback(() => {
    setMode((prev) => {
      const next = prev === 'live' ? 'source' : 'live';
      try {
        localStorage.setItem(KEY, next);
      } catch (err) {
        console.warn('[editor-mode] could not save the editor mode', err?.message || err);
      }
      return next;
    });
  }, []);
  return [mode, toggle];
}
```
`EditorToolbar.jsx`: signature `EditorToolbar({ onRun, liveMode = false })`; after the mapped buttons render
```jsx
<button
  type="button"
  className="editor-format-btn editor-format-btn-mode"
  title={`Live preview (${formatKeys('Mod-E')})`}
  aria-label={`Live preview (${formatKeys('Mod-E')})`}
  aria-pressed={liveMode}
  onMouseDown={(e) => { e.preventDefault(); onRun('toggle-live-preview'); }}
>
  Live
</button>
```
`globals.css` (after `.editor-format-btn:hover`):
```css
.editor-format-btn-mode { margin-left: auto; font-family: var(--font-ui); font-weight: 600; letter-spacing: 0.02em; }
.editor-format-btn[aria-pressed="true"] {
  color: var(--accent); background: color-mix(in srgb, var(--accent) 12%, transparent);
  border-color: color-mix(in srgb, var(--accent) 40%, transparent);
}
```
`editorCommands.js`: add `toggleLivePreview` to the destructured params and, after `toggle-preview`, `cmd('toggle-live-preview', 'Toggle live preview', 'View', toggleLivePreview, { keys: 'Mod-E' }),`.

`PageEditor.jsx`:
- `import { useEditorMode } from '../hooks/useEditorMode';` and `import { loadEmbedState } from '../utils/livePreview/embedSource';`
- next to `useRailOpen()`: `const [editorMode, toggleEditorMode] = useEditorMode();`
- a second keydown effect (keep Mod-s untouched):
  ```js
  // Mod-e: source ⇄ live preview (Obsidian's binding), wherever focus is. CodeMirror does not bind Mod-e.
  useEffect(() => {
    const handler = (e) => {
      if (e.defaultPrevented || !(e.metaKey || e.ctrlKey) || e.altKey || e.shiftKey) return;
      if (e.code === 'KeyE' || e.key === 'e' || e.key === 'E') { e.preventDefault(); toggleEditorMode(); }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [toggleEditorMode]);
  ```
- `buildEditorCommands({ …, toggleLivePreview: toggleEditorMode })` and add `toggleEditorMode` to that effect's deps.
- live context, memoised on content (the attachments hook may return a fresh array each render):
  ```js
  const attachmentNamesKey = attachments.list.map((a) => a.fileName).join('\n');
  const livePreviewContext = useMemo(() => ({
    pageName: name,
    attachments: attachmentNamesKey ? attachmentNamesKey.split('\n') : [],
    loadEmbed: loadEmbedState,
  }), [name, attachmentNamesKey]);
  ```
- `<EditorToolbar onRun={runCommand} liveMode={editorMode === 'live'} />`
- `<CodeEditor … livePreview={editorMode === 'live'} livePreviewContext={livePreviewContext} />`

The side preview pane is untouched (independent toggle).
- [ ] **Step 4: Run green.** Same command as Step 2 → PASS. Then `npx vitest run src/components/PageEditor` (all PageEditor suites) → PASS.
- [ ] **Step 5: Commit** — `git add wikantik-frontend/src/hooks/useEditorMode.js wikantik-frontend/src/hooks/useEditorMode.test.js wikantik-frontend/src/components/EditorToolbar.jsx wikantik-frontend/src/components/EditorToolbar.test.jsx wikantik-frontend/src/utils/editorCommands.js wikantik-frontend/src/utils/editorCommands.test.js wikantik-frontend/src/components/PageEditor.jsx wikantik-frontend/src/components/PageEditor.livePreview.realCodeMirror.test.jsx wikantik-frontend/src/components/PageEditor.interleaving.realCodeMirror.test.jsx wikantik-frontend/src/styles/globals.css`; subject `feat(editor): Source/Live toggle (toolbar, command, Mod-e), persisted per browser` (+ trailer, Global Constraints).

---

### Task 9: Daily notes

**Files:**
- Create: `src/utils/dailyNote.js`, `src/utils/dailyNote.test.js`
- Modify: `src/commands/useGlobalCommands.js` (+ `useGlobalCommands.test.jsx`), `src/hooks/useGlobalHotkeys.js` (+ `useGlobalHotkeys.test.js`), `src/App.jsx` (~35)

**Interfaces:**
- Consumes: `api.listPages({ names, limit })`, `api.listClusters()` (`src/api/client.js`); `useGuardedNavigate` (`go(to, options)`).
- Produces:
  - `dailyNoteName(date = new Date()): string` → `'YYYY-MM-DD'` (local date).
  - `dailyNoteTitle(date = new Date(), locale?): string` → long local date.
  - `buildDailyNote(date, { journal = false, locale } = {}): { name, initialMetadata, initialContent }`.
  - `openDailyNote({ api, go, now = new Date(), locale }): Promise<void>`.
  - Global command `{ id: 'daily-note', title: "Open today's daily note", section: 'Page', keys: 'Mod-Alt-N' }`.
  - `useGlobalHotkeys({ onOpenOverlay, onDailyNote })` — Mod-Alt-N (matched on `e.code === 'KeyN'`) calls `onDailyNote`.
- [ ] **Step 1: Write the failing tests.**

`dailyNote.test.js`:
```js
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { dailyNoteName, dailyNoteTitle, buildDailyNote, openDailyNote } from './dailyNote';
const OCT1 = new Date(2026, 9, 1, 23, 30); // local time, late evening — must still be the 1st
describe('daily note helpers', () => {
  it('names the note by the LOCAL date and titles it with the long local date', () => {
    expect([dailyNoteName(OCT1), dailyNoteName(new Date(2026, 0, 5))]).toEqual(['2026-10-01', '2026-01-05']);
    expect(dailyNoteTitle(OCT1, 'en-GB')).toBe('Thursday, 1 October 2026');
  });
  it('builds an article tagged daily-note, in the journal cluster only when one exists', () => {
    expect(buildDailyNote(OCT1, { locale: 'en-GB' })).toEqual({
      name: '2026-10-01',
      initialMetadata: { type: 'article', date: '2026-10-01', tags: ['daily-note'], title: 'Thursday, 1 October 2026' },
      initialContent: '# Thursday, 1 October 2026\n\n',
    });
    expect(buildDailyNote(OCT1, { journal: true, locale: 'en-GB' }).initialMetadata.cluster).toBe('journal');
  });
});
describe('openDailyNote', () => {
  let api; let go;
  beforeEach(() => { go = vi.fn(); api = { listPages: vi.fn(), listClusters: vi.fn(() => Promise.resolve({ clusters: [{ name: 'journal' }] })) }; });
  it('opens an existing note without creation state', async () => {
    api.listPages.mockResolvedValue({ pages: [{ name: '2026-10-01' }] });
    await openDailyNote({ api, go, now: OCT1 });
    expect(api.listPages).toHaveBeenCalledWith({ names: ['2026-10-01'], limit: 1 });
    expect(go).toHaveBeenCalledWith('/edit/2026-10-01');
    expect(api.listClusters).not.toHaveBeenCalled();
  });
  it('starts a new note, in the journal cluster when a hub declares it', async () => {
    api.listPages.mockResolvedValue({ pages: [] });
    await openDailyNote({ api, go, now: OCT1, locale: 'en-GB' });
    const [path, opts] = go.mock.calls[0];
    expect(path).toBe('/edit/2026-10-01');
    expect(opts.state.initialMetadata).toMatchObject({ type: 'article', tags: ['daily-note'], cluster: 'journal' });
    expect(opts.state.initialContent).toBe('# Thursday, 1 October 2026\n\n');
  });
  it('omits the cluster when no journal hub exists', async () => {
    api.listPages.mockResolvedValue({ pages: [] });
    api.listClusters.mockResolvedValue({ clusters: [{ name: 'finance' }] });
    await openDailyNote({ api, go, now: OCT1 });
    expect(go.mock.calls[0][1].state.initialMetadata.cluster).toBeUndefined();
  });
  it('still opens the note (and warns) when the lookups fail', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.listPages.mockRejectedValue(new Error('offline'));
    api.listClusters.mockRejectedValue(new Error('offline'));
    await openDailyNote({ api, go, now: OCT1 });
    expect(go).toHaveBeenCalledWith('/edit/2026-10-01', expect.objectContaining({ state: expect.any(Object) }));
    expect(warn).toHaveBeenCalledTimes(2);
    warn.mockRestore();
  });
});
```
`useGlobalCommands.test.jsx` — add at the top `vi.mock('../api/client', () => ({ api: { listPages: vi.fn(), listClusters: vi.fn() } }));` + `import { api } from '../api/client';` + `import { useNavigationGuard } from '../navigation/NavigationGuardProvider';`, then:
```js
function DirtyHost(props) { useGlobalCommands(props); useNavigationGuard(true); return null; }
it('daily-note is registered everywhere and opens today\'s note', async () => {
  api.listPages.mockResolvedValue({ pages: [] });
  api.listClusters.mockResolvedValue({ clusters: [] });
  mount('/search', { openOverlay: vi.fn(), toggleSidebar: vi.fn() });
  expect(getCommands().find((c) => c.id === 'daily-note')).toMatchObject({ section: 'Page', keys: 'Mod-Alt-N' });
  await act(async () => { await runCommand('daily-note'); });
  expect(screen.getByTestId('loc').textContent).toMatch(/^\/edit\/\d{4}-\d{2}-\d{2}$/);
});
it('daily-note asks first when the current page has unsaved changes', async () => {
  api.listPages.mockResolvedValue({ pages: [{ name: 'x' }] });
  render(<MemoryRouter initialEntries={['/edit/Alpha']}><NavigationGuardProvider>
    <DirtyHost openOverlay={vi.fn()} toggleSidebar={vi.fn()} /><Probe /></NavigationGuardProvider></MemoryRouter>);
  await act(async () => { await runCommand('daily-note'); });
  expect(screen.getByTestId('guard-dialog')).toBeInTheDocument();
  expect(screen.getByTestId('loc')).toHaveTextContent('/edit/Alpha');
});
```
`useGlobalHotkeys.test.js` — add (reuse the file's `renderHook` / `fireEvent` imports):
```js
it('Mod-Alt-N opens the daily note (matched on the physical key, as macOS Option rewrites e.key)', () => {
  const onDailyNote = vi.fn();
  renderHook(() => useGlobalHotkeys({ onOpenOverlay: vi.fn(), onDailyNote }));
  fireEvent.keyDown(window, { key: '˜', code: 'KeyN', metaKey: true, altKey: true });
  expect(onDailyNote).toHaveBeenCalledTimes(1);
  fireEvent.keyDown(window, { key: 'n', code: 'KeyN', ctrlKey: true }); // no Alt → not ours
  expect(onDailyNote).toHaveBeenCalledTimes(1);
});
```
- [ ] **Step 2: Run red.** `npx vitest run src/utils/dailyNote.test.js src/commands/useGlobalCommands.test.jsx src/hooks/useGlobalHotkeys.test.js` → new tests FAIL.
- [ ] **Step 3: Implement.**

`dailyNote.js`:
```js
const pad = (n) => String(n).padStart(2, '0');
export function dailyNoteName(date = new Date()) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}
export function dailyNoteTitle(date = new Date(), locale = undefined) {
  return date.toLocaleDateString(locale, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
}
/** A new daily note: an ordinary article tagged daily-note (no page type or server change). */
export function buildDailyNote(date, { journal = false, locale } = {}) {
  const name = dailyNoteName(date);
  const title = dailyNoteTitle(date, locale);
  const initialMetadata = { type: 'article', date: name, tags: ['daily-note'], title };
  if (journal) initialMetadata.cluster = 'journal';
  return { name, initialMetadata, initialContent: `# ${title}\n\n` };
}
/** Navigate (guarded) to today's note: open it if it exists, else start it as a new page saved on first save. */
export async function openDailyNote({ api, go, now = new Date(), locale }) {
  const name = dailyNoteName(now);
  let exists = false;
  try { exists = ((await api.listPages({ names: [name], limit: 1 }))?.pages ?? []).some((p) => p.name === name); }
  catch (err) { console.warn('[daily-note] existence check failed for ' + name, err?.message || err); }
  if (exists) { go(`/edit/${name}`); return; }
  let journal = false;
  try { journal = ((await api.listClusters())?.clusters ?? []).some((c) => c.name === 'journal'); }
  catch (err) { console.warn('[daily-note] cluster list unavailable', err?.message || err); }
  const { initialMetadata, initialContent } = buildDailyNote(now, { journal, locale });
  go(`/edit/${name}`, { state: { initialMetadata, initialContent } });
}
```
(Do not route the name through `isValidSlug` — it rejects `-`. PageEditor uses `location.state` only when `getPage` 404s, so a failed existence check still lands correctly.)

`useGlobalCommands.js`: `import { api } from '../api/client';` and `import { openDailyNote } from '../utils/dailyNote';`; add to the base `cmds` list:
`{ id: 'daily-note', title: "Open today's daily note", section: 'Page', keys: 'Mod-Alt-N', keywords: ['journal', 'today'], run: () => openDailyNote({ api, go }) },`

`useGlobalHotkeys.js`: signature `useGlobalHotkeys({ onOpenOverlay, onDailyNote } = {})`; at the top of `handler`, before the existing early return:
```js
      if (!e.defaultPrevented && (e.metaKey || e.ctrlKey) && e.altKey && !e.shiftKey && e.code === 'KeyN') {
        e.preventDefault(); onDailyNote?.(); return;
      }
```
add `onDailyNote` to the effect deps and update the doc comment ("Mod-Alt-N opens today's daily note").

`App.jsx`: `import { runCommand } from './commands/registry';`, then
```js
  const openDailyNoteCmd = useCallback(() => { runCommand('daily-note'); }, []);
  useGlobalHotkeys({ onOpenOverlay: openOverlay, onDailyNote: openDailyNoteCmd });
```
(`runCommand` already logs unknown/failed commands with `console.warn`.)
- [ ] **Step 4: Run green.** Same command as Step 2 → PASS; plus `npx vitest run src/App` if an App test exists.
- [ ] **Step 5: Commit** — `git add wikantik-frontend/src/utils/dailyNote.js wikantik-frontend/src/utils/dailyNote.test.js wikantik-frontend/src/commands/useGlobalCommands.js wikantik-frontend/src/commands/useGlobalCommands.test.jsx wikantik-frontend/src/hooks/useGlobalHotkeys.js wikantik-frontend/src/hooks/useGlobalHotkeys.test.js wikantik-frontend/src/App.jsx`; subject `feat: open today's daily note (command + Mod-Alt-N)` (+ trailer, Global Constraints).

---

### Task 10: Changelog and full verification

**Files:**
- Modify: `CHANGELOG.md` (`## [Unreleased]`)
- [ ] **Step 1: Full frontend gate.**
```bash
cd wikantik-frontend && npx vitest run && npm run lint && npm run build 2>&1 | tee /tmp/claude-build.txt | tail -30
```
Expected: all tests pass (if a vitest-concurrency flake appears, re-run that file alone before investigating — see project memory); `npm run lint` 0 errors, and `npx eslint` over every file this plan created or modified reports 0 warnings; build succeeds with no new chunk-size warning. Check sizes: `grep -a -E "codemirror|PageEditor" /tmp/claude-build.txt` — the `codemirror` chunk must stay under the 700 KB warning limit (≈632 KB before this work; live preview code must land in the `PageEditor` chunk, not `codemirror` or the entry chunk). Record both sizes in the commit message.
- [ ] **Step 2: Coverage floor.** `npx vitest run --coverage` — thresholds in `vite.config.js` (lines 87 / statements 85 / functions 85 / branches 76) must still hold.
- [ ] **Step 3: Manual browser pass** (local Tomcat per CLAUDE.md; credentials in `test.properties`). At desktop width, light **and** dark theme, on a page containing every construct (headings 1–6, emphasis/strong/del/code, link, `[[ ]]` forms, image + `![[x.png|200]]`, `![[OtherPage]]`, quote, two callouts, rule, nested bullets, tasks, `$x$`, `$5 and $10`, `$$` block, fenced code, a table, `[{TableOfContents}]`, frontmatter):
  - Live off → editor identical to before. Live on (button / Mod-e / palette "Toggle live preview") → constructs render; the caret line shows source; toggling keeps caret, scroll, folds; undo after toggling works.
  - Click a task box → toggles, Ctrl-Z reverts in one step. Click a block widget → source revealed. Ctrl-click a link / embed → opens in a new tab. Ctrl-hover shows the preview card.
  - Reload → mode remembered. Mod-Alt-N (or palette "Open today's daily note") → today's note opens/creates; with unsaved edits the guard dialog appears.
  - Type quickly on a decorated line and in a 2,000-line page — no lag, no lost characters.
  Note anything broken; fix with a failing test first (TDD), then continue.
- [ ] **Step 4: Changelog.** Under `## [Unreleased]` add:
```markdown
### Added
- Editor Live Preview: an Obsidian-style editing mode that renders markdown in place — headings, emphasis,
  links and `[[wikilinks]]`, images and attachment embeds, page embeds (`![[Page]]`), quotes and callouts,
  rules, bullets, clickable task checkboxes, KaTeX math — while the lines you are editing show raw source.
  Toggle with the toolbar's **Live** button, `Ctrl/Cmd-E`, or "Toggle live preview"; remembered per browser
  (default: source). Tables, HTML and plugins stay source.
- "Open today's daily note" command (`Ctrl/Cmd-Alt-N`): opens or starts `YYYY-MM-DD` as an article tagged
  `daily-note` (in the `journal` cluster when a hub declares it).

### Changed
- The editor parses GitHub-flavoured markdown (tables, strikethrough, task lists) in both modes, matching
  the server and preview.
```
- [ ] **Step 5: Commit** — `git add CHANGELOG.md`; subject `docs(changelog): live preview and daily notes (codemirror <size> KB, PageEditor <size> KB)` (+ trailer, Global Constraints).
- [ ] **Step 6: Request code review** (superpowers:requesting-code-review) over the whole branch range for this plan, with the spec and this plan as references; address findings test-first.
