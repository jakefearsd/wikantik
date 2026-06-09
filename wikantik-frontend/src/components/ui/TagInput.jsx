// TagInput.jsx
// Chip-based multi-value input. value is an array of strings; Enter or comma adds the draft,
// Backspace on an empty field removes the last chip, and each chip's × removes it. Optional
// `suggestions` populate a <datalist> for autocomplete.
import { useState } from 'react';
import Chip from './Chip';

export default function TagInput({
  value = [],
  onChange,
  suggestions = [],
  placeholder = 'Add…',
  id,
}) {
  const [draft, setDraft] = useState('');
  const listId = id ? `${id}-suggestions` : undefined;

  const commit = (raw) => {
    const tag = raw.trim();
    setDraft('');
    if (!tag || value.includes(tag)) return;
    onChange?.([...value, tag]);
  };

  const remove = (tag) => onChange?.(value.filter((t) => t !== tag));

  const onKeyDown = (e) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault();
      commit(draft);
    } else if (e.key === 'Backspace' && draft === '' && value.length > 0) {
      remove(value[value.length - 1]);
    }
  };

  return (
    <div className="ui-taginput">
      {value.map((tag) => (
        <Chip key={tag} label={tag} onRemove={() => remove(tag)} />
      ))}
      <input
        className="ui-taginput-field"
        type="text"
        value={draft}
        placeholder={placeholder}
        aria-label={placeholder}
        list={listId}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={onKeyDown}
        onBlur={() => commit(draft)}
      />
      {suggestions.length > 0 && (
        <datalist id={listId}>
          {suggestions.map((s) => (
            <option key={s} value={s} />
          ))}
        </datalist>
      )}
    </div>
  );
}
