import { useCallback, useEffect, useState } from 'react';
import WorkConfirm from './WorkConfirm';
import { formatRelative } from '../../utils/datetime';
import {
  abandonChange, acknowledgeFlag, listChanges, mergeChange, openChange, requestReview, reviewChange,
} from '../../api/work';

const STATE_LABEL = { open: 'open', in_review: 'in review', merged: 'merged', abandoned: 'abandoned' };
const KIND_LABEL = { reviewed: 'merged after review', unreviewed: 'merged without review', direct: 'merged directly' };
const BLANK = { branch: '', headSha: '', reviewUrl: '', mergedSha: '', firstCommit: '' };
const MIN_YEAR = 1970;
const MAX_YEAR = 9999;

/** The ISO instant for a datetime-local value, or null when blank or not a real time in 1970-9999. */
function firstCommitInstant(value) {
  if (!value || !value.trim()) return null;
  const d = new Date(value);
  const y = d.getFullYear();
  if (Number.isNaN(d.getTime()) || y < MIN_YEAR || y > MAX_YEAR) return undefined;
  return d.toISOString();
}
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

const FLAG_LABEL = { unreviewed: 'without review', direct: 'direct commit' };

function who(name, kind) {
  if (!name) return null;
  return kind === 'agent' ? `${name} (agent)` : name;
}

/**
 * A wiki member by display name, or an author the code host named who is not a wiki user: shown by that name, marked
 * "(outside the wiki)", set apart in style, and never a link to a profile.
 */
function Person({ name, kind, external }) {
  const member = who(name, kind);
  if (member) return member;
  if (external) return <span className="work-outside-author">{external} (outside the wiki)</span>;
  return 'someone';
}

function Flag({ change: c, canAcknowledge, busy, acking, onAcknowledge, error }) {
  if (c.supersededBy != null) {
    return <div className="work-muted">recorded again as change {c.supersededBy}</div>;
  }
  if (!c.policyFlag) return null;
  return (
    <div className="work-change-flag">
      <span className="work-chip work-chip-warn">outside policy: {FLAG_LABEL[c.policyFlag] || c.policyFlag}</span>{' '}
      {c.flagAcknowledgedAt || c.flagAcknowledgedByName
        ? <span className="work-muted">acknowledged by {c.flagAcknowledgedByName || 'a lead'}</span>
        : canAcknowledge && (
          <button type="button" className="btn btn-sm" disabled={busy || acking}
            aria-label={`Acknowledge the flag on ${c.branch}`} onClick={onAcknowledge}>Acknowledge</button>
        )}
      {error && <div className="work-form-error" role="alert">{error}</div>}
    </div>
  );
}

function details(c, href) {
  const parts = [
    <>opened by <Person name={c.openedByName} kind={c.openedByKind} external={c.externalOpenedBy} /></>,
    c.headSha && `head ${short(c.headSha)}`,
    c.firstCommitAt && `first commit ${formatRelative(c.firstCommitAt)}`,
    c.approvedForHead && <>approved by <Person name={c.reviewedByName} kind={c.reviewedByKind} external={c.externalReviewedBy} /></>,
    c.state === 'merged' && <>merged by <Person name={c.mergedByName} kind={c.mergedByKind} external={c.externalMergedBy} /> · {short(c.mergedSha)}</>,
  ].filter(Boolean);
  const nodes = [];
  parts.forEach((p, i) => { if (i > 0) nodes.push(' · '); nodes.push(<span key={i}>{p}</span>); });
  if (href) nodes.push(' · ', <a key="review" href={href} target="_blank" rel="noopener noreferrer">review</a>);
  return nodes;
}

