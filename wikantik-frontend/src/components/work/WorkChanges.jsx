import { useCallback, useEffect, useState } from 'react';
import {
  abandonChange, listChanges, mergeChange, openChange, requestReview, reviewChange,
} from '../../api/work';

const STATE_LABEL = { open: 'open', in_review: 'in review', merged: 'merged', abandoned: 'abandoned' };
const KIND_LABEL = { reviewed: 'merged after review', unreviewed: 'merged without review', direct: 'merged directly' };
const BLANK = { branch: '', headSha: '', reviewUrl: '', mergedSha: '' };
const short = (sha) => (sha ? String(sha).slice(0, 8) : '');

/** Only http(s) targets become anchors; anything else stays text. */
function httpHref(target) {
  try {
    const u = new URL(target);
    return u.protocol === 'http:' || u.protocol === 'https:' ? u.href : null;
  } catch {
    return null;
  }
}

function who(name, kind) {
  if (!name) return null;
  return kind === 'agent' ? `${name} (agent)` : name;
}

function ChangeRow({ change: c, canEdit, busy, act }) {
  const [sha, setSha] = useState(c.headSha || '');
  const [note, setNote] = useState('');
  const live = c.state === 'open' || c.state === 'in_review';
  const href = c.reviewUrl ? httpHref(c.reviewUrl) : null;
  const stateText = c.state === 'merged' ? KIND_LABEL[c.mergeKind] || 'merged' : STATE_LABEL[c.state] || c.state;
  return (
    <li className="work-change" data-change-id={c.id}>
      <div className="work-change-head">
        <code>{c.branch}</code> <span className="work-muted">→ {c.baseBranch}</span>{' '}
        <span className="work-chip">{stateText}</span>
      </div>
      <div className="work-muted">
        {[`opened by ${who(c.openedByName, c.openedByKind) || 'someone'}`,
          c.headSha && `head ${short(c.headSha)}`,
          c.approvedForHead && `approved by ${who(c.reviewedByName, c.reviewedByKind) || 'someone'}`,
          c.state === 'merged' && `merged by ${who(c.mergedByName, c.mergedByKind) || 'someone'} · ${short(c.mergedSha)}`,
        ].filter(Boolean).join(' · ')}
        {href && <> · <a href={href} target="_blank" rel="noopener noreferrer">review</a></>}
      </div>
      {canEdit && live && (
        <div className="work-change-actions">
          {c.state === 'open' && (
            <button type="button" className="btn btn-sm" disabled={busy}
              onClick={() => act(() => requestReview(c.id))}>Ask for review</button>
          )}
          {c.state === 'in_review' && (
            <>
              <input className="work-field-input" aria-label={`Review note for ${c.branch}`} placeholder="Review note"
                value={note} onChange={(e) => setNote(e.target.value)} />
              <button type="button" className="btn btn-sm" disabled={busy}
                onClick={() => act(() => reviewChange(c.id, { approved: true, note: note || null }))}>Approve</button>
              <button type="button" className="btn btn-sm" disabled={busy}
                onClick={() => act(() => reviewChange(c.id, { approved: false, note: note || null }))}>Send back</button>
            </>
          )}
          <input className="work-field-input" aria-label={`Merged commit for ${c.branch}`} placeholder="Merged commit"
            value={sha} onChange={(e) => setSha(e.target.value)} />
          <button type="button" className="btn btn-sm" disabled={busy || !sha.trim()}
            onClick={() => act(() => mergeChange(c.id, { mergedSha: sha.trim() }))}>Record merge</button>
          <button type="button" className="btn btn-ghost btn-sm" disabled={busy}
            onClick={() => act(() => abandonChange(c.id, {}))}>Abandon</button>
        </div>
      )}
    </li>
  );
}

/**
 * The item's changes: branches of work from open to merged. It loads its own list and reloads after each action;
 * a refusal from the server is shown in place and nothing typed is lost. `onChanged` tells the item view to
 * refresh its timeline.
 */
export default function WorkChanges({ itemKey, canEdit = false, refreshKey = 0, onChanged }) {
  const [changes, setChanges] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [mode, setMode] = useState(null); // null | 'open' | 'merged'
  const [f, setF] = useState(BLANK);

  const load = useCallback(() => listChanges(itemKey)
    .then((r) => setChanges(r?.changes || []))
    .catch((e) => { console.warn('WorkChanges: could not load the changes', e); setChanges([]); }), [itemKey]);

  useEffect(() => { load(); }, [load, refreshKey]);

  function act(call, after) {
    if (busy) return;
    setBusy(true); setError(null);
    call()
      .then(() => { after?.(); onChanged?.(); return load(); })
      .catch((e) => setError(e?.message || 'That did not work.'))
      .finally(() => setBusy(false));
  }

  function submit(e) {
    e.preventDefault();
    if (!f.branch.trim()) { setError('Name the branch.'); return; }
    if (mode === 'merged' && !f.mergedSha.trim()) { setError('Name the merged commit.'); return; }
    const body = mode === 'merged'
      ? { branch: f.branch.trim(), mergedSha: f.mergedSha.trim() }
      : { branch: f.branch.trim(), headSha: f.headSha.trim() || null, reviewUrl: f.reviewUrl.trim() || null };
    act(() => openChange(itemKey, body), () => { setF(BLANK); setMode(null); });
  }

  const set = (name) => (e) => setF((s) => ({ ...s, [name]: e.target.value }));

  return (
    <section className="work-section" aria-label="Changes">
      <h2 className="work-subheading">Changes</h2>
      {changes === null ? <p className="work-muted">Loading…</p>
        : changes.length === 0 ? <p className="work-muted">No changes.</p> : (
          <ul className="work-change-list">
            {changes.map((c) => <ChangeRow key={c.id} change={c} canEdit={canEdit} busy={busy} act={act} />)}
          </ul>
        )}
      {error && <div className="work-form-error" role="alert">{error}</div>}
      {canEdit && mode === null && (
        <div className="work-actions">
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => { setMode('open'); setError(null); }}>Open a change</button>
          <button type="button" className="btn btn-sm" onClick={() => { setMode('merged'); setError(null); }}>Record a merged change</button>
        </div>
      )}
      {canEdit && mode !== null && (
        <form className="work-form work-change-form" onSubmit={submit} noValidate>
          <label className="work-form-field" htmlFor="work-change-branch">Branch</label>
          <input id="work-change-branch" className="work-field-input" value={f.branch} onChange={set('branch')}
            placeholder={`work/${itemKey}-short-name`} />
          {mode === 'open' ? (
            <>
              <label className="work-form-field" htmlFor="work-change-head">Newest commit</label>
              <input id="work-change-head" className="work-field-input" value={f.headSha} onChange={set('headSha')} />
              <label className="work-form-field" htmlFor="work-change-url">Review link</label>
              <input id="work-change-url" className="work-field-input" value={f.reviewUrl} onChange={set('reviewUrl')} />
            </>
          ) : (
            <>
              <label className="work-form-field" htmlFor="work-change-merged">Merged commit</label>
              <input id="work-change-merged" className="work-field-input" value={f.mergedSha} onChange={set('mergedSha')} />
            </>
          )}
          <div className="work-actions">
            <button type="submit" className="btn btn-primary btn-sm" disabled={busy}>
              {mode === 'open' ? 'Open change' : 'Record merged change'}
            </button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => { setMode(null); setError(null); }}>Cancel</button>
          </div>
        </form>
      )}
    </section>
  );
}
