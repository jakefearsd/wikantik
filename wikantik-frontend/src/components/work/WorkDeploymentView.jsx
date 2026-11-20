import { useCallback, useEffect, useState } from 'react';
import { STATUS_LABELS } from './boardModel';
import { Link, useParams } from 'react-router-dom';
import EmptyState from '../ui/EmptyState';
import Spinner from '../ui/Spinner';
import { useAuth } from '../../hooks/useAuth';
import { useDocumentTitle } from '../../hooks/useDocumentTitle';
import {
  approveDeployment, failDeployment, getDeployment, getDeploymentHistory, rejectDeployment, rollBackDeployment,
  startDeployment, verifyDeployment,
} from '../../api/work';
import WorkConfirm from './WorkConfirm';
import { stateLabel as stateText } from './WorkDeployments';

const who = (name, kind) => (name ? `${name}${kind === 'agent' ? ' (agent)' : ''}` : 'someone');
const when = (iso) => (iso ? new Date(iso).toLocaleString() : '');

function historyVerb(h) {
  if (h.from) return `moved it to ${stateText(h.to)}`;
  return h.to === 'requested' ? 'requested it' : `set it to ${stateText(h.to)}`;
}

/**
 * /work/deployments/:id — one deployment: what it carries (as far as the viewer can see), its evidence and
 * history, and the next step the viewer may take. Evidence is shown as plain text, never rendered.
 */
