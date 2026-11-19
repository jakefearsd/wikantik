import { memo, useLayoutEffect, useRef, useState } from 'react';
import { formatKeys } from '../utils/keyHints';
import { buildEditorCommands } from '../utils/editorCommands';
import { EMPTY_FORMAT_STATE } from '../utils/formatState';
import ToolbarMenu, { keepEditorFocus } from './editor/ToolbarMenu';

/** The registry's entries by id, for their titles and shortcuts only: built without actions, and never run from here. */
const COMMANDS = Object.fromEntries(buildEditorCommands({}).map((command) => [command.id, command]));
const LABELS = { 'toggle-comment': 'Comment' };

/** A command's name on the toolbar: the registry's title, without the "Insert" an insert surface already says. */
function labelOf(id) {
  const { title, slashLabel } = COMMANDS[id];
  return LABELS[id] ?? slashLabel ?? title.replace(/^Insert (.)/, (_, first) => first.toUpperCase());
}
const hintOf = (id) => formatKeys(COMMANDS[id].keys);

const LinkGlyph = (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
       strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
    <path d="M10 13a5 5 0 0 0 7.07 0l3-3a5 5 0 0 0-7.07-7.07l-1.5 1.5" />
    <path d="M14 11a5 5 0 0 0-7.07 0l-3 3a5 5 0 0 0 7.07 7.07l1.5-1.5" />
  </svg>
);

/**
 * The button row, left to right (the Heading menu sits HEADING_AT buttons in). `pressed` reads the caret's format state;
 * a button without it is not a toggle. When the toolbar is too narrow the row gives its buttons to the Insert menu
 * from the right end.
 */
const BUTTONS = [
  { id: 'format-bold', face: 'B', style: { fontWeight: 'bold' }, pressed: (s) => s.bold },
  { id: 'format-italic', face: 'I', style: { fontStyle: 'italic' }, pressed: (s) => s.italic },
  { id: 'format-strike', face: 'S', style: { textDecoration: 'line-through' }, pressed: (s) => s.strike },
  { id: 'format-list', face: '•', pressed: (s) => s.list === 'bullet' },
  { id: 'format-ordered-list', face: '1.', pressed: (s) => s.list === 'ordered' },
  { id: 'format-task-list', face: '☑', pressed: (s) => s.list === 'task' },
  { id: 'format-quote', face: '❝', pressed: (s) => s.quote },
  { id: 'format-code', face: '</>', style: { fontFamily: 'monospace' }, pressed: (s) => s.code },
  { id: 'insert-table', face: '▦' },
  { id: 'insert-link', face: LinkGlyph, pressed: (s) => s.link },
];
/** The Heading menu's place in the row: after this many buttons. */
const HEADING_AT = 3;
const HEADING_LEVELS = [1, 2, 3, 4];
const INSERT_IDS = ['code-block', 'insert-mermaid', 'insert-plantuml', 'math-block', 'callout-note', 'callout-tip', 'callout-info',
  'callout-warning', 'callout-danger', 'insert-image', 'insert-embed', 'insert-footnote', 'format-highlight', 'toggle-comment',
  'horizontal-rule', 'insert-access-rule'];

/** The mode control's two options, in order; both are toolbar items, so the arrow keys reach them. */
const MODE_OPTIONS = [
  { id: 'mode-rich', label: 'Rich', data: 'rich' },
  { id: 'mode-source', label: 'Source', data: 'source' },
];

/**
 * How many buttons must leave the row, from its right end, for the toolbar to fit `available` pixels. `fixed` is the
 * width that never moves (padding, the menus, the mode control), `fixedCount` how many controls that is.
 */
function overflowCountFor(available, fixed, fixedCount, gap, widths) {
  let used = fixed + widths.reduce((sum, width) => sum + width, 0) + gap * (fixedCount + widths.length - 1);
  let count = 0;
  while (used > available && count < widths.length) {
    used -= widths[widths.length - 1 - count] + gap;
    count += 1;
  }
  return count;
}

/**
 * The editor's formatting toolbar: every control runs a command-registry id through `onRun`. One tab stop (roving
 * tabindex): Left/Right move between the controls, Home/End jump, Tab leaves.
 *
 * Props:
 *   onRun(commandId) — e.g. 'format-bold', 'heading-2', 'insert-table'
 *   liveMode / liveDisabled — the mode control's state: Rich checked, and Rich disabled (legacy page)
 *   formatState — what the caret is in (`formatStateAt`); lights the toggle buttons and names the heading level
 */
