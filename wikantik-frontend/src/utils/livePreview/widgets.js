import { WidgetType } from '@codemirror/view';
import { isolateHistory } from '@codemirror/commands';
import katex from 'katex';
import 'katex/dist/katex.min.css';
import { wikiLinkHref } from '../wikiLinkSyntax';
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

export class RuleWidget extends WidgetType {
  eq(other) { return other instanceof RuleWidget; }
  toDOM() { return el('span', 'cm-lp-rule'); }
  ignoreEvent() { return false; }
}

export class TextWidget extends WidgetType {
  constructor(text, cls) { super(); this.text = text; this.cls = cls; }
  eq(other) { return other instanceof TextWidget && other.text === this.text && other.cls === this.cls; }
  toDOM() { return el('span', this.cls, this.text); }
  ignoreEvent() { return false; }
}

export class CalloutTitleWidget extends WidgetType {
  constructor(style, title) { super(); this.style = style; this.title = title; }
  eq(other) { return other instanceof CalloutTitleWidget && other.style === this.style && other.title === this.title; }
  toDOM() {
    const wrap = el('span', 'cm-lp-callout-title-widget');
    const icon = el('span', 'cm-lp-callout-icon');
    icon.setAttribute('aria-hidden', 'true');
    wrap.append(icon, document.createTextNode(this.title || ''));
    return wrap;
  }
  ignoreEvent() { return false; }
}

export class ImageWidget extends WidgetType {
  constructor({ src, alt = '', width, height }) {
    super();
    this.src = src; this.alt = alt; this.width = width; this.height = height;
  }
  eq(o) {
    return o instanceof ImageWidget && o.src === this.src && o.alt === this.alt
      && o.width === this.width && o.height === this.height;
  }
  toDOM(view) {
    const wrap = el('span', 'cm-lp-image-wrap');
    const img = el('img', 'cm-lp-image');
    img.setAttribute('src', this.src);
    img.setAttribute('alt', this.alt);
    if (this.width != null) img.setAttribute('width', String(this.width));
    if (this.height != null) img.setAttribute('height', String(this.height));
    img.addEventListener('load', () => view?.requestMeasure?.()); // the real height is known now
    img.addEventListener('error', () => {
      console.warn('[live-preview] image failed to load', this.src);
      wrap.classList.add('cm-lp-image-missing');
      wrap.textContent = this.alt || this.src;
      view?.requestMeasure?.();
    });
    wrap.appendChild(img);
    return wrap;
  }
  ignoreEvent() { return false; }
}

const TASK_AT = /^(?:[-*+]|\d+[.)])[ \t]+\[([ xX])\]/;

/** Flip the task box whose `[ ]`/`[x]` starts at markerFrom, as one isolated undo step. */
export function toggleTaskBox(view, markerFrom) {
  const at = markerFrom + 1;
  const ch = view.state.sliceDoc(at, at + 1);
  if (view.state.sliceDoc(markerFrom, markerFrom + 1) !== '[' || view.state.sliceDoc(at + 1, at + 2) !== ']') return false;
  if (ch !== ' ' && ch !== 'x' && ch !== 'X') return false;
  view.dispatch({
    changes: { from: at, to: at + 1, insert: ch === ' ' ? 'x' : ' ' },
    annotations: isolateHistory.of('full'),
    userEvent: 'input.toggle-task',
  });
  return true;
}

/** Flip the task box of the list item whose marker starts at pos, as one isolated undo step. */
export function toggleTaskAt(view, pos) {
  const m = TASK_AT.exec(view.state.sliceDoc(pos, view.state.doc.lineAt(pos).to));
  if (!m) return false;
  const at = pos + m[0].length - 2;
  view.dispatch({
    changes: { from: at, to: at + 1, insert: m[1] === ' ' ? 'x' : ' ' },
    annotations: isolateHistory.of('full'),
    userEvent: 'input.toggle-task',
  });
  return true;
}

const taskLabel = (checked, text) => `${checked ? 'Mark task not done' : 'Mark task done'}${text ? `: ${text}` : ''}`;