export default function WorkDeploymentView() {
  const { id } = useParams();
  const { user } = useAuth();
  const isAdmin = !!user?.roles?.includes('Admin');
  const [d, setD] = useState(null);
  const [history, setHistory] = useState([]);
  const [missing, setMissing] = useState(false);
  const [evidence, setEvidence] = useState('');
  const [note, setNote] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState(null); // null | 'reject' | 'fail' | 'rollback'
  const [confirmError, setConfirmError] = useState(null);
  useDocumentTitle(d ? `${d.product} ${d.version}` : 'Deployment');

  const load = useCallback(() => getDeployment(id)
    .then((dep) => {
      setD(dep); setMissing(false);
      return getDeploymentHistory(id).then((h) => setHistory(h?.history || []));
    })
    .catch((e) => { if (e?.status === 404) setMissing(true); else setError(e?.message || 'Could not load the deployment.'); }), [id]);

  useEffect(() => { load(); }, [load]);

  function act(call, inStrip = false) {
    if (busy) return;
    setBusy(true); setError(null); setConfirmError(null);
    call().then(() => { setNote(''); setReason(''); setConfirm(null); return load(); })
      .catch((e) => {
        const message = e?.message || 'That did not work.';
        if (inStrip) setConfirmError(message); else setError(message);
      })
      .finally(() => setBusy(false));
  }
  const ask = (which) => { setConfirm(which); setConfirmError(null); setNote(''); setReason(''); };
  const closeConfirm = () => { setConfirm(null); setConfirmError(null); };
  const noteField = (placeholder) => (
    <label className="work-form-field">Why (optional)
      <input className="work-field-input" placeholder={placeholder} value={note} onChange={(e) => setNote(e.target.value)} />
    </label>
  );

  if (missing) return <EmptyState message="No such deployment." />;
  if (!d) return error ? <EmptyState message={error} /> : <Spinner label="Loading…" />;

  const body = { note: note.trim() || null };
  const rejectLabel = d.mayApprove || d.state === 'approved' ? 'Reject' : 'Withdraw';
  return (
    <article className="work-deployment-view">
      <h1 className="work-heading">
        <Link to="/work">Work</Link> <span aria-hidden="true">▸</span> <Link to="/work/deployments">Deployments</Link>{' '}
        <span aria-hidden="true">▸</span> {d.product} {d.version} <span className="work-chip">{stateText(d.state)}</span></h1>
      <p className="work-muted">
        {d.environment} · requested by {who(d.requestedByName, d.requestedByKind)} {when(d.requestedAt)}
        {d.approvedByName && <> · approved by {who(d.approvedByName, d.approvedByKind)} {when(d.approvedAt)}</>}
      </p>

      <h2 className="work-subheading">Items it carries</h2>
      <ul className="work-link-list">
        {d.items.map((i) => (
          <li key={i.key}><Link to={`/work/item/${i.key}`}>{i.key}</Link> {i.title} <span className="work-muted">· {STATUS_LABELS[i.status] || i.status}</span></li>
        ))}
      </ul>
      {!isAdmin && <p className="work-muted">Only the items in projects you belong to are listed.</p>}

      {d.evidence && (
        <section aria-label="Evidence">
          <h2 className="work-subheading">Evidence</h2>
          <pre className="work-evidence">{d.evidence}</pre>
        </section>
      )}

      {error && <div className="work-form-error" role="alert">{error}</div>}
      {d.state === 'requested' && d.mayApprove && (
        <div className="work-actions">
          <input className="work-field-input" aria-label="Note" placeholder="Note (optional)" value={note} onChange={(e) => setNote(e.target.value)} />
          <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => act(() => approveDeployment(id, body))}>Approve</button>
        </div>
      )}
      {d.state === 'requested' && !d.mayApprove && <p className="work-muted">Waiting for someone who may approve deployments of this product.</p>}
      {(d.state === 'requested' || d.state === 'approved') && confirm !== 'reject' && (
        <div className="work-actions">
          <button type="button" className="btn btn-ghost btn-sm" disabled={busy} onClick={() => ask('reject')}>{rejectLabel}</button>
        </div>
      )}
      {(d.state === 'requested' || d.state === 'approved') && confirm === 'reject' && (
        <WorkConfirm yes={rejectLabel} error={confirmError} onNo={closeConfirm}
          onYes={() => act(() => rejectDeployment(id, { note: reason.trim() || null }), true)}
          fields={(
            <label className="work-form-field">Why (optional)
              <input className="work-field-input" placeholder="Why it will not go ahead" value={reason}
                onChange={(e) => setReason(e.target.value)} />
            </label>
          )}>
          {rejectLabel === 'Reject' ? 'Reject this deployment? It will not run.' : 'Withdraw this request? It will not run.'}
        </WorkConfirm>
      )}
      {d.state === 'approved' && d.mayRun && (
        <div className="work-actions">
          <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => act(() => startDeployment(id))}>Mark as running</button>
        </div>
      )}
      {d.state === 'running' && d.mayRun && (
        <div className="work-form">
          <label className="work-form-field" htmlFor="work-dep-evidence">Health and smoke-test output</label>
          <textarea id="work-dep-evidence" className="work-field-input" rows={6} value={evidence} onChange={(e) => setEvidence(e.target.value)} />
          <div className="work-actions">
            <button type="button" className="btn btn-primary btn-sm" disabled={busy}
              onClick={() => (evidence.trim() ? act(() => verifyDeployment(id, { evidence })) : setError('Attach the health and smoke-test output before marking this verified.'))}>
              Mark as verified
            </button>
            {confirm !== 'fail' && (
              <button type="button" className="btn btn-sm" disabled={busy} onClick={() => ask('fail')}>Mark as failed</button>
            )}
          </div>
          {confirm === 'fail' && (
            <WorkConfirm yes="Mark as failed" error={confirmError} onNo={closeConfirm}
              onYes={() => act(() => failDeployment(id, body), true)} fields={noteField('What failed')}>
              Mark this deployment as failed?
            </WorkConfirm>
          )}
        </div>
      )}
      {(d.state === 'verified' || d.state === 'failed') && d.mayRun && confirm !== 'rollback' && (
        <div className="work-actions">
          <button type="button" className="btn btn-sm" disabled={busy} onClick={() => ask('rollback')}>Mark as rolled back</button>
        </div>
      )}
      {(d.state === 'verified' || d.state === 'failed') && d.mayRun && confirm === 'rollback' && (
        <WorkConfirm yes="Mark as rolled back" error={confirmError} onNo={closeConfirm}
          onYes={() => act(() => rollBackDeployment(id, body), true)} fields={noteField('Why it was rolled back')}>
          Mark this deployment as rolled back? Items it shipped will no longer count as shipped.
        </WorkConfirm>
      )}

      <h2 className="work-subheading">History</h2>
      <ul className="work-timeline">
        {history.map((h) => (
          <li key={h.id}>
            {who(h.actorName, h.actorKind)} {historyVerb(h)}
            <span className="work-muted"> · {when(h.at)}</span>
            {h.note && <blockquote className="work-waiting-note">{h.note}</blockquote>}
          </li>
        ))}
      </ul>
    </article>
  );
}
