import { useId, useState } from 'react';
import { labelColor } from './labelColor';
import { LABEL_PATTERN, MAX_LABELS } from './boardModel';

const RULE = 'Use lowercase letters, digits, dots, dashes or underscores, up to 40.';
const TOO_MANY = `At most ${MAX_LABELS} labels.`;

function chipStyle(name) {
  const color = labelColor(name);
  return { color, borderColor: color, background: `color-mix(in srgb, ${color} 12%, transparent)` };
}

/** Label chips; with `editable`, each can be removed and a new one typed (Enter adds, never submits a form). */
export default function WorkLabels({ labels = [], suggestions = [], editable = false, max, onChange }) {
  const listId = useId();
  const [text, setText] = useState('');
  const [error, setError] = useState('');
  const shown = max != null ? labels.slice(0, max) : labels;
  const hidden = labels.length - shown.length;

  const add = () => {
    const name = text.trim().toLowerCase();
    if (!name) return;
    if (!LABEL_PATTERN.test(name)) { setError(RULE); return; }
    if (labels.includes(name)) { setError(''); return; }
    if (labels.length >= MAX_LABELS) { setError(TOO_MANY); return; }
    setError('');
    setText('');
    onChange?.([...labels, name].sort());
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
