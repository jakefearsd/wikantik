import { describe, it, expect } from 'vitest';
import { EditorState, EditorSelection } from '@codemirror/state';
import { markdown } from '@codemirror/lang-markdown';
import fixture from '../__fixtures__/renderedFences.json';
import { fullyParsed } from '../../test/fullParse';
import { linearCost, LINEAR_COST_TIMEOUT_MS } from '../../test/linearCost';
import { editorMarkdownConfig } from '../editorMarkdown';
import { blocksFromMarkdown, blockMarkers } from '../blockIds';
import { blockCandidates, activeLinesOf, livePreviewSpecs, resolveImageSrc, attachmentUrl, blockSpecs, parseWikiTarget, caretsInLinks } from './ranges';
function stateOf(doc, selection) {
  const state = EditorState.create({
    doc, selection,
    extensions: [markdown(editorMarkdownConfig), EditorState.allowMultipleSelections.of(true)],
  });
  return fullyParsed(state);
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
    expect([...activeLinesOf(state)].sort((a, b) => a - b)).toEqual([1, 3, 4, 5]);
  });
});
describe('checkbox spec', () => {
  it('carries markerFrom, the offset of the box', () => {
    const state = stateOf('- [ ] a\n- [x] b\n\nend');
    const boxes = livePreviewSpecs(state, new Set([4])).filter((s) => s.widget?.type === 'checkbox');
    expect(boxes.map((s) => s.widget.markerFrom)).toEqual([2, 10]);
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
  it('keeps styling but hides no inline marker on an active line (the heading marker stays hidden)', () => {
    const { state, specs } = specsOf(DOC, [1, 3]);
    expect(replacing(specs).map((s) => state.sliceDoc(s.from, s.to))).toEqual(['# ']);
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
  it('decorates a defined reference but leaves an undefined shortcut as source', () => {
    const { state, specs } = specsOf('[x][r] and [y]\n\n[r]: http://a.example\n\nend', [5]);
    expect(visible(state, specs, 1)).toBe('x and [y]');
  });
  it('attachmentUrl encodes each path segment so a # in a file name resolves', () => {
    expect(attachmentUrl('My Page', 'a#b.png')).toBe('/attach/My%20Page/a%23b.png');
    expect(attachmentUrl('P', 'sub/a b.png')).toBe('/attach/P/sub/a%20b.png');
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
    expect(titles).toEqual([{ type: 'callout-title', style: 'warning', rawType: 'caution' }, { type: 'callout-title', style: 'question', rawType: 'faq' }]);
    // The marker is a line marker that stays under the caret, like the `> ` before it.
    const onTitle = livePreviewSpecs(state, new Set([1])).filter((s) => s.from < 20 && (s.kind === 'hide' || s.kind === 'widget'));
    expect(onTitle.map((s) => [s.from, s.to, s.sticky, s.lineMarker])).toEqual([[0, 2, true, true], [2, 13, true, true]]);
  });
  it('rules, bullets and tasks become widgets; ordered markers stay', () => {
    const doc = 'a\n\n***\n\n- one\n  - nested\n\n1. first\n\n- [ ] todo\n- [x] done\n\nend';
    const { state, specs } = specsOf(doc, [13]);
    expect(visible(state, specs, 3)).toBe('[rule]');
    expect(visible(state, specs, 5)).toBe('[bullet]one'); // the marker's one space is part of the widget's range
    expect(visible(state, specs, 6)).toBe('  [bullet]nested');
    expect(visible(state, specs, 8)).toBe('1. first');
    expect(visible(state, specs, 10)).toBe('[checkbox]todo');
    expect(specs.filter((s) => s.widget?.type === 'checkbox').map((s) => s.widget.checked)).toEqual([false, true]);
  });
  it('fenced code: a header for the opening line, the closing line hidden, nothing inside decorated', () => {
    for (const active of [[5], [2]]) { // the header and the hidden closing line do not depend on the caret
      const { state, specs } = specsOf('```js\nconst a = **1**;\n```\n\nend', active);
      expect(show(state, specs)).toEqual(['line cm-lp-fence @1', 'widget fence-header "```js"', 'line cm-lp-codeblock @2',
        'line cm-lp-fence-close @3', 'hide "```"']);
      expect(replacing(specs).map((s) => [s.sticky, s.hiddenLine, s.lineMarker])).toEqual([[true, 'open', undefined], [true, 'close', undefined]]);
      expect(specs[1].widget).toEqual({ type: 'fence-header', language: 'js', kind: 'code' });
    }
  });
  it('fenced code: an unclosed fence and one with no body line stay source', () => {
    for (const doc of ['```js\nconst a = 1;', '```js\n```\n\nend']) {
      const { state, specs } = specsOf(doc, []);
      expect(replacing(specs)).toEqual([]);
      expect(show(state, specs)[0]).toBe('line cm-lp-fence @1');
    }
  });
  it('fenced code: a drawable fence gets a diagram header once one of its lines is active; `example` is plain code', () => {
    const doc = '```mermaid\ngraph TD; A-->B;\n```\n\nend';
    expect(replacing(specsOf(doc, [5]).specs)).toEqual([]); // drawn as a block widget (blockSpecs)
    expect(replacing(specsOf(doc, [2]).specs)[0].widget).toEqual({ type: 'fence-header', language: 'mermaid', kind: 'diagram' });
    expect(replacing(specsOf('```mermaid example\ngraph TD; A-->B;\n```\n\nend', [5]).specs)[0].widget)
      .toEqual({ type: 'fence-header', language: 'mermaid', kind: 'code' });
    // Nested in a list it is never a block widget, so it always has its header.
    expect(replacing(specsOf('- item\n\n  ```mermaid\n  graph TD; A-->B;\n  ```\n\nend', [7]).specs).map((s) => s.widget?.type ?? s.kind))
      .toEqual(['bullet', 'fence-header', 'hide']);
  });
  it('fenced code: only lines inside the viewport range are decorated (huge fence, small range)', () => {
    const body = Array.from({ length: 2000 }, (_, i) => `line ${i}`).join('\n');
    const doc = `\`\`\`\n${body}\n\`\`\`\n\nend`;
    const state = stateOf(doc);
    const mid = state.doc.line(1000);
    const specs = livePreviewSpecs(state, new Set([state.doc.lines]), { from: mid.from, to: state.doc.line(1003).to });
    const lines = specs.filter((s) => s.kind === 'line').map((s) => state.doc.lineAt(s.from).number);
    expect(lines.length).toBeGreaterThan(0);
    expect(lines.length).toBeLessThanOrEqual(5);
    expect(Math.min(...lines)).toBeGreaterThanOrEqual(1000);
    expect(Math.max(...lines)).toBeLessThanOrEqual(1003);
  });
  it('tables, HTML and frontmatter stay untouched source (HTML only gains its line class)', () => {
    const doc = '---\ntitle: x\n---\n\n| a | **b** |\n|---|---|\n| 1 |\n\n<div>**x**</div>\n\nend'; // a ragged table: not the grid's
    const { specs } = specsOf(doc, [11]);
    expect(specs.filter((s) => s.kind !== 'line')).toEqual([]);
    expect(specs.filter((s) => s.kind === 'line').map((s) => s.cls)).toEqual(['cm-lp-html']);
  });
  it('limits the walk to the requested range', () => {
    const state = stateOf('**a**\n\n**b**\n');
    const third = state.doc.line(3);
    const specs = livePreviewSpecs(state, new Set(), { from: third.from, to: third.to });
    expect(specs.length).toBeGreaterThan(0);
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
  it('stays a chip on the active line: the brackets are still hidden', () => {
    const { state, specs } = specsOf('x [[Page]] y', [1], ctx);
    expect(visible(state, specs, 1)).toBe('x Page y');
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
  it('shows a whole-line page embed inside a list as a link', () => {
    const { state, specs } = specsOf('- ![[Other]]\n\nend', [3], ctx);
    expect(visible(state, specs, 1)).toContain('Other');
    expect(visible(state, specs, 1)).not.toContain('[[');
  });
  it('drops emphasis that would cut through a wikilink, keeps emphasis around one', () => {
    const { state, specs } = specsOf('**bold [[Page|P]]** and *a [[B*c]]*\n\nend', [3], ctx);
    expect(visible(state, specs, 1)).toMatch(/^bold P and (\*a B\*c\*|a B\*c)$/);
  });
});
describe('livePreviewSpecs — strikethrough', () => {
  it('styles ~~x~~ but never single-tilde ~x~ (prose like "~5 min to ~10 min")', () => {
    expect(specsOf('a ~~b~~ c\n\nend', [3]).specs.some((s) => s.cls === 'cm-lp-del')).toBe(true);
    expect(specsOf('a ~b~ c\n\nend', [3]).specs.some((s) => s.cls === 'cm-lp-del')).toBe(false);
    expect(specsOf('~5 min to ~10 min\n\nend', [3]).specs.some((s) => s.cls === 'cm-lp-del')).toBe(false);
  });
});
describe('livePreviewSpecs — inline tags', () => {
  const tagMarks = (doc, active) => {
    const o = specsOf(`${doc}\n\nend`, active);
    return o.specs.filter((s) => s.cls === 'cm-lp-tag').map((s) => o.state.sliceDoc(s.from, s.to));
  };
  it('marks a #tag off the caret line only, and never replaces its text', () => {
    expect(tagMarks('a #Foo b', [3])).toEqual(['#Foo']);
    expect(tagMarks('a #Foo b', [1])).toEqual([]);
    const off = specsOf('a #Foo b\n\nend', [3]);
    expect(visible(off.state, off.specs, 1)).toBe('a #Foo b');
  });
  it('refuses what the reader refuses', () => {
    for (const doc of ['#123', 'x#foo', '## H #x', '`#x`', '$#x$', '[t](#frag)', '[[Page#Heading]]', '```\n#x\n```', '    #x']) {
      expect(tagMarks(doc, [99])).toEqual([]);
    }
  });
  it('marks a tag in a list item and a blockquote', () => {
    expect(tagMarks('- #foo', [99])).toEqual(['#foo']);
    expect(tagMarks('> see #foo', [99])).toEqual(['#foo']);
  });
});
describe('livePreviewSpecs — highlight', () => {
  it('marks ==x== and hides the delimiters off the caret line only', () => {
    const off = specsOf('a ==b== c\n\nend', [3]);
    expect(off.specs.filter((s) => s.cls === 'cm-lp-mark').map((s) => off.state.sliceDoc(s.from, s.to))).toEqual(['==b==']);
    expect(visible(off.state, off.specs, 1)).toBe('a b c');
    const on = specsOf('a ==b== c\n\nend', [1]);
    expect(on.specs.some((s) => s.cls === 'cm-lp-mark')).toBe(true);
    expect(visible(on.state, on.specs, 1)).toBe('a ==b== c');
  });
  it('nests with strong and refuses what the reader refuses', () => {
    expect(visible(...Object.values(specsOf('==**x**==\n\nend', [3])).slice(0, 2), 1)).toBe('x');
    for (const doc of ['a == b', '== x ==', '===x===', '=x=', '==x']) {
      expect(specsOf(`${doc}\n\nend`, [3]).specs.some((s) => s.cls === 'cm-lp-mark')).toBe(false);
    }
  });
  it('pairs across a soft line break inside one paragraph, never across paragraphs or list items', () => {
    const o = specsOf('Some ==marked\nacross== text.\n\nend', [4]);
    expect(o.specs.filter((s) => s.cls === 'cm-lp-mark').map((s) => o.state.sliceDoc(s.from, s.to))).toEqual(['==marked\nacross==']);
    for (const doc of ['a ==x\n\ny== b', '- a ==x\n- y== b', '# H ==x\ny== b']) {
      expect(specsOf(`${doc}\n\nend`, [99]).specs.some((s) => s.cls === 'cm-lp-mark'), doc).toBe(false);
    }
  });
  it('draws a highlight around a wikilink and a comment (only its delimiters must be free)', () => {
    for (const doc of ['==see [[Page]] now==', '==a %%note%% b==']) {
      const o = specsOf(`${doc}\n\nend`, [99]);
      expect(o.specs.filter((s) => s.cls === 'cm-lp-mark').map((s) => o.state.sliceDoc(s.from, s.to)), doc).toEqual([doc]);
    }
  });
  it('leaves code spans and math alone', () => {
    for (const doc of ['`==x==`', '$a==b==c$', '$$\na==b==c\n$$']) {
      expect(specsOf(`${doc}\n\nend`, [99]).specs.some((s) => s.cls === 'cm-lp-mark')).toBe(false);
    }
  });
});
describe('livePreviewSpecs — comments', () => {
  const commentMarks = (o) => o.specs.filter((s) => s.cls === 'cm-lp-comment').map((s) => o.state.sliceDoc(s.from, s.to));
  it('dims a comment and keeps its delimiters visible, even off the caret line', () => {
    const o = specsOf('a %%note%% b\n\nend', [3]);
    expect(commentMarks(o)).toEqual(['%%note%%']);
    expect(visible(o.state, o.specs, 1)).toBe('a %%note%% b');
  });
  it('dims a multi-line comment as one range', () => {
    const o = specsOf('a %%one\ntwo%% b\n\nend', [4]);
    expect(commentMarks(o)).toEqual(['%%one\ntwo%%']);
  });
  it('never decorates a highlight inside a comment (the reader shows neither)', () => {
    for (const doc of ['a %%==x==%% b', 'a %%one\n==x== two%% b']) {
      const o = specsOf(`${doc}\n\nend`, [99]);
      expect(o.specs.some((s) => s.cls === 'cm-lp-mark')).toBe(false);
      expect(commentMarks(o).length).toBe(1);
    }
    expect(specsOf('==x== %%c%%\n\nend', [99]).specs.some((s) => s.cls === 'cm-lp-mark')).toBe(true);
  });
  it('decorates nothing inside a comment but the dimming itself (wikilinks, embeds, math, plugins, footnotes)', () => {
    for (const body of ['[[x]]', '![[x]]', '$x$', '[{Foo}]', '[^1]', '[[x|alias]]', '[[x]] and $y$']) {
      const o = specsOf(`a %%${body}%% b\n\nend`, [99]);
      expect(show(o.state, o.specs).filter((s) => !s.includes('cm-lp-comment'))).toEqual([]);
      expect(commentMarks(o).length).toBe(1);
    }
    const spanning = specsOf('a %%one\n[[x]] $y$ two%% b\n\nend', [99]);
    expect(replacing(spanning.specs)).toEqual([]);
  });
  it('still decorates a wikilink and math beside a comment', () => {
    const o = specsOf('[[x]] %%c%% $y$\n\nend', [99]);
    expect(replacing(o.specs).map((s) => o.state.sliceDoc(s.from, s.to))).toEqual(['[[', ']]', '$y$']);
  });
  it('draws no block-id chip for a marker inside a comment, and such a marker makes no later one a duplicate', () => {
    const o = specsOf('%%\n\nhidden ^sec\n\n%%\n\nShown ^sec\n\nend', [99]);
    const ids = o.specs.filter((s) => s.cls === 'cm-lp-blockid' || s.cls === 'cm-lp-blockid-dup');
    expect(ids.map((s) => [s.cls, o.state.doc.lineAt(s.from).number])).toEqual([['cm-lp-blockid', 7]]);
  });
  it('leaves code, math, an unterminated marker and a Mermaid comment undimmed', () => {
    for (const doc of ['`%%x%%`', '$a %%x%% b$', '$$\nx %% y %%\n$$', 'a %%b', '```mermaid\ngraph TD\n%% c\nA-->B\n```']) {
      expect(commentMarks(specsOf(`${doc}\n\nend`, [99]))).toEqual([]);
    }
  });
});
describe('livePreviewSpecs — math and plugins', () => {
  it('renders inline math on inactive lines', () => {
    const { specs } = specsOf('a $x+y$ b\n\nend', [3]);
    expect(specs.filter((s) => s.kind === 'widget').map((s) => s.widget)).toEqual([{ type: 'math', tex: 'x+y', display: false }]);
  });
  it('reads `$$x$$` as the reader does: one inline-math widget over `$x$`, the outer dollars left as text', () => {
    const { specs } = specsOf('see $$x$$ here\n\nend', [3]);
    const widgets = specs.filter((s) => s.kind === 'widget');
    expect(widgets.map((s) => s.widget)).toEqual([{ type: 'math', tex: 'x', display: false }]);
    expect([widgets[0].from, widgets[0].to]).toEqual([5, 8]);
  });
  it.each(['$5 and $10', 'costs $5 or $6 today', '$ x $', '\\$5$', '`$x$`', '$$x$ y'])('currency is never math: %s', (src) => {
    const { specs } = specsOf(`${src}\n\nend`, [3]);
    expect(specs.filter((s) => s.widget?.type === 'math' && s.widget.tex !== 'x')).toEqual([]);
    if (src !== '$$x$ y') expect(specs.filter((s) => s.widget?.type === 'math')).toEqual([]);
  });
  it('follows the shared rule: currency pairs are not math, escaped dollars stay inside, trailing digit refuses', () => {
    const math = (src) => specsOf(`${src}\n\nend`, [3]).specs.filter((s) => s.widget?.type === 'math').map((s) => s.widget.tex);
    expect(math('costs $5 and $10')).toEqual([]);
    expect(math('$c_o = \\$1.00$')).toEqual(['c_o = \\$1.00']);
    expect(math('$x$5')).toEqual([]);
  });
  it('does not style emphasis inside math, and math on the active line stays source', () => {
    expect(specsOf('$a*b*c$\n\nend', [3]).specs.filter((s) => s.cls === 'cm-lp-em')).toEqual([]);
    expect(replacing(specsOf('$x$', [1]).specs)).toEqual([]);
  });
  it('marks wiki plugins as a pill on the caret line and leaves them visible', () => {
    const { state, specs } = specsOf('[{TableOfContents}] and [{Image src=a}]()\n\nend', [1]);
    expect(show(state, specs)).toEqual(expect.arrayContaining(['mark cm-lp-plugin "[{TableOfContents}]"', 'mark cm-lp-plugin "[{Image src=a}]()"']));
    expect(specs.filter((s) => s.widget?.type === 'chip')).toEqual([]);
    expect(visible(state, specs, 1)).toBe('[{TableOfContents}] and [{Image src=a}]()');
  });
  it('replaces each [{...}] match with its own chip off the caret line', () => {
    const { state, specs } = specsOf('a [{ALLOW view Admin}] b [{$x}]\n\nend', [3]);
    const chips = specs.filter((s) => s.widget?.type === 'chip');
    expect(chips.map((s) => state.sliceDoc(s.from, s.to))).toEqual(['[{ALLOW view Admin}]', '[{$x}]']);
    expect(chips[0].widget).toEqual({ type: 'chip', text: 'view: Admin', cls: 'cm-lp-chip cm-lp-chip-acl', title: '[{ALLOW view Admin}]' });
  });
  it('makes no chip for [{...}] inside inline code or a fence', () => {
    const { specs } = specsOf('`[{ALLOW view A}]`\n\n```\n[{ALLOW view A}]\n```\n\nend', [7]);
    expect(specs.filter((s) => s.widget?.type === 'chip')).toEqual([]);
  });
  it('a block-id marker paragraph keeps its blockid mark and gets a chip', () => {
    const { state, specs } = specsOf('[{ALLOW view Admin}] ^abc\n\nend', [3]);
    expect(specs.filter((s) => s.widget?.type === 'chip')).toHaveLength(1);
    expect(specs.some((s) => s.kind === 'mark' && s.cls === 'cm-lp-blockid' && state.sliceDoc(s.from, s.to) === '^abc')).toBe(true);
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
describe('fence block widgets', () => {
  const block = (doc, active = [99]) => { const state = stateOf(doc); return { state, specs: blockSpecs(state, new Set(active), {}) }; };
  it('fencedMermaidBecomesBlockWidget', () => {
    const doc = '```mermaid\nflowchart TD\n  A-->B\n```\n\nend';
    const { specs } = block(doc, [6]);
    expect(specs).toEqual([{ kind: 'block', from: 0, to: 35, widget: { type: 'fence', language: 'mermaid', source: 'flowchart TD\n  A-->B\n' } }]);
  });
  it('unclosedFenceIsNotAWidget', () => {
    expect(block('```mermaid\nflowchart TD\n').specs).toEqual([]);
  });
  it('exampleFenceIsNotAWidget', () => {
    expect(block('```mermaid example\nflowchart TD\n```').specs).toEqual([]);
  });
  it('otherLanguageAndNestedFencesStayCode', () => {
    expect(block('```java\nint x;\n```').specs).toEqual([]);
    expect(block('- item\n\n  ```mermaid\n  flowchart TD\n  ```').specs).toEqual([]);
  });
  it('activeLineShowsSource', () => {
    expect(block('```mermaid\nflowchart TD\n```\n\nend', [2]).specs).toEqual([]);
  });
});
describe('table block widgets', () => {
  const block = (doc, active = [99]) => { const state = stateOf(doc); return { state, specs: blockSpecs(state, new Set(active), {}) }; };
  // Ragged (its last row is a cell short), so the widget draws it: a table with every row whole is the grid's.
  const TABLE = '| a | b |\n|:--|--:|\n| 1 | 2 |\n| 3 |';
  it('a top-level table off the caret is one block spec spanning exactly its lines', () => {
    const doc = `intro\n\n${TABLE}\n\nend`;
    const { specs } = block(doc, [1]);
    const from = doc.indexOf('| a');
    expect(specs).toEqual([{ kind: 'block', from, to: from + TABLE.length, widget: { type: 'table', source: TABLE } }]);
  });
  it('a caret or selection on any table line reveals the whole table', () => {
    const doc = `intro\n\n${TABLE}\n\nend`;
    for (const line of [3, 4, 5, 6]) expect(block(doc, [line]).specs).toEqual([]);
  });
  it('a table nested in a list or blockquote stays source', () => {
    expect(block(`- item\n\n  ${TABLE.replace(/\n/g, '\n  ')}`).specs).toEqual([]);
    expect(block(`> ${TABLE.replace(/\n/g, '\n> ')}`).specs).toEqual([]);
  });
  it('a table whose delimiter row is invalid stays source', () => {
    expect(block('| a | b |\n|--|x|\n| 1 | 2 |').specs).toEqual([]);
  });
  it('a non-blank line right after the last row is inside the widget (GFM lazy continuation)', () => {
    const doc = `${TABLE}\nlazy line\n\nafter`;
    const { specs } = block(doc);
    expect(specs).toEqual([{ kind: 'block', from: 0, to: TABLE.length + 10, widget: { type: 'table', source: `${TABLE}\nlazy line` } }]);
    expect(block(doc, [5]).specs).toEqual([]); // the caret on the lazy line reveals the table
  });
  it('a table at the end of the document without a trailing newline is covered exactly', () => {
    const doc = `x\n\n${TABLE}`;
    expect(block(doc).specs).toEqual([{ kind: 'block', from: 3, to: doc.length, widget: { type: 'table', source: TABLE } }]);
  });
  it('the inline pass never decorates inside a table the widget draws', () => {
    const doc = `${TABLE.replace('| 1 |', '| **b** [[P]] $x$ |')}\n\nend`;
    const { specs } = specsOf(doc, [99]);
    expect(specs.filter((s) => s.from < doc.indexOf('\n\nend'))).toEqual([]);
  });
});
describe('grid tables', () => {
  const GRID = '| a | b |\n|:--|--:|\n| **x** | [[P]] |\n| 3 |  |';
  const doc = `intro\n\n${GRID}\n\nend`;
  const start = doc.indexOf('| a');
  const hidden = (specs) => specs.filter((s) => s.sticky && (s.kind === 'hide' || s.kind === 'widget')).map((s) => [doc.slice(s.from, s.to), s.widget?.type ?? s.kind]);
  it('a table with every row whole is no block widget, with the caret on it or off it', () => {
    const state = stateOf(doc);
    expect(blockSpecs(state, new Set([99]), {})).toEqual([]);
    expect(blockCandidates(state)).toEqual([]);
  });
  it('hides each row\'s pipes and padding as sticky ranges, an empty cell\'s as its box, and the delimiter row as a hidden line', () => {
    const { specs } = specsOf(doc, [99]);
    expect(hidden(specs)).toEqual([
      ['| ', 'hide'], [' | ', 'hide'], [' |', 'hide'],
      ['|:--|--:|', 'hide'],
      ['| ', 'hide'], [' | ', 'hide'], [' |', 'hide'],
      ['| ', 'hide'], [' | ', 'hide'], [' |', 'table-cell'],
    ]);
    const delimiter = specs.find((s) => s.hiddenLine);
    expect(doc.slice(delimiter.from, delimiter.to)).toBe('|:--|--:|');
    expect(delimiter.hiddenLine).toBe('close');
    expect(specs.filter((s) => s.kind === 'line').map((s) => s.cls)).toEqual(['cm-lp-table-delim']);
    expect(specs.some((s) => s.lineMarker)).toBe(false); // Backspace never removes a pipe
    expect(specs.find((s) => s.widget?.type === 'table-cell').widget).toEqual({ type: 'table-cell', col: 1, last: true, align: 'right' });
  });
  it('the same ranges stay hidden with the caret on the row', () => {
    const state = stateOf(doc);
    const row = state.doc.lineAt(doc.indexOf('| **x**')).number;
    const off = livePreviewSpecs(state, new Set(), {});
    const on = livePreviewSpecs(state, new Set([row]), {});
    expect(hidden(on)).toEqual(hidden(off));
  });
  it('decorates a cell\'s text as a paragraph\'s: emphasis, and a link as a chip', () => {
    const { specs } = specsOf(doc, [99]);
    const marks = specs.filter((s) => s.kind === 'mark').map((s) => [s.cls, doc.slice(s.from, s.to)]);
    expect(marks).toContainEqual(['cm-lp-strong', '**x**']);
    expect(marks).toContainEqual(['cm-lp-linkchip', '[[P]]']);
  });
  it('a caret in a cell\'s trailing padding ends that cell\'s hidden range at the caret', () => {
    const padded = doc.replace('| 3 |', '| 3  |');
    const caret = padded.indexOf('| 3  |') + 4; // after the first of the two spaces
    const state = stateOf(padded).update({ selection: { anchor: caret } }).state;
    const specs = livePreviewSpecs(state, new Set(), {});
    expect(specs.some((s) => s.kind === 'hide' && s.sticky && s.from === caret)).toBe(true);
    expect(specs.some((s) => s.kind === 'hide' && s.from === caret - 1)).toBe(false);
  });
  it('reads only the rows in the requested range', () => {
    const big = ['| a | b |', '|---|---|', ...Array.from({ length: 300 }, (_, i) => `| r${i} | v |`)].join('\n');
    const state = stateOf(big);
    const line = state.doc.line(150);
    const specs = livePreviewSpecs(state, new Set(), { from: line.from, to: line.to });
    expect(specs.length).toBe(3);
    expect(specs.every((s) => s.from >= line.from && s.to <= line.to)).toBe(true);
    expect(start).toBeGreaterThan(0);
  });
});
describe('live-preview parity with the shared rendered-fence fixture', () => {
  // Documented live-preview deviations from the server: only TOP-LEVEL, CLOSED fences become widgets.
  const EXPECTED_TO_DIFFER = {
    'inside a list item': [],
    'inside a blockquote': [],
    'unclosed fence at end of file': [],
  };
  it.each(fixture.documents.map((d) => [d.name, d]))('%s', (name, doc) => {
    const got = blockCandidates(stateOf(doc.markdown))
      .filter((c) => c.widget.type === 'fence')
      .map((c) => ({ language: c.widget.language, source: c.widget.source }));
    expect(got).toEqual(name in EXPECTED_TO_DIFFER ? EXPECTED_TO_DIFFER[name] : doc.fences);
  });
  it('the deviations are real: the server renders those fences', () => {
    for (const name of Object.keys(EXPECTED_TO_DIFFER)) {
      expect(fixture.documents.find((d) => d.name === name).fences).toHaveLength(1);
    }
  });
});

describe('block markers', () => {
  const DUP_TITLE = 'Duplicate block id — only the first ^dup is linkable';
  const markers = (state, specs) => specs.filter((s) => s.kind === 'mark' && s.cls.startsWith('cm-lp-blockid'))
    .map((s) => [s.cls, state.sliceDoc(s.from, s.to), s.title ?? null]);

  it('marks an inline marker off the active line, and leaves it raw on the active line', () => {
    const doc = 'A paragraph ^abc\n\nOther';
    const off = specsOf(doc, [3]);
    expect(markers(off.state, off.specs)).toEqual([['cm-lp-blockid', '^abc', null]]);
    const on = specsOf(doc, [1]);
    expect(markers(on.state, on.specs)).toEqual([]);
  });

  it('marks standalone, last-line, list-item and quoted markers; never code, math, headings or escapes', () => {
    const doc = [
      '| a |', '|---|', '| 1 |', '', '^tbl', '',            // 1-6: standalone after a table
      'two', 'lines', '^last', '',                         // 7-10: last-line marker
      '- item ^li', '', '> quoted ^qq', '',                // 11-14
      '## Heading ^hh', '', '```', 'code ^cc', '```', '',  // 15-20
      'Uses `code ^ic`', '', '$$', 'x ^mm', '$$', '',      // 21-26
      'escaped \\^esc', '', 'tight^no', '',                // 27-30
      '> [!note] Title ^ct', '> body ^cb', '',            // 31-33: a callout title never holds one
      '- [ ] a task ^tk',                                  // 34: a task item (a Task node, not a Paragraph)
    ].join('\n');
    const { state, specs } = specsOf(doc, []);
    expect(markers(state, specs).map((m) => m[1])).toEqual(['^tbl', '^last', '^li', '^qq', '^cb', '^tk']);
    const ids = blocksFromMarkdown(doc).filter((b) => b.id).map((b) => `^${b.id}`);
    expect(ids).toEqual(['^tbl', '^last', '^li', '^qq', '^cb', '^tk']);   // what the server model reads
  });

  it('flags a later duplicate (by marker position, the first wins) with a warning class and title', () => {
    const doc = 'First ^dup\n\nSecond ^dup\n\n^dup\n\nOther ^ok';
    const { state, specs } = specsOf(doc, []);
    expect(markers(state, specs)).toEqual([
      ['cm-lp-blockid', '^dup', null],
      ['cm-lp-blockid-dup', '^dup', DUP_TITLE],
      ['cm-lp-blockid-dup', '^dup', DUP_TITLE],
      ['cm-lp-blockid', '^ok', null],
    ]);
  });

  it('judges a duplicate against the whole document even when only a later range is scanned', () => {
    const doc = `First ^dup\n\n${'filler\n\n'.repeat(20)}Again ^dup`;
    const state = stateOf(doc);
    const last = state.doc.line(state.doc.lines);
    const specs = livePreviewSpecs(state, new Set(), { from: last.from, to: last.to });
    expect(markers(state, specs)).toEqual([['cm-lp-blockid-dup', '^dup', 'Duplicate block id — only the first ^dup is linkable']]);
  });

  it('judging duplicates on a page of markers and comments costs linear time, every keystroke', () => {
    // Each keystroke is a new document, so the first-wins walk runs again from the top: asking of each marker whether
    // it lies in a comment must not cost time in the number of comments.
    const make = (n) => {
      let doc = '';
      for (let i = 0; i < n; i += 1) doc += `Para ${i} ^b${i}\n\nx %%c${i}%% y\n\n`;
      return stateOf(`${doc}Last ^b0`);
    };
    const keystroke = (state) => {
      const next = fullyParsed(state.update({ changes: { from: state.doc.length, insert: ' ' } }).state);
      const last = next.doc.line(next.doc.lines);
      return { state: next, specs: livePreviewSpecs(next, new Set(), { from: last.from, to: last.to }) };
    };
    const typed = keystroke(make(3));   // the walk does judge the last line: its ^b0 repeats the first marker
    expect(markers(typed.state, typed.specs)).toEqual([['cm-lp-blockid-dup', '^b0', 'Duplicate block id — only the first ^b0 is linkable']]);
    const { ratio, bound } = linearCost(make, keystroke, { small: 4000 });
    expect(ratio).toBeLessThan(bound);
  }, LINEAR_COST_TIMEOUT_MS);

  it('agrees with blocksFromMarkdown on which markers are duplicates', () => {
    const doc = 'a ^x\n\n- b ^x\n- c ^y\n\n> d\n>\n> ^y\n\ne ^z\n\n- [x] done ^z';
    const { specs } = specsOf(doc, []);
    const dupCarets = specs.filter((s) => s.cls === 'cm-lp-blockid-dup').map((s) => s.from);
    // the losers are the blocks with a marker range but no id
    const losers = blocksFromMarkdown(doc).filter((b) => b.markerFrom >= 0 && b.id == null).map((b) => doc.indexOf('^', b.markerFrom));
    expect(dupCarets).toEqual(losers);
  });
});

describe('block markers agree with the block model (blockMarkers: orphans and duplicates included)', () => {
  const chips = (doc) => {
    const state = stateOf(doc);
    return livePreviewSpecs(state, new Set(), {}).filter((s) => s.kind === 'mark' && s.cls.startsWith('cm-lp-blockid'))
      .map((s) => ({ id: state.sliceDoc(s.from + 1, s.to), caret: s.from, wins: s.cls === 'cm-lp-blockid' }));
  };
  it.each([
    ['an access rule with an inline marker is a standalone marker (here an orphan)', '[{ALLOW view All}] ^ra\n\nText ^ra\n'],
    ['an access rule with an inline marker tags the paragraph before it', 'Para\n\n[{ALLOW view Admin}] ^ar\n\nMore ^ar\n'],
    ['an access rule then a marker on the next line', 'Para\n\n[{ALLOW view Admin}]\n^n2\n\nMore ^n2\n'],
    ['a marker before an access rule is a standalone marker', 'Para\n\n^bx [{DENY edit Guest}]\n\nMore ^bx\n'],
    ['a marker on the line before an access rule', 'Para\n\n^by\n[{DENY edit Guest}]\n\nMore ^by\n'],
    ['an access rule with a marker in a quote', '> [{ALLOW view Admin}] ^q\n\nText ^q\n'],
    ['an access rule with a marker in a list item', '- item\n- [{ALLOW view Admin}] ^li\n\nText ^li\n'],
    ['an access rule and other text keep the inline marker', 'Para\n\n[{ALLOW view Admin}] text ^t\n\nMore ^t\n'],
    ['a plugin beside a marker is not an access rule', '[{TableOfContents}] ^p\n\nMore ^p\n'],
    ['a list item of only an access rule reads as empty', '- one\n  - [{ALLOW view Admin}]\n    ^mk\n  - three ^mk\n'],
    ['an access rule with a last-line marker', '[{ALLOW view All}]\n^rl\n'],
    ['a standalone after an access-rule-only paragraph is claimed across it', 'Para\n\n[{ALLOW view All}]\n\n^ac\n\nMore ^ac\n'],
    ['a standalone after only an access rule is an orphan', '[{DENY edit Guest}]\n\n^ro\n\nText ^ro\n'],
    ['an orphan wins over a later block marker', '^orph\n\nPara ^orph\n'],
    ['a second standalone is an orphan that loses', 'Para\n\n^x\n\n^x\n'],
    ['a callout body, a task and a nested list', '> [!tip] T\n> body ^c\n\n- [ ] task ^c\n  - nested ^n\n\n^n\n'],
    ['a marker then an access rule on its line', 'Para ^id [{ALLOW view Admin}]\n\nMore ^id\n'],
    ['a marker, then an access rule on the next line', 'Para ^id\n[{ALLOW view Admin}]\n\nMore ^id\n'],
    ['a list item marker with a rule on its continuation line', '- item ^id\n  [{ALLOW view Admin}]\n\nMore ^id\n'],
    ['a quote marker with a rule on the next quote line', '> quote ^q\n> [{ALLOW view Admin}]\n\nMore ^q\n'],
    ['a last-line marker before a rule', 'Para.\n^id [{DENY edit Guest}]\n\nMore ^id\n'],
    ['a last-line marker, the rule on the line after', 'Para.\n^id\n[{DENY edit Guest}]\n\nMore ^id\n'],
    ['other text after the rule keeps the caret literal', 'Para ^id [{ALLOW view Admin}] more\n\nMore ^id\n'],
    ['a vertical tab after a marker beside a rule is no marker', 'Para.\n\n[{ALLOW view Admin}] ^id\u000b\n\nAfter ^id\n'],
    ['a tab between a rule and a marker leaves code', 'Para.\n\n[{ALLOW view Admin}]\t^id\n\nAfter ^id\n'],
    ['four spaces between a rule and a marker leave code', 'Para.\n\n[{ALLOW view Admin}]    ^id\n\nAfter ^id\n'],
    ['an indented rule and two spaces leave code', 'Para.\n\n  [{ALLOW view Admin}]  ^id\n\nAfter ^id\n'],
    ['an indented marker after a rule line is code', 'Para.\n\n[{ALLOW view Admin}]\n    ^id\n\nAfter ^id\n'],
    ['two spaces after a rule keep the marker', 'Para.\n\n[{ALLOW view Admin}]  ^id\n\nAfter ^id\n'],
    ['markers between, inside and after several comments', '%%one%%\n\nA ^a\n\n%%\ntwo ^b\n%%\n\nB ^b\n\n%%three%% ok ^c\n\nC ^a\n'],
  ])('%s', (_name, doc) => {
    expect(chips(doc)).toEqual(blockMarkers(doc));
  });
});

describe('less common markdown (autolinks, bare URLs, escapes, setext, HTML blocks)', () => {
  const links = (state, specs) => specs.filter((s) => s.cls === 'cm-lp-link').map((s) => state.sliceDoc(s.from, s.to));
  const hidden = (state, specs) => specs.filter((s) => s.kind === 'hide').map((s) => state.sliceDoc(s.from, s.to));
  const lines = (state, specs) => specs.filter((s) => s.kind === 'line').map((s) => `${state.doc.lineAt(s.from).number}:${s.cls}`).sort();

  it('autolinks hide the angle brackets off the caret line and style the URL', () => {
    const { state, specs } = specsOf('<https://x.org> and <a@b.co>\n\nend', [3]);
    expect(hidden(state, specs)).toEqual(['<', '>', '<', '>']);
    expect(links(state, specs)).toEqual(['https://x.org', 'a@b.co']);
  });
  it('autolinks keep their brackets on the caret line but still style the URL', () => {
    const { state, specs } = specsOf('<https://x.org>\n\nend', [1]);
    expect(hidden(state, specs)).toEqual([]);
    expect(links(state, specs)).toEqual(['https://x.org']);
  });
  it('a bare URL is styled as a link, in either line state', () => {
    for (const active of [[1], [3]]) {
      const { state, specs } = specsOf('see https://x.org/a now\n\nend', active);
      expect(links(state, specs)).toEqual(['https://x.org/a']);
      expect(hidden(state, specs)).toEqual([]);
    }
  });
  it('a URL inside a link, image or autolink is not double-marked', () => {
    const { state, specs } = specsOf('[t](https://x.org) ![i](https://y.org) <https://z.org>\n\nend', [3]);
    expect(links(state, specs)).toEqual(['t', 'https://z.org']);
  });
  it('a backslash escape hides only its backslash (off the caret line)', () => {
    const { state, specs } = specsOf('\\*not em\\* and \\\\\n\nend', [3]);
    expect(specs.filter((s) => s.kind === 'hide').map((s) => [s.from, s.to])).toEqual([[0, 1], [8, 9], [15, 16]]);
    expect(visible(state, specs, 1)).toBe('*not em* and \\');
    expect(specs.some((s) => s.cls === 'cm-lp-em')).toBe(false);
  });
  it('an escape stays source on the caret line', () => {
    const { specs } = specsOf('\\*a\\*\n\nend', [1]);
    expect(specs.filter((s) => s.kind === 'hide')).toEqual([]);
  });
  it('setext headings: text lines get the heading class, the underline its own, nothing hidden', () => {
    for (const active of [[1], [5]]) {
      const { state, specs } = specsOf('Title\n=====\n\nSub\n---\n\nend', active);
      expect(lines(state, specs)).toEqual(['1:cm-lp-h1', '2:cm-lp-setext-underline', '4:cm-lp-h2', '5:cm-lp-setext-underline']);
      expect(hidden(state, specs)).toEqual([]);
    }
  });
  it('a two-line setext heading styles both text lines', () => {
    const { state, specs } = specsOf('One\nTwo\n===\n\nend', [5]);
    expect(lines(state, specs)).toEqual(['1:cm-lp-h1', '2:cm-lp-h1', '3:cm-lp-setext-underline']);
  });
  it('an HTML block marks every line and produces no other specs inside', () => {
    const { state, specs } = specsOf('<div>\n**x** [[A]]\n</div>\n\nend', [5]);
    expect(lines(state, specs)).toEqual(['1:cm-lp-html', '2:cm-lp-html', '3:cm-lp-html']);
    expect(specs.filter((s) => s.kind !== 'line')).toEqual([]);
  });
  it('an inactive line with three rules yields exactly three chips', () => {
    const { specs } = specsOf('[{ALLOW view A}][{ALLOW edit B}] x [{$v}]\n\nend', [3]);
    expect(specs.filter((s) => s.widget?.type === 'chip')).toHaveLength(3);
  });
});

describe('reference-style links and footnotes', () => {
  it('decorates [Text][Ref] when a definition exists anywhere below', () => {
    const doc = `see [Text][Ref] here\n${'filler\n\n'.repeat(1500)}[ref]: https://x\n`;
    const { state, specs } = specsOf(doc, [2]);
    expect(visible(state, specs, 1)).toBe('see Text here');
    expect(show(state, specs)).toContain('mark cm-lp-link "Text"');
  });
  it('decorates [Text][] and shortcut [Text] against a case-insensitive definition', () => {
    const a = specsOf('[Text][] and [Other]\n\n[text]: u\n[OTHER]: v\n', [3]);
    expect(visible(a.state, a.specs, 1)).toBe('Text and Other');
  });
  it('leaves source untouched without a definition', () => {
    const { specs } = specsOf('[Text][Ref] and [Short]\n\n[other]: u\n', [3]);
    expect(specs.filter((s) => s.kind !== 'line')).toEqual([]);
  });
  it('definition lines get cm-lp-linkdef and nothing is hidden', () => {
    const { state, specs } = specsOf('x\n\n[ref]: https://x\n', [1]);
    expect(show(state, specs)).toEqual(['line cm-lp-linkdef @3']);
  });
  it('typing a definition decorates a previously plain reference', () => {
    const before = specsOf('[Text][ref]\n\n', [2]);
    expect(before.specs).toEqual([]);
    const after = specsOf('[Text][ref]\n\n[ref]: u\n', [2]);
    expect(visible(after.state, after.specs, 1)).toBe('Text');
  });
  it('footnote reference becomes a chip, never a link', () => {
    const { state, specs } = specsOf('word[^1] more\n\n[^1]: note\n[1]: u\n', [2]);
    const w = specs.filter((s) => s.kind === 'widget');
    expect(w[0].widget).toMatchObject({ type: 'chip', text: '1', cls: 'cm-lp-chip cm-lp-footnote-ref' });
    expect(state.sliceDoc(w[0].from, w[0].to)).toBe('[^1]');
    expect(show(state, specs)).toEqual(['widget chip "[^1]"', 'widget chip "[^1]:"', 'line cm-lp-linkdef @4']);
  });
  it('footnote definition label becomes a chip, with no linkdef class', () => {
    const { state, specs } = specsOf('x[^note]\n\n[^note]: Some text\n', [1]);
    const w = specs.filter((s) => s.kind === 'widget' && s.widget.cls?.includes('footnote-def'));
    expect(w).toHaveLength(1);
    expect(w[0].widget).toMatchObject({ type: 'chip', text: 'note:', cls: 'cm-lp-chip cm-lp-footnote-def' });
    expect(state.sliceDoc(w[0].from, w[0].to)).toBe('[^note]:');
    expect(show(state, specs)).not.toContain('line cm-lp-linkdef @3');
  });
  it('referenceLabels excludes footnote labels', async () => {
    const { referenceLabels } = await import('./ranges');
    const state = stateOf('[^note]: x\n\n[Ref  One]: u\n[^a]: y\n');
    expect([...referenceLabels(state)]).toEqual(['ref one']);
  });
  it('wikilink block refs and block markers are not footnotes', () => {
    const { specs } = specsOf('see [[#^abc]]\n\ntext ^abc\n', [2]);
    expect(specs.filter((s) => s.widget?.cls?.includes('footnote'))).toEqual([]);
  });
  it('nothing in inline code or fences', () => {
    const { specs } = specsOf('`a[^1]` x\n\n```\n[^2]\n[^3]: z\n```\n', [2]);
    expect(specs.filter((s) => s.kind === 'widget' && s.widget.type !== 'fence-header')).toEqual([]);
  });
  it('an escaped footnote reference stays literal text, as the reader shows it', () => {
    const { state, specs } = specsOf('word\\[^1] more\n\n[^1]: note\n', [2]);
    expect(specs.filter((s) => s.widget?.cls?.includes('footnote-ref'))).toEqual([]);
    expect(visible(state, specs, 1)).toBe('word[^1] more');
  });
  it('a callout-looking reference link with a label still links when the label is defined', () => {
    const { state, specs } = specsOf('see [!x][r] here\n\n[r]: u\n', [2]);
    expect(visible(state, specs, 1)).toBe('see !x here');
    expect(show(state, specs)).toContain('mark cm-lp-link "!x"');
  });
  it('a shortcut [!x] is never a reference link, even when defined', () => {
    const { specs } = specsOf('see [!x] here\n\n[!x]: u\n', [2]);
    expect(specs.filter((s) => s.cls === 'cm-lp-link')).toEqual([]);
  });
  it('active line keeps footnote source', () => {
    const { specs } = specsOf('word[^1]\n', [1]);
    expect(specs.filter((s) => s.kind === 'widget')).toEqual([]);
  });
});

describe('footnote definitions Lezer reads as LinkReference', () => {
  const defs = (doc) => {
    const { state, specs } = specsOf(`${doc}\n\nz\n`, [doc.split('\n').length + 2]);
    return { state, specs, w: specs.filter((x) => x.widget?.cls?.includes('footnote-def')) };
  };
  for (const [name, doc, label] of [
    ['a URL', '[^1]: https://x.org', '[^1]:'],
    ['a single word', '[^1]: word', '[^1]:'],
    ['an indented continuation', '[^1]:\n    indented para', '[^1]:'],
    ['an indented label', '  [^n]: ind', '[^n]:'],
  ]) {
    it(`chips ${name}`, () => {
      const { state, specs, w } = defs(doc);
      expect(w).toHaveLength(1);
      expect(state.sliceDoc(w[0].from, w[0].to)).toBe(label);
      expect(show(state, specs).filter((x) => x.includes('linkdef'))).toEqual([]);
    });
  }
  it('chips a list-item footnote definition as a definition', () => {
    const { w } = defs('- [^n]: text');
    expect(w).toHaveLength(1);
  });
  it('chips a blockquote footnote definition as a definition', () => {
    const { w } = defs('> [^n]: text');
    expect(w).toHaveLength(1);
  });
});
describe('callout markers are never reference links', () => {
  it('leaves > [!NOTE] undecorated even with a [!note] definition', () => {
    const { state, specs } = specsOf('> [!NOTE]\n> body\n\n[!note]: u\n\nz\n', [6]);
    expect(show(state, specs).filter((x) => x.includes('cm-lp-link"'))).toEqual([]);
    expect(specs.some((x) => x.kind === 'hide' && state.sliceDoc(x.from, x.to) === '[')).toBe(false);
  });
});
describe('overlap regressions (bare URL, escaped wikilink)', () => {
  it('a bare URL inside a wikilink or plugin chip yields no overlapping replacing specs', () => {
    for (const doc of ['[[https://x.org]]\n', '[{INSERT https://x.org/a}]\n']) {
      const { specs } = specsOf(`${doc}\nz\n`, [3]);
      const r = replacing(specs).sort((a, b) => a.from - b.from);
      for (let i = 1; i < r.length; i += 1) expect(r[i].from).toBeGreaterThanOrEqual(r[i - 1].to);
    }
    const chip = specsOf('[{INSERT https://x.org/a}]\n\nz\n', [3]);
    expect(chip.specs.filter((s) => s.kind !== 'line').map((s) => s.kind)).toEqual(['widget']);
  });
  it('\\[[A]] hides only the backslash', () => {
    const { state, specs } = specsOf('\\[[A]]\n\nz\n', [3]);
    expect(show(state, specs)).toEqual(['hide "\\\\"']);
  });
});
describe('block markers stay hidden on the caret line (sticky)', () => {
  /** The sticky specs of line `n` with the caret on it, as `kind[ widget] "text"`. */
  const stickyOn = (doc, n = 1) => {
    const { state, specs } = specsOf(doc, [n]);
    const line = state.doc.line(n);
    return specs.filter((s) => s.sticky && s.from >= line.from && s.to <= line.to)
      .map((s) => `${s.kind}${s.widget ? ` ${s.widget.type}` : ''} ${JSON.stringify(state.sliceDoc(s.from, s.to))}`);
  };
  it.each([
    ['## Title', ['hide "## "']],
    ['#', ['hide "#"']],
    [' ## Indented', ['hide "## "']],
    ['- item', ['widget bullet "- "']],
    ['-', ['widget bullet "-"']],
    ['* star', ['widget bullet "* "']],
    ['1. item', ['mark "1. "']],
    ['12) item', ['mark "12) "']],
    ['- [ ] task', ['widget checkbox "- [ ] "']],
    ['1. [x] task', ['widget checkbox "1. [x] "']],
    ['> quote', ['hide "> "']],
    ['>bare', ['hide ">"']],
    ['> > deep', ['hide "> "', 'hide "> "']],
    ['> - [ ] x', ['hide "> "', 'widget checkbox "- [ ] "']],
    ['> ## t', ['hide "> "', 'hide "## "']],
    ['- > x', ['widget bullet "- "', 'hide "> "']],
    ['- ## t', ['widget bullet "- "', 'hide "## "']],
  ])('%j', (doc, expected) => {
    expect(stickyOn(doc)).toEqual(expected);
  });
  it('a nested item: only the marker and its space are sticky, never the indentation', () => {
    const doc = '- one\n  - nested\n    1. deep';
    expect(stickyOn(doc, 2)).toEqual(['widget bullet "- "']);
    expect(stickyOn(doc, 3)).toEqual(['mark "1. "']);
    const { state, specs } = specsOf(doc, [2]);
    expect(visible(state, specs, 2)).toBe('  [bullet]nested');
  });
  it('only one space after the marker belongs to it', () => {
    expect(stickyOn('-   wide')).toEqual(['widget bullet "- "']);
    expect(stickyOn('##   wide')).toEqual(['hide "## "']);
  });
  it('the caret line reads the same as any other line', () => {
    const doc = '## Title\n- item\n1. first\n- [x] done\n> quote\n\nend';
    const { state, specs } = specsOf(doc, [1, 2, 3, 4, 5]);
    expect([1, 2, 3, 4, 5].map((n) => visible(state, specs, n))).toEqual(['Title', '[bullet]item', '1. first', '[checkbox]done', 'quote']);
    const off = specsOf(doc, [7]);
    expect(replacing(off.specs)).toEqual(replacing(specs));
  });
  it('an ordered number is shown as text: a sticky mark, nothing hidden', () => {
    const { state, specs } = specsOf('1. first', [1]);
    expect(show(state, specs)).toEqual(['mark cm-lp-olmark "1. "']);
  });
  it('every quote line of a multi-line quote keeps its mark hidden; a lazy line has none', () => {
    const doc = '> a\n> b\nlazy';
    expect(stickyOn(doc, 2)).toEqual(['hide "> "']);
    expect(stickyOn(doc, 3)).toEqual([]);
  });
  it('a setext underline keeps reveal-at-caret (nothing sticky, nothing hidden)', () => {
    const { specs } = specsOf('Title\n=====\n\nend', [1, 2]);
    expect(specs.filter((s) => s.sticky)).toEqual([]);
    expect(replacing(specs)).toEqual([]);
  });
  it.each([
    ['a footnote definition in a list', '- [^n]: text'],
    ['a footnote definition in a quote', '> [^n]: text'],
    ['a footnote definition in a numbered list', '1. [^n]: text'],
    ['a table in a quote', '> | a | b |\n> |---|---|\n> | 1 | 2 |'],
    ['a table in a list', '- | a | b |\n  |---|---|\n  | 1 | 2 |'],
    ['a table in a list in a quote', '> - | a | b |\n>   |---|---|\n>   | 1 | 2 |'],
  ])('%s keeps reveal-at-caret: no sticky marker, and source on the caret line', (_name, doc) => {
    const { state, specs } = specsOf(`${doc}\n\nend`, [1]);
    expect(specs.filter((s) => s.sticky)).toEqual([]);
    expect(visible(state, specs, 1)).toBe(doc.split('\n')[0]);
    const off = specsOf(`${doc}\n\nend`, [doc.split('\n').length + 2]);
    expect(off.specs.filter((s) => s.sticky)).toEqual([]);
  });
});

describe('links, wikilinks, embeds and images are chips on the caret line', () => {
  const ctx = { pageName: 'Here', attachments: ['pic.png'] };
  const at = (doc, caret) => {
    const state = stateOf(doc, EditorSelection.cursor(caret));
    return { state, specs: livePreviewSpecs(state, activeLinesOf(state), { context: ctx }) };
  };
  const sticky = (state, specs) => specs.filter((s) => s.sticky).map((s) => `${s.kind} ${state.sliceDoc(s.from, s.to)}`);
  it.each([
    ['x [[Page|a]] y', 'x a y', 'mark [[Page|a]]'],
    ['x [[Page#Setup]] y', 'x Page > Setup y', 'mark [[Page#Setup]]'],
    ['x ![[Other]] y', 'x Other y', 'mark ![[Other]]'],
    ['x [text](https://e.org "t") y', 'x text y', 'mark [text](https://e.org "t")'],
    ['x ![alt](pic.png) y', 'x [image] y', 'widget ![alt](pic.png)'],
    ['x ![[pic.png|300]] y', 'x [image] y', 'widget ![[pic.png|300]]'],
  ])('%s with the caret at the line start reads as %s, one sticky range', (doc, shown, range) => {
    const { state, specs } = at(doc, 0);
    expect(visible(state, specs, 1)).toBe(shown);
    expect(sticky(state, specs)).toEqual([range]);
  });
  it('is still a chip with the caret at either end of it', () => {
    for (const caret of [2, 12]) {
      const { state, specs } = at('x [[Page|a]] y', caret);
      expect(visible(state, specs, 1)).toBe('x a y');
    }
  });
  it.each([
    ['x [[Page|a]] y', 5], ['x [text](url) y', 4], ['x ![alt](pic.png) y', 6], ['x ![[pic.png]] y', 7],
  ])('%s shows its source while a caret is strictly inside it (it is being typed)', (doc, caret) => {
    const { state, specs } = at(doc, caret);
    expect(visible(state, specs, 1)).toBe(doc);
    expect(sticky(state, specs)).toEqual([]);
  });
  it.each([[1, 13], [2, 12], [12, 2]])('a selection %i..%i covering a chip does not reveal it', (anchor, head) => {
    const state = stateOf('x [[Page|a]] y', EditorSelection.range(anchor, head));
    const specs = livePreviewSpecs(state, activeLinesOf(state), { context: ctx });
    expect(visible(state, specs, 1)).toBe('x a y');
  });
  it.each([[1, 6], [6, 13], [4, 8], [8, 4]])('a selection %i..%i with an end inside a link shows its source (a find match in a hidden target)', (anchor, head) => {
    const state = stateOf('x [[Page|a]] y', EditorSelection.range(anchor, head));
    const specs = livePreviewSpecs(state, activeLinesOf(state), { context: ctx });
    expect(visible(state, specs, 1)).toBe('x [[Page|a]] y');
    expect(specs.filter((sp) => sp.sticky)).toEqual([]);
  });
  it('caretsInLinks names the links a caret or a selection end is inside, and nothing else', () => {
    const doc = 'x [[Page|a]] y [t](u) z';
    const key = (selection) => caretsInLinks(stateOf(doc, selection));
    expect(key(EditorSelection.cursor(0))).toBe('');
    expect(key(EditorSelection.cursor(2))).toBe('');
    expect(key(EditorSelection.cursor(5))).toBe('2,');
    expect(key(EditorSelection.range(1, 13))).toBe('');
    expect(key(EditorSelection.range(1, 6))).toBe('2,');
    expect(key(EditorSelection.range(6, 18))).toBe('2,15,');
  });
  it('a reference-style link keeps reveal-at-caret (the "expensive" set)', () => {
    const doc = 'x [text][ref] y\n\n[ref]: https://e.org\n\nend';
    const active = at(doc, 0);
    expect(visible(active.state, active.specs, 1)).toBe('x [text][ref] y');
    expect(sticky(active.state, active.specs)).toEqual([]);
    const away = at(doc, doc.length);
    expect(visible(away.state, away.specs, 1)).toBe('x text y');
  });
  it('a link with no text keeps reveal-at-caret: a chip nobody could see', () => {
    const { state, specs } = at('x [](https://e.org) y', 0);
    expect(visible(state, specs, 1)).toBe('x [](https://e.org) y');
    expect(sticky(state, specs)).toEqual([]);
  });
  it('a link whose text holds an image: the link is one sticky range and the image is drawn inside it', () => {
    const { state, specs } = at('x [![alt](pic.png)](https://e.org) y', 0);
    expect(visible(state, specs, 1)).toBe('x [image] y');
    expect(sticky(state, specs)).toEqual(['mark [![alt](pic.png)](https://e.org)', 'widget ![alt](pic.png)']);
  });
  it('a whole-line page embed is a chip once the caret is on its line (off it, the block widget)', () => {
    const doc = '![[Other]]\n\nend';
    const on = at(doc, 0);
    expect(visible(on.state, on.specs, 1)).toBe('Other');
    expect(sticky(on.state, on.specs)).toEqual(['mark ![[Other]]']);
    const off = at(doc, doc.length);
    expect(off.specs.filter((s) => s.from < 10 && s.kind !== 'line')).toEqual([]);
  });
});