function EditorToolbar({ onRun, liveMode = false, liveDisabled = false, formatState = EMPTY_FORMAT_STATE }) {
  const toolbarRef = useRef(null);
  const widthsRef = useRef({});
  const [overflowCount, setOverflowCount] = useState(0);
  const [activeId, setActiveId] = useState(BUTTONS[0].id);

  // Buttons that do not fit move into the Insert menu. A moved button keeps the width it had while it was in the row.
  useLayoutEffect(() => {
    const toolbar = toolbarRef.current;
    if (!toolbar || typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver(() => {
      const style = getComputedStyle(toolbar);
      const px = (value) => parseFloat(value) || 0;
      let fixed = px(style.paddingLeft) + px(style.paddingRight);
      let fixedCount = 0;
      for (const child of toolbar.children) {
        const id = child.dataset.overflowId;
        if (id) widthsRef.current[id] = child.offsetWidth;
        else { fixed += child.offsetWidth; fixedCount += 1; }
      }
      const widths = BUTTONS.map((b) => widthsRef.current[b.id] ?? 0);
      setOverflowCount(overflowCountFor(toolbar.clientWidth, fixed, fixedCount, px(style.columnGap), widths));
    });
    observer.observe(toolbar);
    return () => observer.disconnect();
  }, []);

  const inRow = BUTTONS.slice(0, BUTTONS.length - overflowCount);
  const moved = BUTTONS.slice(BUTTONS.length - overflowCount);
  const resolved = activeId === 'heading' || activeId === 'insert' || MODE_OPTIONS.some((o) => o.id === activeId)
    || inRow.some((b) => b.id === activeId) ? activeId : (inRow[0]?.id ?? 'heading');
  // A disabled Rich cannot be the tab stop, or the toolbar would have none.
  const active = resolved === 'mode-rich' && liveDisabled ? 'mode-source' : resolved;
  const roving = (id) => ({ 'data-toolbar-item': id, tabIndex: id === active ? 0 : -1, onFocus: () => setActiveId(id) });

  const onKeyDown = (e) => {
    const items = [...toolbarRef.current.querySelectorAll('[data-toolbar-item]:not(:disabled)')];
    const at = items.indexOf(e.target);
    if (at < 0) return;
    const to = { ArrowRight: at + 1, ArrowLeft: at - 1, Home: 0, End: items.length - 1 }[e.key];
    if (to === undefined) return;
    e.preventDefault();
    items[(to + items.length) % items.length].focus();
  };

  const { heading } = formatState;
  const headingName = heading ? `Heading ${heading}` : 'Paragraph';
  const headingItems = [
    { id: 'paragraph', label: 'Paragraph', checked: heading === 0, disabled: !HEADING_LEVELS.includes(heading) },
    ...HEADING_LEVELS.map((n) => ({ id: `heading-${n}`, label: labelOf(`heading-${n}`), checked: heading === n })),
  ];
  const insertItems = [
    ...moved.map(({ id, pressed }) => ({ id, label: labelOf(id), hint: hintOf(id), checked: pressed ? !!pressed(formatState) : undefined })),
    ...INSERT_IDS.map((id, i) => ({ id, label: labelOf(id), hint: hintOf(id), separatorBefore: i === 0 && moved.length > 0 })),
  ];

  const rowButton = ({ id, face, style, pressed }) => {
    const label = labelOf(id);
    const hint = hintOf(id);
    return (
      <button
        key={id}
        type="button"
        className="editor-format-btn"
        title={hint ? `${label} (${hint})` : label}
        aria-label={label}
        aria-pressed={pressed ? !!pressed(formatState) : undefined}
        data-overflow-id={id}
        style={style}
        onMouseDown={keepEditorFocus}
        onClick={() => onRun(id)}
        {...roving(id)}
      >
        {face}
      </button>
    );
  };

  return (
    <div ref={toolbarRef} className="editor-format-toolbar" role="toolbar" aria-label="Formatting toolbar" onKeyDown={onKeyDown}>
      {inRow.slice(0, HEADING_AT).map(rowButton)}
      <ToolbarMenu
        name="Heading"
        title="Heading"
        ariaLabel={`Heading: ${headingName}`}
        face={headingName}
        className="editor-format-heading"
        checkable="menuitemradio"
        items={headingItems}
        onChoose={(id) => onRun(id === 'paragraph' ? `heading-${heading}` : id)}
        itemProps={roving('heading')}
      />
      {inRow.slice(HEADING_AT).map(rowButton)}
      <ToolbarMenu name="Insert" title="Insert" ariaLabel="Insert" face="Insert" items={insertItems} onChoose={onRun} itemProps={roving('insert')} />
      <span id="toggle-live-preview" data-testid="editor-mode" className="editor-mode-control" role="radiogroup" aria-label="Editor mode">
        {MODE_OPTIONS.map(({ id, label, data }) => {
          const disabled = id === 'mode-rich' && liveDisabled;
          const checked = id === 'mode-rich' ? liveMode : !liveMode;
          return (
            <button
              key={id}
              type="button"
              role="radio"
              aria-checked={checked}
              data-mode={data}
              className="editor-mode-option"
              title={disabled ? 'Rich editing is available for Markdown pages' : `${label} (${formatKeys('Mod-E')} switches)`}
              onMouseDown={keepEditorFocus}
              onClick={() => { if (!checked) onRun('toggle-live-preview'); }}
              disabled={disabled}
              {...roving(id)}
            >
              {label}
            </button>
          );
        })}
      </span>
    </div>
  );
}

export default memo(EditorToolbar);
