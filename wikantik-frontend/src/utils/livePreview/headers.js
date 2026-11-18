import { WidgetType } from '@codemirror/view';
import { isolateHistory } from '@codemirror/commands';
import { FENCE_RENDERERS } from '../fences/registry';
import { MARKER, defaultTitle } from '../remarkCallouts';

/**
 * The one choice a block's header offers in Rich mode — a code block's language, a diagram's kind, a callout's type —
 * as a `<select>` inside the widget that stands for the marker. A choice is one transaction that replaces only the
 * word it names; where that word is, is read from the document when the choice is made, so the widget carries no
 * position and stays equal (and is not rebuilt) while text above it shifts.
 */

const LANGUAGES = ['text', 'bash', 'java', 'javascript', 'typescript', 'json', 'yaml', 'sql', 'python', 'xml', 'html', 'css', 'diff'];
const CALLOUT_TYPES = ['note', 'tip', 'info', 'warning', 'danger'];
const OPENING_FENCE = /^(`{3,}|~{3,})([ \t\v\f]*)([^ \t\v\f]*)/;

/**
 * The header picker's options: the rendered kinds, the common languages, then `current` when it is none of those; the
 * one shown carries `selected`. A fence whose language names a rendered kind but which is not drawn (`kind` 'code':
 * `mermaid example`) is code, so it shows its word as a last option, not the diagram label.
 */
export function fenceLanguageOptions(current, kind) {
  const kinds = Object.entries(FENCE_RENDERERS).map(([value, r]) => ({ value, label: `${r.label} diagram` }));
  const options = [...kinds, ...LANGUAGES.map((value) => ({ value, label: value }))];
  const listed = kind === 'diagram' ? options.find((o) => o.value === current) : options.slice(kinds.length).find((o) => o.value === current);
  if (listed) listed.selected = true;
  else options.push({ value: current, label: current || 'Code', selected: true });
  return options;
}

/**
 * The change that makes `language` the first word of the info string of the fence opening at `pos` (the first fence
 * character): only that word is replaced, or the language is inserted right after the fence when there is none.
 * Null when `pos` does not open a fence or the word is already `language`.
 */
export function fenceLanguageChange(doc, pos, language) {
  const m = OPENING_FENCE.exec(doc.sliceString(pos, doc.lineAt(pos).to));
  if (!m || m[3] === language) return null;
  const from = pos + m[1].length + (m[3] ? m[2].length : 0);
  return { from, to: from + m[3].length, insert: language };
}

/**
 * The type picker's options: the five common types, then `rawType` when it is none of them (compared without case);
 * the one shown carries `selected`.
 */
export function calloutTypeOptions(rawType) {
  const lower = rawType.toLowerCase();
  const options = CALLOUT_TYPES.map((value) => ({ value, label: defaultTitle(value), ...(value === lower && { selected: true }) }));
  if (!CALLOUT_TYPES.includes(lower)) options.push({ value: rawType, label: defaultTitle(rawType), selected: true });
  return options;
}

/**
 * The change that makes `type` the word inside the `[!…]` starting at `pos`: only that word is replaced. Null when
 * there is no callout marker at `pos` or the word is already `type`.
 */
export function calloutTypeChange(doc, pos, type) {
  const m = MARKER.exec(doc.sliceString(pos, doc.lineAt(pos).to));
  if (!m || m[1] === type) return null;
  return { from: pos + 2, to: pos + 2 + m[1].length, insert: type };
}

/** The keys that work a `<select>`; every other key pressed in a picker goes back to the editor. */
const SELECT_KEYS = new Set(['ArrowDown', 'ArrowUp', 'ArrowLeft', 'ArrowRight', 'Enter', ' ', 'Tab', 'Home', 'End', 'PageDown', 'PageUp']);

/**
 * A `<select>` over `options`, showing the one that carries `selected`. Choosing dispatches
 * `changeFor(doc, pos, chosen)` — `pos` being where the widget `root()` stands — as one isolated undo step, and hands
 * the focus back to the editor either way.
 *
 * A read-only document (a frozen shared draft, no edit permission) is never changed: `view.dispatch` does not check
 * `readOnly` itself, so the picker is drawn disabled, does not open, and a choice that still arrives is put back.
 *
 * The focus must not stay in the picker: a letter typed there is the select's type-ahead, which would pick a language.
 * So Escape, and any key that does not work a select, return the focus to the editor (the key is dropped).
 */
export function picker(view, { cls, label, options, userEvent, root, changeFor }) {
  const select = document.createElement('select');
  select.className = `cm-lp-picker ${cls}`;
  select.setAttribute('aria-label', label);
  select.setAttribute('autocomplete', 'off'); // or a reload restores the last choice over the document's
  let shown = 0;
  options.forEach((o, i) => {
    const option = document.createElement('option');
    option.value = o.value;
    option.textContent = o.label;
    if (o.selected) shown = i;
    select.appendChild(option);
  });
  select.selectedIndex = shown;
  const readOnly = () => view?.state.readOnly === true;
  select.disabled = readOnly();
  select.addEventListener('mousedown', (e) => { if (readOnly()) e.preventDefault(); });
  select.addEventListener('keydown', (e) => {
    if (SELECT_KEYS.has(e.key) && !readOnly()) return;
    e.preventDefault();
    view.focus();
  });
  select.addEventListener('change', () => {
    if (readOnly()) {
      select.selectedIndex = shown;
      return;
    }
    try {
      const change = changeFor(view.state.doc, view.posAtDOM(root()), select.value);
      if (change) view.dispatch({ changes: change, annotations: isolateHistory.of('full'), userEvent });
    } catch (err) {
      console.warn('[rich] a header choice could not be applied', err?.message || err);
    }
    view.focus();
  });
  return select;
}

/** Whether an event came from a header picker: CodeMirror leaves those to the `<select>`. */
export const fromPicker = (event) => !!event.target?.closest?.('select.cm-lp-picker');

/**
 * The opening fence line in Rich mode: its text is replaced by this header, a picker showing the language ("Code" when
 * there is none) or, for a fence a registered renderer draws, the diagram kind.
 */
export class FenceHeaderWidget extends WidgetType {
  constructor({ language, kind }) { super(); this.language = language; this.kind = kind; }
  eq(other) { return other instanceof FenceHeaderWidget && other.language === this.language && other.kind === this.kind; }
  toDOM(view) {
    const wrap = document.createElement('span');
    wrap.className = 'cm-lp-fence-header';
    wrap.appendChild(picker(view, {
      cls: 'cm-lp-fence-lang',
      label: this.kind === 'diagram' ? 'Diagram kind' : 'Code language',
      options: fenceLanguageOptions(this.language, this.kind),
      userEvent: 'input.fence-language',
      root: () => wrap,
      changeFor: fenceLanguageChange,
    }));
    return wrap;
  }
  ignoreEvent(event) { return fromPicker(event); } // anywhere else on the header, CodeMirror places the caret (it moves on into the block)
}
