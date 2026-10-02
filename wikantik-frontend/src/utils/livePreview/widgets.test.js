import { describe, it, expect, vi, afterEach } from 'vitest';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { history, undo } from '@codemirror/commands';
import {
  BulletWidget, CheckboxWidget, ImageWidget, MathWidget, EmbedWidget, TextWidget, CalloutTitleWidget, RuleWidget,
  widgetFor, toggleTaskAt, toggleTaskBox,
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
    expect(new EmbedWidget('A', 'S', () => {}).eq(new EmbedWidget('A', null, () => {}))).toBe(false);
    expect(new ImageWidget({ src: 'a', alt: '' }).eq(new ImageWidget({ src: 'a', alt: '', width: 3 }))).toBe(false);
    expect(new ImageWidget({ src: 'a', alt: '' }).eq(new ImageWidget({ src: 'a', alt: '' }))).toBe(true);
    expect(new TextWidget('a', 'c').eq(new TextWidget('a', 'd'))).toBe(false);
    expect(new CalloutTitleWidget('note', 'T').eq(new CalloutTitleWidget('note', 'T'))).toBe(true);
    expect(new BulletWidget().eq(new RuleWidget())).toBe(false);
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
  it('checkbox mousedown toggles the task in the document', () => {
    const view = viewOf('- [ ] a');
    const box = new CheckboxWidget(false).toDOM(view);
    vi.spyOn(view, 'posAtDOM').mockReturnValue(0);
    box.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    expect(view.state.doc.toString()).toBe('- [x] a');
    expect(new CheckboxWidget(false).ignoreEvent()).toBe(true);
  });
  it('checkbox toggles from the keyboard (Space/Enter) via the same transaction', () => {
    const view = viewOf('- [ ] a');
    const box = new CheckboxWidget(false).toDOM(view);
    vi.spyOn(view, 'posAtDOM').mockReturnValue(0);
    const space = new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true });
    box.dispatchEvent(space);
    expect(view.state.doc.toString()).toBe('- [x] a');
    expect(space.defaultPrevented).toBe(true);
    box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    expect(view.state.doc.toString()).toBe('- [ ] a');
    box.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', bubbles: true, cancelable: true }));
    expect(view.state.doc.toString()).toBe('- [ ] a');
  });
  it('image: asks the view to re-measure once the image loads', () => {
    const view = { requestMeasure: vi.fn() };
    const wrap = new ImageWidget({ src: '/a.png', alt: 'A' }).toDOM(view);
    expect(view.requestMeasure).not.toHaveBeenCalled();
    wrap.querySelector('img').dispatchEvent(new Event('load'));
    expect(view.requestMeasure).toHaveBeenCalledTimes(1);
  });
  it.each([
    [{ state: 'ok', html: '<p>B</p>' }],
    [{ state: 'missing' }],
    [{ state: 'error' }],
  ])('embed: asks the view to re-measure after the body is filled (%o)', async (result) => {
    const view = { requestMeasure: vi.fn(), posAtDOM: () => 0 };
    new EmbedWidget('Other', null, () => Promise.resolve(result)).toDOM(view);
    expect(view.requestMeasure).not.toHaveBeenCalled();
    await flush();
    expect(view.requestMeasure).toHaveBeenCalledTimes(1);
  });
  it('embed: re-measures after a rejected load too', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const view = { requestMeasure: vi.fn(), posAtDOM: () => 0 };
    new EmbedWidget('Other', null, () => Promise.reject(new Error('down'))).toDOM(view);
    await flush();
    expect(view.requestMeasure).toHaveBeenCalledTimes(1);
  });
  it('embed Mod-click opens the slugified heading anchor, like a wikilink', () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null);
    const dom = new EmbedWidget('Other Page', 'Raw Heading', () => Promise.resolve({ state: 'missing' })).toDOM(viewOf('x'));
    dom.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, ctrlKey: true }));
    expect(open).toHaveBeenCalledWith('/wiki/Other%20Page#raw-heading', '_blank', 'noopener');
  });
  it('checkbox eq ignores the source offset, so typing above does not rebuild every box', () => {
    expect(new CheckboxWidget(true, 3).eq(new CheckboxWidget(true, 40))).toBe(true);
  });
  it('checkbox updateDOM syncs checked + label in place', () => {
    const view = viewOf('- [ ] write the report');
    const w = new CheckboxWidget(false, 2, 'write the report');
    const box = w.toDOM(view);
    expect(box.getAttribute('aria-label')).toBe('Mark task done: write the report');
    const next = new CheckboxWidget(true, 2, 'write the report');
    expect(next.updateDOM(box)).toBe(true);
    expect(box.checked).toBe(true);
    expect(box.getAttribute('aria-label')).toBe('Mark task not done: write the report');
  });
  it('checkbox toggles at the position at event time, not the stale build-time offset', () => {
    const view = viewOf('- [ ] a');
    const box = new CheckboxWidget(false, 999).toDOM(view);
    vi.spyOn(view, 'posAtDOM').mockReturnValue(0);
    box.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    expect(view.state.doc.toString()).toBe('- [x] a');
  });
  it('toggleTaskBox refuses when the brackets do not surround the box', () => {
    const view = viewOf('- [ a');
    expect(toggleTaskBox(view, 2)).toBe(false);
    expect(view.state.doc.toString()).toBe('- [ a');
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
