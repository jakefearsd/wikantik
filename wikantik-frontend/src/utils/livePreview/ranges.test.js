import { describe, it, expect } from 'vitest';
import { EditorState, EditorSelection } from '@codemirror/state';
import { markdown } from '@codemirror/lang-markdown';
import fixture from '../__fixtures__/renderedFences.json';
import { fullyParsed } from '../../test/fullParse';
import { editorMarkdownConfig } from '../editorMarkdown';
import { blocksFromMarkdown, blockMarkers } from '../blockIds';
import { blockCandidates, activeLinesOf, livePreviewSpecs, resolveImageSrc, attachmentUrl, blockSpecs, parseWikiTarget } from './ranges';
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
    const doc = '---\ntitle: x\n---\n\n| a | **b** |\n|---|---|\n| 1 | 2 |\n\n<div>**x**</div>\n\nend';
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
  const TABLE = '| a | b |\n|:--|--:|\n| 1 | 2 |\n| 3 | 4 |';
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
  it('the inline pass never decorates inside a table', () => {
    const doc = `${TABLE.replace('| 1 |', '| **b** [[P]] $x$ |')}\n\nend`;
    const { specs } = specsOf(doc, [99]);
    expect(specs.filter((s) => s.from < doc.indexOf('\n\nend'))).toEqual([]);
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
    expect(specs.filter((s) => s.kind === 'widget')).toEqual([]);
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
