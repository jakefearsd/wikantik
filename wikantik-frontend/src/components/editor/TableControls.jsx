import { useEffect, useLayoutEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { buildTableCommands } from '../../utils/editorCommands';
import ToolbarMenu, { keepEditorFocus } from './ToolbarMenu';

/** The registry's entries by id, for their titles only: built without an action, and never run from here. */
const TITLES = Object.fromEntries(buildTableCommands({}).map((command) => [command.id, command.title]));
const ALIGNS = [['table-align-left', 'left'], ['table-align-center', 'center'], ['table-align-right', 'right'], ['table-align-none', null]];
/** For Alt-T's hint: the key as assistive technology spells it. */
const SHORTCUT = 'Alt+T';

/**
 * The row and column controls of the table the caret is in (Rich mode): `[+ row] [+ col] [⋮]`, the rest of the table
 * commands in the `⋮` menu. Every control hands its command-registry id to `onRun`; nothing is written from here.
 *
 * One tab stop: Left/Right move between the three buttons, Escape calls `onLeave` (the host returns to the editor).
 * A mouse press leaves the focus, and so the caret, in the editor.
 *
 * Props:
 *   onRun(commandId)                  — e.g. 'table-row-below', 'table-delete-column'
 *   canDeleteRow, canDeleteColumn     — false disables that menu item (the header row; the last column)
 *   canInsertRowAbove, canMoveLeft, canMoveRight — likewise, default true
 *   align                             — the caret's column's alignment ('left' | 'center' | 'right' | null), checked in the menu
 *   onLeave()                         — Escape on a button; also called when the controls go away while they hold
 *                                       the focus (a peer deleted the table, the document became read-only), which
 *                                       would otherwise drop to the page
 *   barRef                            — receives the toolbar element (the host positions and focuses it)
 *   host                              — an element to draw into (the editor's scroller, so the controls scroll with
 *                                       the table); without one they are drawn in place
 */
export default function TableControls({
  onRun, canDeleteRow, canDeleteColumn, canInsertRowAbove = true, canMoveLeft = true, canMoveRight = true, align = null,
  onLeave, barRef, host,
}) {
  const ownRef = useRef(null);
  const ref = barRef || ownRef;

  const leaveRef = useRef(onLeave);
  useEffect(() => { leaveRef.current = onLeave; });
  // A layout effect: its cleanup runs before the toolbar's elements are removed, while one of them still has the focus.
  useLayoutEffect(() => {
    const bar = ref.current;
    return () => { if (bar?.contains(document.activeElement)) leaveRef.current?.(); };
  }, [ref]);

  const onKeyDown = (e) => {
    if (e.key === 'Escape') { e.preventDefault(); onLeave?.(); return; }
    const items = [...ref.current.querySelectorAll('[data-table-control]')];
    const at = items.indexOf(e.target);
    const to = { ArrowRight: at + 1, ArrowLeft: at - 1, Home: 0, End: items.length - 1 }[e.key];
    if (at < 0 || to === undefined) return;
    e.preventDefault();
    items[(to + items.length) % items.length].focus();
  };

  const item = (id, extra) => ({ id, label: TITLES[id], ...extra });
  const items = [
    item('table-row-above', { disabled: !canInsertRowAbove }),
    item('table-column-left'),
    item('table-move-column-left', { disabled: !canMoveLeft, separatorBefore: true }),
    item('table-move-column-right', { disabled: !canMoveRight }),
    ...ALIGNS.map(([id, value], i) => item(id, { checked: align === value, separatorBefore: i === 0 })),
    item('table-delete-row', { disabled: !canDeleteRow, separatorBefore: true }),
    item('table-delete-column', { disabled: !canDeleteColumn }),
  ];

  const button = (id, face, first) => (
    <button
      type="button"
      className="editor-format-btn table-controls-btn"
      title={first ? `${TITLES[id]} (${SHORTCUT} reaches these controls)` : TITLES[id]}
      aria-label={TITLES[id]}
      data-table-control
      tabIndex={first ? 0 : -1}
      onMouseDown={keepEditorFocus}
      onClick={() => onRun(id)}
    >
      {face}
    </button>
  );

  const bar = (
    <div ref={ref} className="table-controls" role="toolbar" aria-label="Table" aria-keyshortcuts={SHORTCUT} onKeyDown={onKeyDown}>
      {button('table-row-below', '+ row', true)}
      {button('table-column-right', '+ col', false)}
      <ToolbarMenu name="Table" title="More table actions" ariaLabel="More table actions" face="⋮" caret={false}
        className="table-controls-btn" checkable="menuitemradio" items={items} onChoose={onRun}
        itemProps={{ 'data-table-control': true, tabIndex: -1 }} />
    </div>
  );
  return host ? createPortal(bar, host) : bar;
}
