import { useState } from 'react';
import { Link } from 'react-router-dom';
import Badge from '../ui/Badge';
import Select from '../ui/Select';

const KINDS = [
  { value: 'page', label: 'Wiki page' },
  { value: 'issue', label: 'Issue' },
  { value: 'url', label: 'URL' },
  { value: 'commit', label: 'Commit' },
  { value: 'blocked_by', label: 'Blocked by (item key)' },
];
const KIND_LABEL = { page: 'Page', issue: 'Issue', url: 'URL', commit: 'Commit', blocked_by: 'Blocked by' };

/** Only http(s) targets become anchors; anything else (javascript:, data:) stays text. */
function httpHref(target) {
  try {
    const u = new URL(target);
    return u.protocol === 'http:' || u.protocol === 'https:' ? u.href : null;
  } catch {
    return null;
  }
}

function LinkTarget({ link, blocked }) {
  const { kind, target } = link;
  if (kind === 'page') return <Link to={`/wiki/${encodeURIComponent(target)}`}>{target}</Link>;
  if (kind === 'blocked_by') {
    return (
      <>
        <Link to={`/work/item/${encodeURIComponent(target)}`}>{target}</Link>
        {blocked && <Badge variant="danger" title="Blocked by an open item">Blocked</Badge>}
      </>
    );
  }
  if (kind === 'issue' || kind === 'url') {
    const href = httpHref(target);
    return href
      ? <a href={href} target="_blank" rel="noopener noreferrer">{target}</a>
      : <span>{target}</span>;
  }
  return <code>{target}</code>;
}

/** The item's links. `onAdd(kind, target)` and `onRemove(id)` resolve true on success; only editors get controls. */
export default function WorkLinks({ links = [], blocked = false, canEdit = false, onAdd, onRemove }) {
  const [kind, setKind] = useState('page');
  const [target, setTarget] = useState('');
  const [saving, setSaving] = useState(false);

  function add(e) {
    e.preventDefault();
    const value = target.trim();
    if (!value || saving) return;
    setSaving(true);
    onAdd(kind, value)
      .then((ok) => { if (ok) setTarget(''); })
      .catch((err) => console.warn('WorkLinks: adding a link failed', err))
      .finally(() => setSaving(false));
  }

  return (
    <section className="work-section" aria-label="Links">
      <h2 className="work-subheading">Links</h2>
      {links.length === 0 ? <p className="work-muted">No links.</p> : (
        <ul className="work-link-list">
          {links.map((l) => (
            <li key={l.id}>
              <span className="work-link-kind">{KIND_LABEL[l.kind] || l.kind}</span>
              <LinkTarget link={l} blocked={blocked} />
              {canEdit && (
                <button type="button" className="work-link-button" aria-label={`Remove link ${l.target}`} onClick={() => onRemove(l.id)}>Remove</button>
              )}
            </li>
          ))}
        </ul>
      )}
      {canEdit && (
        <form className="work-link-form" onSubmit={add}>
          <Select ariaLabel="Link kind" value={kind} options={KINDS} onChange={setKind} />
          <input className="work-field-input" aria-label="Link target" value={target} onChange={(e) => setTarget(e.target.value)} />
          <button type="submit" className="btn btn-secondary btn-sm" disabled={saving}>Add link</button>
        </form>
      )}
    </section>
  );
}
