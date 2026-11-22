import { useState } from 'react';
import { windowError } from './flowModel';

/**
 * The period (and optional assignee) of a flow report. Typed values are checked here and only applied when the server
 * would take them, so an invalid period never becomes a request. Blank dates mean "the last 90 days". The parent
 * re-keys this form when the applied values change.
 */
export default function FlowWindow({ from = '', to = '', assignee, withAssignee = false, onApply, outsideError }) {
  const [f, setF] = useState({ from, to, assignee: assignee || '' });
  const [error, setError] = useState(null);
  const set = (name) => (e) => setF((s) => ({ ...s, [name]: e.target.value }));

  function submit(e) {
    e.preventDefault();
    const problem = windowError({ from: f.from.trim(), to: f.to.trim() });
    if (problem) { setError(problem); return; }
    setError(null);
    onApply({ from: f.from.trim(), to: f.to.trim(), ...(withAssignee ? { assignee: f.assignee.trim() } : {}) });
  }

  const shown = error || outsideError;
  return (
    <form className="work-flow-filters" onSubmit={submit} noValidate aria-label="Period">
      <label>From
        <input type="text" inputMode="numeric" className="work-field-input" placeholder="yyyy-mm-dd" value={f.from} onChange={set('from')} />
      </label>
      <label>To
        <input type="text" inputMode="numeric" className="work-field-input" placeholder="yyyy-mm-dd" value={f.to} onChange={set('to')} />
      </label>
      {withAssignee && (
        <label>Assignee
          <input type="text" className="work-field-input" placeholder="anyone" value={f.assignee} onChange={set('assignee')} />
        </label>
      )}
      <button type="submit" className="btn btn-secondary btn-sm">Apply</button>
      <p className="work-muted" style={{ flexBasis: '100%', margin: 0 }}>
        Leave the dates blank for the last 90 days; a period covers at most 366 days.
      </p>
      {shown && <div className="work-form-error" role="alert" style={{ flexBasis: '100%' }}>{shown}</div>}
    </form>
  );
}
