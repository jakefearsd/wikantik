// Combobox.jsx
// Search-backed single-value picker. Provide either static `options` (filtered client-side) or an
// async `fetchOptions(query) -> Promise<[{value,label}|string]>` (debounced 200ms). With
// `allowFreeEntry`, the typed value is accepted on Enter/blur — used for fields like `cluster`.
import { useEffect, useRef, useState } from 'react';

const normalize = (arr) =>
  (arr || []).map((o) => (typeof o === 'string' ? { value: o, label: o } : o));

export default function Combobox({
  value,
  onChange,
  fetchOptions,
  options,
  allowFreeEntry = true,
  placeholder,
  id,
}) {
  const [query, setQuery] = useState(value ?? '');
  const [matches, setMatches] = useState([]);
  const [open, setOpen] = useState(false);
  const debounceRef = useRef();

  useEffect(() => {
    setQuery(value ?? '');
  }, [value]);

  useEffect(() => () => clearTimeout(debounceRef.current), []);

  const runSearch = (q) => {
    if (options) {
      const all = normalize(options);
      setMatches(
        q ? all.filter((o) => o.label.toLowerCase().includes(q.toLowerCase())) : all,
      );
      setOpen(true);
      return;
    }
    if (fetchOptions) {
      Promise.resolve(fetchOptions(q))
        .then((res) => {
          setMatches(normalize(res));
          setOpen(true);
        })
        .catch(() => setMatches([]));
    }
  };

  const onInput = (e) => {
    const q = e.target.value;
    setQuery(q);
    clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => runSearch(q), 200);
  };

  const select = (v) => {
    onChange?.(v);
    setQuery(v);
    setOpen(false);
  };

  const onKeyDown = (e) => {
    if (e.key === 'Enter' && allowFreeEntry) {
      e.preventDefault();
      select(query.trim());
    } else if (e.key === 'Escape') {
      setOpen(false);
    }
  };

  return (
    <div className="ui-combobox">
      <input
        className="ui-combobox-field"
        type="text"
        role="combobox"
        aria-expanded={open}
        aria-autocomplete="list"
        value={query}
        placeholder={placeholder}
        id={id}
        onChange={onInput}
        onKeyDown={onKeyDown}
        onFocus={() => runSearch(query)}
        onBlur={() => {
          if (allowFreeEntry && value !== query) onChange?.(query.trim());
        }}
      />
      {open && matches.length > 0 && (
        <ul className="ui-combobox-list" role="listbox">
          {matches.map((m) => (
            <li
              key={m.value}
              role="option"
              aria-selected={m.value === value}
              className="ui-combobox-option"
              onMouseDown={(e) => {
                e.preventDefault();
                select(m.value);
              }}
            >
              {m.label}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