function ChangeRow({ change: c, canEdit, canAcknowledge, busy, act }) {
  const [acking, setAcking] = useState(false);
  const [ackError, setAckError] = useState(null);
  const [sha, setSha] = useState(c.headSha || '');
  const [note, setNote] = useState('');
  const [abandoning, setAbandoning] = useState(false);
  const [why, setWhy] = useState('');
  const [abandonError, setAbandonError] = useState(null);
  const live = c.state === 'open' || c.state === 'in_review';
  const href = c.reviewUrl ? httpHref(c.reviewUrl) : null;
  const stateText = c.state === 'merged' ? KIND_LABEL[c.mergeKind] || 'merged' : STATE_LABEL[c.state] || c.state;
  return (
    <li className="work-change" data-change-id={c.id}>
      <div className="work-change-head">
        <code>{c.branch}</code> {c.baseBranch && <span className="work-muted">→ {c.baseBranch}</span>}{' '}
        <span className="work-chip">{stateText}</span>
        {c.source === 'codehost' && <>{' '}<span className="work-chip">via GitHub</span></>}
      </div>
      <div className="work-muted">{details(c, href)}</div>
      <Flag change={c} canAcknowledge={canAcknowledge} busy={busy} acking={acking} error={ackError}
        onAcknowledge={() => {
          setAcking(true); setAckError(null);
          act(() => acknowledgeFlag(c.id), null, (e) => setAckError(e?.message || 'Could not acknowledge the flag.'))
            .finally(() => setAcking(false));
        }} />
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
              {!c.approvedForHead && (
                <button type="button" className="btn btn-sm" disabled={busy}
                  onClick={() => act(() => reviewChange(c.id, { approved: true, note: note || null }))}>Approve</button>
              )}
              <button type="button" className="btn btn-sm" disabled={busy}
                onClick={() => act(() => reviewChange(c.id, { approved: false, note: note || null }))}>Send back</button>
            </>
          )}
          <label className="work-inline-label">
            <span className="work-muted">Merged commit</span>{' '}
            <input className="work-field-input" aria-label={`Merged commit for ${c.branch}`} placeholder="Merged commit"
              value={sha} onChange={(e) => setSha(e.target.value)} />
          </label>
          <button type="button" className="btn btn-sm" disabled={busy || !sha.trim()}
            onClick={() => act(() => mergeChange(c.id, { mergedSha: sha.trim() }))}>Record merge</button>
          {!abandoning && (
            <button type="button" className="btn btn-ghost btn-sm" disabled={busy}
              onClick={() => { setAbandoning(true); setAbandonError(null); }}>Abandon</button>
          )}
        </div>
      )}
      {canEdit && live && abandoning && (
        <WorkConfirm yes="Abandon" error={abandonError}
          onYes={() => act(() => abandonChange(c.id, { note: why.trim() || null }), null,
            (e) => setAbandonError(e?.message || 'That did not work.'))}
          onNo={() => { setAbandoning(false); setWhy(''); setAbandonError(null); }}
          fields={(
            <label className="work-form-field">Why (optional)
              <input className="work-field-input" value={why} onChange={(e) => setWhy(e.target.value)} />
            </label>
          )}>
          Abandon this change? It cannot be reopened.
        </WorkConfirm>
      )}
    </li>
  );
}

/**
 * The item's changes: branches of work from open to merged. It loads its own list and reloads after each action;
 * a refusal from the server is shown in place and nothing typed is lost. `onChanged` tells the item view to
 * refresh its timeline.
 */
export default function WorkChanges({ itemKey, canEdit = false, canAcknowledge = false, refreshKey = 0, onChanged }) {
  const [changes, setChanges] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [mode, setMode] = useState(null); // null | 'open' | 'merged'
  const [f, setF] = useState(BLANK);

  const [loadError, setLoadError] = useState(null);

  // A flag can clear on its own (a late approval): always show what the server says now, and keep the last good list
  // when a reload fails.
  const load = useCallback(() => listChanges(itemKey)
    .then((r) => { setChanges(r?.changes || []); setLoadError(null); })
    .catch((e) => {
      console.warn('WorkChanges: could not load the changes', e);
      setLoadError(e?.message || 'Could not load the changes.');
    }), [itemKey]);

  useEffect(() => { load(); }, [load, refreshKey]);

  function act(call, after, onError) {
    if (busy) return Promise.resolve();
    setBusy(true); setError(null);
    return call()
      .then(() => { after?.(); onChanged?.(); return load(); })
      .catch((e) => (onError ? onError(e) : setError(e?.message || 'That did not work.')))
      .finally(() => setBusy(false));
  }

  function submit(e) {
    e.preventDefault();
    if (!f.branch.trim()) { setError('Name the branch.'); return; }
    if (mode === 'merged' && !f.mergedSha.trim()) { setError('Name the merged commit.'); return; }
    const first = firstCommitInstant(f.firstCommit);
    if (first === undefined) { setError('Give the first commit as a real date and time between 1970 and 9999, or leave it blank.'); return; }
    const body = mode === 'merged'
      ? { branch: f.branch.trim(), mergedSha: f.mergedSha.trim() }
      : { branch: f.branch.trim(), headSha: f.headSha.trim() || null, reviewUrl: f.reviewUrl.trim() || null };
    if (first) body.firstCommitAt = first;
    act(() => openChange(itemKey, body), () => { setF(BLANK); setMode(null); });
  }

  const set = (name) => (e) => setF((s) => ({ ...s, [name]: e.target.value }));

  return (
    <section className="work-section" aria-label="Changes">
      <h2 className="work-subheading">Changes</h2>
      {loadError && (
        <div className="work-form-error" role="alert">
          {loadError} <button type="button" className="btn btn-sm" onClick={load}>Retry</button>
        </div>
      )}
      {changes === null ? (loadError ? null : <p className="work-muted">Loading…</p>)
        : changes.length === 0 ? <p className="work-muted">No changes.</p> : (
          <ul className="work-change-list">
            {changes.map((c) => <ChangeRow key={c.id} change={c} canEdit={canEdit} canAcknowledge={canAcknowledge} busy={busy} act={act} />)}
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
          <label className="work-form-field" htmlFor="work-change-first">First commit (optional)</label>
          <input id="work-change-first" type="datetime-local" className="work-field-input" value={f.firstCommit}
            onChange={set('firstCommit')} />
          <p className="work-muted">When work on this change began. It starts the lead-time clock, which never starts more than 30 days before the change was opened.</p>
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
