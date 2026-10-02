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
  toDOM() {
    const wrap = el('span', 'cm-lp-image-wrap');
    const img = el('img', 'cm-lp-image');
    img.setAttribute('src', this.src);
    img.setAttribute('alt', this.alt);
    if (this.width != null) img.setAttribute('width', String(this.width));
    if (this.height != null) img.setAttribute('height', String(this.height));
    img.addEventListener('error', () => {
      console.warn('[live-preview] image failed to load', this.src);
      wrap.classList.add('cm-lp-image-missing');
      wrap.textContent = this.alt || this.src;
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

export class CheckboxWidget extends WidgetType {
  constructor(checked, markerFrom) { super(); this.checked = checked; this.markerFrom = markerFrom; }
  eq(other) { return other instanceof CheckboxWidget && other.checked === this.checked && other.markerFrom === this.markerFrom; }
  toDOM(view) {
    const box = el('input', 'cm-lp-task');
    box.type = 'checkbox';
    box.checked = this.checked;
    box.setAttribute('aria-label', this.checked ? 'Completed task' : 'Task');
    box.addEventListener('mousedown', (e) => {
      e.preventDefault(); // keep editor focus; the document edit re-renders the box
      try {
        if (this.markerFrom != null) toggleTaskBox(view, this.markerFrom);
        else toggleTaskAt(view, view.posAtDOM(box));
      } catch (err) {
        console.warn('[live-preview] task toggle failed', err?.message || err);
      }
    });
    box.addEventListener('keydown', (e) => { // keyboard parity: Space/Enter toggle through the same transaction
      if (e.key !== ' ' && e.key !== 'Enter') return;
      e.preventDefault();
      try {
        if (this.markerFrom != null) toggleTaskBox(view, this.markerFrom);
        else toggleTaskAt(view, view.posAtDOM(box));
      } catch (err) {
        console.warn('[live-preview] task toggle failed', err?.message || err);
      }
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

export class MathWidget extends WidgetType {
  constructor(tex, display) { super(); this.tex = tex; this.display = display; }
  eq(other) { return other instanceof MathWidget && other.tex === this.tex && other.display === this.display; }
  toDOM(view) {
    const node = el(this.display ? 'div' : 'span', this.display ? 'cm-lp-math cm-lp-math-block' : 'cm-lp-math');
    try {
      // renderToString (not render): same output, and it is not disabled by KaTeX's quirks-mode guard
      // (which also makes the widget testable under happy-dom). The markup is generated by KaTeX itself.
      node.innerHTML = katex.renderToString(this.tex, { displayMode: this.display, throwOnError: true });
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
    case 'checkbox': return new CheckboxWidget(w.checked, w.markerFrom);
    case 'rule': return new RuleWidget();
    case 'callout-title': return new CalloutTitleWidget(w.style, w.title);
    case 'image': return new ImageWidget(w);
    case 'math': return new MathWidget(w.tex, !!w.display);
    case 'text': return new TextWidget(w.text, w.cls);
    case 'embed': return new EmbedWidget(w.target, w.section, context.loadEmbed);
    default: throw new Error(`unknown live-preview widget type: ${w.type}`);
  }
}