export class CheckboxWidget extends WidgetType {
  // markerFrom is build-time only: the toggle position is resolved from the DOM at event time, so the
  // widget stays equal (and is not rebuilt) when text above it shifts every offset.
  constructor(checked, markerFrom, text = '') { super(); this.checked = checked; this.markerFrom = markerFrom; this.text = text; }
  eq(other) { return other instanceof CheckboxWidget && other.checked === this.checked && other.text === this.text; }
  updateDOM(dom) { // keeps the same <input> (and its keyboard focus) across a toggle
    dom.checked = this.checked;
    dom.setAttribute('aria-label', taskLabel(this.checked, this.text));
    return true;
  }
  toDOM(view) {
    const box = el('input', 'cm-lp-task');
    box.type = 'checkbox';
    box.checked = this.checked;
    box.setAttribute('aria-label', taskLabel(this.checked, this.text));
    const toggle = () => {
      try {
        toggleTaskAt(view, view.posAtDOM(box));
      } catch (err) {
        console.warn('[live-preview] task toggle failed', err?.message || err);
      }
    };
    box.addEventListener('mousedown', (e) => {
      e.preventDefault(); // keep editor focus; the document edit re-renders the box
      toggle();
    });
    box.addEventListener('keydown', (e) => { // keyboard parity: Space/Enter toggle through the same transaction
      if (e.key !== ' ' && e.key !== 'Enter') return;
      e.preventDefault();
      toggle();
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
    if (a) {
      if (e.ctrlKey || e.metaKey) window.open(a.href, '_blank', 'noopener');
      return;
    }
    try {
      view.dispatch({ selection: { anchor: view.posAtDOM(dom) } });
      view.focus();
    } catch (err) {
      console.warn('[live-preview] could not place the caret at a block widget', err?.message || err);
    }
  });
}

const MATH_CACHE_MAX = 500;
const mathCache = new Map(); // `${D|I}${tex}` -> { fragment } | { error }; insertion order = LRU order

/**
 * KaTeX output for `tex`, rendered once and cloned on every later use: scrolling re-creates the widgets of
 * every line that re-enters the viewport, and a caret leaving a line re-creates its inline math.
 */
function renderedMath(tex, display) {
  const key = `${display ? 'D' : 'I'}${tex}`;
  let hit = mathCache.get(key);
  if (hit) {
    mathCache.delete(key); // refresh its LRU position
  } else {
    try {
      // renderToString (not render): same output, and it is not disabled by KaTeX's quirks-mode guard
      // (which also makes the widget testable under happy-dom). The markup is generated by KaTeX itself.
      const template = document.createElement('template');
      template.innerHTML = katex.renderToString(tex, { displayMode: display, throwOnError: true });
      hit = { fragment: template.content };
    } catch (err) {
      hit = { error: err?.message || 'Invalid TeX' };
    }
    if (mathCache.size >= MATH_CACHE_MAX) mathCache.delete(mathCache.keys().next().value);
  }
  mathCache.set(key, hit);
  return hit;
}

export class MathWidget extends WidgetType {
  constructor(tex, display) { super(); this.tex = tex; this.display = display; }
  eq(other) { return other instanceof MathWidget && other.tex === this.tex && other.display === this.display; }
  toDOM(view) {
    const node = el(this.display ? 'div' : 'span', this.display ? 'cm-lp-math cm-lp-math-block' : 'cm-lp-math');
    const math = renderedMath(this.tex, this.display);
    if (math.fragment) {
      node.appendChild(math.fragment.cloneNode(true));
    } else { // invalid TeX: show the source, explain on hover
      node.classList.add('cm-lp-math-error');
      node.textContent = this.display ? `$$\n${this.tex}\n$$` : `$${this.tex}$`;
      node.title = math.error;
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
    const load = this.load
      ? Promise.resolve().then(() => this.load(this.target, this.section))
      : Promise.reject(new Error('no embed loader configured'));
    load.then((r) => {
      if (r?.state === 'ok') {
        body.className = 'cm-lp-embed-body article-prose';
        body.innerHTML = r.html || ''; // server-rendered, view-ACL'd HTML — what the preview's WikiEmbed shows
        renderMath(body);
      } else if (r?.state === 'missing') show('cm-lp-embed-missing', `"${this.target}" does not exist yet.`);
      else if (r?.state === 'restricted') show('cm-lp-embed-restricted', "You don't have access to this page.");
      else show('cm-lp-embed-error', 'Could not load this embed.');
      view?.requestMeasure?.();
    }).catch((err) => {
      console.warn('[live-preview] embed failed', this.target, this.section, err?.message || err);
      show('cm-lp-embed-error', 'Could not load this embed.');
      view?.requestMeasure?.();
    });
    box.addEventListener('mousedown', (e) => { // Ctrl/Cmd-click opens the embedded page, like a link
      if (!(e.ctrlKey || e.metaKey) || e.target.closest?.('a')) return;
      e.preventDefault();
      e.stopImmediatePropagation();
      const base = (typeof window !== 'undefined' && window.__WIKANTIK_BASE__) || '';
      window.open(`${base}/wiki/${wikiLinkHref({ target: this.target, heading: this.section })}`, '_blank', 'noopener');
    }, true);
    revealOnMouseDown(view, box);
    return box;
  }
  ignoreEvent() { return true; } // the widget handles its own mouse events
}

export function widgetFor(w, context = {}) {
  switch (w.type) {
    case 'bullet': return new BulletWidget();
    case 'checkbox': return new CheckboxWidget(w.checked, w.markerFrom, w.text);
    case 'rule': return new RuleWidget();
    case 'callout-title': return new CalloutTitleWidget(w.style, w.title);
    case 'image': return new ImageWidget(w);
    case 'math': return new MathWidget(w.tex, !!w.display);
    case 'text': return new TextWidget(w.text, w.cls);
    case 'embed': return new EmbedWidget(w.target, w.section, context.loadEmbed);
    default: throw new Error(`unknown live-preview widget type: ${w.type}`);
  }
}
