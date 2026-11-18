import { WidgetType, ViewPlugin } from '@codemirror/view';
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

const MAC = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || '');
const MOVES_VALUE = new Set(['ArrowDown', 'ArrowUp', 'ArrowLeft', 'ArrowRight', 'Home', 'End', 'PageDown', 'PageUp']);
const chord = (e) => e.ctrlKey || e.metaKey || e.altKey;

/**
 * Whether a key pressed on the focused picker opens its list: Enter, Space, Alt-ArrowDown/Up — and, where a closed
 * select's arrow keys open the list instead of changing the value (`mac`), the bare vertical arrows too.
 */
export function opensPicker(e, mac = MAC) {
  if (e.key === 'Enter' || e.key === ' ') return !e.ctrlKey && !e.metaKey;
  if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return false;
  return e.altKey || (mac && !chord(e));
}

/**
 * A `<select>` over `options`, showing the one that carries `selected`. A choice dispatches
 * `changeFor(doc, pos, chosen)` — `pos` being where the widget `root()` stands — as one isolated undo step, and hands
 * the focus back to the editor.
 *
 * Only a deliberate choice writes. A closed, focused select changes its value on a bare arrow, Home, End or Page key
 * (Chrome and Firefox on Linux and Windows) and fires `change` at once — and the picker can keep the focus after its
 * list was dismissed, where the author's next ArrowDown is meant for the caret. So a `change` counts only after the
 * list was opened on purpose (`armed`: a press on the picker, or an opening key, see opensPicker), once; any other
 * `change` is put back and the focus goes to the editor. A value-moving key ends the opening (in an open list Enter,
 * which takes the option, opens it again in this sense); so do Escape and blur.
 *
 * The focus is also returned when the list closes without a change, as far as the DOM shows it: Escape, a second press
 * on the open picker, and a click that follows no press (an option taken in the list — the one already shown).
 * A letter typed on the picker would be type-ahead: it is dropped and the focus returned. Other keys are untouched.
 *
 * A read-only document (a frozen shared draft, no edit permission) is never changed: `view.dispatch` does not check
 * `readOnly` itself, so the picker is disabled (kept in step by followReadOnly), does not open, and a choice that
 * still arrives is put back.
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
  let armed = false; // the list was opened on purpose and no choice has been made since
  let open = false; // the list is open, as far as presses on the picker tell
  let pressed = false; // the next click belongs to a press on the picker itself
  const close = () => { armed = false; open = false; };
  const backToEditor = () => { close(); select.selectedIndex = shown; view.focus(); };

  select.addEventListener('mousedown', (e) => {
    if (readOnly()) { e.preventDefault(); return; }
    pressed = true;
    open = !open;
    armed = open;
  });
  select.addEventListener('click', () => {
    if (pressed) {
      pressed = false;
      if (!open) backToEditor(); // the press closed the open list
    } else if (open) {
      backToEditor(); // an option was taken in the list and no `change` came before this click: the one shown
    }
  });
  select.addEventListener('blur', close);
  select.addEventListener('keydown', (e) => {
    if (readOnly()) return;
    if (e.key === 'Escape') backToEditor();
    else if (opensPicker(e)) armed = true;
    else if (MOVES_VALUE.has(e.key) && !chord(e)) armed = false;
    else if (e.key.length === 1 && !chord(e)) { e.preventDefault(); backToEditor(); } // type-ahead
  });
  select.addEventListener('change', () => {
    const deliberate = armed && !readOnly();
    const chosen = select.value;
    backToEditor();
    if (!deliberate) return;
    try {
      const change = changeFor(view.state.doc, view.posAtDOM(root()), chosen);
      if (change) view.dispatch({ changes: change, annotations: isolateHistory.of('full'), userEvent });
    } catch (err) {
      console.warn('[rich] a header choice could not be applied', err?.message || err);
    }
  });
  return select;
}

/**
 * Keeps the controls drawn inside the editor — the pickers and the task checkboxes — disabled exactly while the
 * document is read-only. Read-only comes and goes in a live session (a shared draft resyncing), and a widget that
 * compares equal keeps its DOM, so what toDOM set would stay. Touches the DOM only when `readOnly` changes.
 */
export const followReadOnly = ViewPlugin.define(() => ({
  update(u) {
    if (u.startState.readOnly === u.state.readOnly) return;
    for (const control of u.view.contentDOM.querySelectorAll('select.cm-lp-picker, input.cm-lp-task')) control.disabled = u.state.readOnly;
  },
}));

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
