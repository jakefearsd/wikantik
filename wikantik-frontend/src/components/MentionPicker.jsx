import { useEffect, useRef } from 'react';

/** Dumb popover for @-mention autocomplete. Driven entirely by props from
 *  the useMentionPicker hook. Candidates are `{ id, displayName }`: members are
 *  shown to each other by display name only — never by login name. */
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

  if (!open || !candidates || candidates.length === 0 || !anchorPos) return null;

  // anchorPos is { top, left } relative to viewport (fixed).
  const style = { position: 'fixed', top: anchorPos.top, left: anchorPos.left };

  return (
    <div className="mention-picker" style={style} ref={listRef} role="listbox" aria-label="User mentions">
      {candidates.map((c, idx) => (
        <button
          key={c.id}
          type="button"
          role="option"
          aria-selected={idx === selectedIndex}
          className={`mention-picker-item ${idx === selectedIndex ? 'active' : ''}`}
          // onMouseDown (not onClick) so the textarea doesn't lose focus first.
          onMouseDown={(e) => { e.preventDefault(); onSelect(c); }}
        >
          <span className="mention-picker-name">@{c.displayName}</span>
        </button>
      ))}
    </div>
  );
}
