import { useEffect, useState } from 'react';
import Select from '../ui/Select';
import { api } from '../../api/client';

const SEARCH_DELAY_MS = 250;

/**
 * Key-owner picker: search-as-you-type over the people directory; the chosen name stays selectable. Used when
 * registering an agent (default: the registering admin) and when rotating its key (default: the current owner). The
 * empty choice means "no owner named"; `idPrefix` keeps two pickers on one page from sharing element ids.
 */
export default function WorkOwnerPicker({
  owner, onChange, disabled, error, errorId, idPrefix = 'work-a-owner', findLabel = 'Find key owner',
  ariaLabel = 'Key owner', placeholder = 'Me (default)',
}) {
  const [query, setQuery] = useState('');
  const [names, setNames] = useState([]);
  const [loadError, setLoadError] = useState(null);

  useEffect(() => {
    let live = true;
    const timer = setTimeout(() => {
      api.listMentionableUsers(query, 20)
        .then((r) => { if (live) { setNames((r?.users || []).map((u) => u.displayName).filter(Boolean)); setLoadError(null); } })
        .catch((err) => {
          console.warn('WorkOwnerPicker: could not search users', err);
          if (live) setLoadError('Could not search users.');
        });
    }, query === '' ? 0 : SEARCH_DELAY_MS);
    return () => { live = false; clearTimeout(timer); };
  }, [query]);

  const options = owner && !names.includes(owner) ? [owner, ...names] : names;
  return (
    <>
      <label className="work-form-field" htmlFor={`${idPrefix}-q`}>{findLabel}</label>
      <input id={`${idPrefix}-q`} className="work-field-input" value={query} onChange={(e) => setQuery(e.target.value)} disabled={disabled}
        aria-describedby={loadError ? `${idPrefix}-load` : undefined} />
      {loadError && <div id={`${idPrefix}-load`} className="work-field-error" role="alert">{loadError}</div>}
      <Select ariaLabel={ariaLabel} value={owner} options={options} placeholder={placeholder} disabled={disabled}
        onChange={onChange} />
      {error && <div id={errorId} className="work-field-error" role="alert">{error}</div>}
    </>
  );
}
