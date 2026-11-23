import { useEffect, useId, useRef, useState } from 'react';
import { labelColor } from './labelColor';
import { LABEL_PATTERN, MAX_LABELS } from './boardModel';

const RULE = 'Use lowercase letters, digits, dots, dashes or underscores, up to 40.';
const TOO_MANY = `At most ${MAX_LABELS} labels.`;

function chipStyle(name) {
  const color = labelColor(name);
  return { color, borderColor: color, background: `color-mix(in srgb, ${color} 12%, transparent)` };
}

const typedNames = (text) => [...new Set((text || '').split(',').map((n) => n.trim().toLowerCase()).filter(Boolean))];

/** `labels` plus whatever is still typed in the box (see `onPending`): what a Save beside the editor must send. */
export function withPending(labels, text) {
  return [...new Set([...(labels || []), ...typedNames(text)])].sort();
}

/**
 * Label chips; with `editable`, each can be removed and new ones typed (Enter adds, commas separate several; Enter
 * never submits a form). Text typed but not yet added is reported through `onPending(text)` ('' once added or when the
 * editor goes away), so the owner's Save can include it with `withPending` instead of losing it.
 */
export default function WorkLabels({ labels = [], suggestions = [], editable = false, max, onChange, onPending }) {
  const listId = useId();
  const [text, setTextState] = useState('');
  const pendingRef = useRef(onPending);
  useEffect(() => { pendingRef.current = onPending; }, [onPending]);
  useEffect(() => () => pendingRef.current?.(''), []);
  const setText = (value) => { setTextState(value); pendingRef.current?.(value); };
  const [error, setError] = useState('');
  const shown = max != null ? labels.slice(0, max) : labels;
  const hidden = labels.length - shown.length;

  // Adds everything typed (commas separate several). All or nothing: one bad name keeps the text and says why.
  const add = () => {
    const names = typedNames(text);
    if (names.length === 0) return;
    if (names.some((n) => !LABEL_PATTERN.test(n))) { setError(RULE); return; }
    const fresh = names.filter((n) => !labels.includes(n));
    if (labels.length + fresh.length > MAX_LABELS) { setError(TOO_MANY); return; }
    setError('');
    setText('');
    if (fresh.length > 0) onChange?.([...labels, ...fresh].sort());
  };
  const onKeyDown = (e) => {
    if (e.key === 'Enter') { e.preventDefault(); e.stopPropagation(); add(); } // not a save of the field row around it
  };
  const open = suggestions.filter((s) => !labels.includes(s));

  return (
    <div className="work-labels">
      {shown.map((name) => (
        <span key={name} className="work-label" style={chipStyle(name)}>
          {name}
          {editable && (
            <button type="button" className="work-label-remove" aria-label={`Remove label ${name}`}
              onClick={() => onChange?.(labels.filter((l) => l !== name))}>×</button>
          )}
        </span>
      ))}
      {hidden > 0 && <span className="work-label-more">+{hidden}</span>}
      {editable && (
        <>
          <input className="work-label-input" aria-label="Add a label" list={listId} value={text}
            onChange={(e) => { setText(e.target.value); setError(''); }} onKeyDown={onKeyDown} />
          <datalist id={listId}>
            {open.map((s) => <option key={s} value={s} />)}
          </datalist>
          {error && <span role="alert" className="work-label-error">{error}</span>}
        </>
      )}
    </div>
  );
}
