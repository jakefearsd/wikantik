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
