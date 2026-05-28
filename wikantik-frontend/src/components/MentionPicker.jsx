import { useEffect, useRef } from 'react';

/** Dumb popover for @-mention autocomplete. Driven entirely by props from
 *  the useMentionPicker hook. */
export default function MentionPicker({ open, candidates, selectedIndex, onSelect, anchorPos }) {
  const listRef = useRef(null);

  // Scroll the active candidate into view if the keyboard navigation moves
  // selectedIndex outside the visible window.
  useEffect(() => {
    if (!open || !listRef.current) return;
    const active = listRef.current.querySelector('.mention-picker-item.active');
    if (active && typeof active.scrollIntoView === 'function') {
      active.scrollIntoView({ block: 'nearest' });
    }
  }, [open, selectedIndex]);

  if (!open || !candidates || candidates.length === 0) return null;

  // anchorPos is { top, left } relative to viewport (fixed). Hook returns null
  // for v0; default to a corner of the screen so the picker is at least visible.
  const style = anchorPos
    ? { position: 'fixed', top: anchorPos.top, left: anchorPos.left }
    : { position: 'fixed', top: 100, left: 100 };

  return (
    <div className="mention-picker" style={style} ref={listRef} role="listbox" aria-label="User mentions">
      {candidates.map((c, idx) => (
        <button
          key={c.loginName}
          type="button"
          role="option"
          aria-selected={idx === selectedIndex}
          className={`mention-picker-item ${idx === selectedIndex ? 'active' : ''}`}
          // onMouseDown (not onClick) so the textarea doesn't lose focus first.
          onMouseDown={(e) => { e.preventDefault(); onSelect(c.loginName); }}
        >
          <span className="mention-picker-login">@{c.loginName}</span>
          {c.fullName && c.fullName !== c.loginName && (
            <span className="mention-picker-fullname"> {c.fullName}</span>
          )}
        </button>
      ))}
    </div>
  );
}
