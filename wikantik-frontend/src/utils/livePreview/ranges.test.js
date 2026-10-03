import { describe, it, expect } from 'vitest';
import { EditorState, EditorSelection } from '@codemirror/state';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { editorMarkdownConfig } from '../editorMarkdown';
import { activeLinesOf, livePreviewSpecs, resolveImageSrc, attachmentUrl, blockSpecs, parseWikiTarget } from './ranges';
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
  it('leaves reference, shortcut and callout-marker brackets as source', () => {
    const { state, specs } = specsOf('[x][r] and [y]\n\n[r]: http://a.example\n\nend', [5]);
    expect(visible(state, specs, 1)).toBe('[x][r] and [y]');
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
  it('tables, HTML and frontmatter stay untouched source', () => {
    const doc = '---\ntitle: x\n---\n\n| a | **b** |\n|---|---|\n| 1 | 2 |\n\n<div>**x**</div>\n\nend';
    const { specs } = specsOf(doc, [11]);
    expect(specs).toEqual([]);
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
