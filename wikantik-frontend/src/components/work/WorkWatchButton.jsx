import { useEffect, useRef, useState } from 'react';
import { getWatch, setWatch } from '../../api/work';
import { ARCHIVED_TEXT } from './archived';
import { useToast } from '../../hooks/useToast';

const LABELS = { watching: 'Watching', muted: 'Muted', none: 'Watch' };
const OPTIONS = [
  { state: 'watching', label: 'Watch this item' },
  { state: 'muted', label: 'Mute this item' },
  { state: 'none', label: 'Use the defaults' },
];

/**
 * Watch / Watching / Muted for an item. The state loads on its own: the item page never waits on it. A failed load
 * leaves the button out (the failure is logged); a failed save keeps the state shown and says so.
 */
export default function WorkWatchButton({ itemKey, archived = false }) {
  const toast = useToast();
  const [state, setState] = useState(null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const trigger = useRef(null);

  useEffect(() => {
    let cancelled = false;
    getWatch(itemKey)
      .then((r) => { if (!cancelled) setState(r?.state || 'none'); })
      .catch((e) => console.warn('WorkWatchButton: could not load the watch state', e));
    return () => { cancelled = true; };
  }, [itemKey]);

  if (state == null) return null;

  function choose(next) {
    setOpen(false);
    if (next === state) return;
    setBusy(true);
    setWatch(itemKey, next)
      .then((r) => setState(r?.state || next))
      .catch((e) => {
        console.warn('WorkWatchButton: could not save the watch state', e);
        toast.error(e?.message || 'Could not change watching.');
      })
      .finally(() => setBusy(false));
  }

  return (
    <div className="work-watch" onKeyDown={(e) => { if (e.key === 'Escape' && open) { setOpen(false); trigger.current?.focus(); } }}>
      <button ref={trigger} type="button" className="btn btn-sm" aria-haspopup="menu" aria-expanded={open}
        disabled={archived || busy} title={archived ? `${ARCHIVED_TEXT}.` : undefined} onClick={() => setOpen((o) => !o)}>
        {LABELS[state] || LABELS.none}
      </button>
      {open && (
        <ul className="work-watch-menu" role="menu">
          {OPTIONS.map((o) => (
            <li key={o.state} role="none">
              <button type="button" role="menuitem" className="work-link-button" onClick={() => choose(o.state)}>{o.label}</button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
