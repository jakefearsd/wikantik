import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import EmptyState from '../ui/EmptyState';
import Spinner from '../ui/Spinner';
import { useDocumentTitle } from '../../hooks/useDocumentTitle';
import {
  approveDeployment, failDeployment, getDeployment, getDeploymentHistory, rollBackDeployment, startDeployment,
  verifyDeployment,
} from '../../api/work';
import { STATE_LABEL } from './WorkDeployments';

const who = (name, kind) => (name ? `${name}${kind === 'agent' ? ' (agent)' : ''}` : 'someone');
const when = (iso) => (iso ? new Date(iso).toLocaleString() : '');

/**
 * /work/deployments/:id — one deployment: what it carries (as far as the viewer can see), its evidence and
 * history, and the next step the viewer may take. Evidence is shown as plain text, never rendered.
 */
export default function WorkDeploymentView() {
  const { id } = useParams();
  const [d, setD] = useState(null);
  const [history, setHistory] = useState([]);
  const [missing, setMissing] = useState(false);
  const [evidence, setEvidence] = useState('');
  const [note, setNote] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  useDocumentTitle(d ? `${d.product} ${d.version}` : 'Deployment');

  const load = useCallback(() => getDeployment(id)
    .then((dep) => {
      setD(dep); setMissing(false);
      return getDeploymentHistory(id).then((h) => setHistory(h?.history || []));
    })
    .catch((e) => { if (e?.status === 404) setMissing(true); else setError(e?.message || 'Could not load the deployment.'); }), [id]);

  useEffect(() => { load(); }, [load]);

  function act(call) {
    if (busy) return;
    setBusy(true); setError(null);
    call().then(() => { setNote(''); return load(); })
      .catch((e) => setError(e?.message || 'That did not work.'))
      .finally(() => setBusy(false));
  }

  if (missing) return <EmptyState message="No such deployment." />;
  if (!d) return error ? <EmptyState message={error} /> : <Spinner label="Loading…" />;

  const body = { note: note.trim() || null };
  return (
    <article className="work-deployment-view">
      <p><Link to="/work/deployments">Deployments</Link></p>
      <h1 className="work-heading">{d.product} {d.version} <span className="work-chip">{STATE_LABEL[d.state] || d.state}</span></h1>
      <p className="work-muted">
        {d.environment} · requested by {who(d.requestedByName, d.requestedByKind)} {when(d.requestedAt)}
        {d.approvedByName && <> · approved by {who(d.approvedByName, d.approvedByKind)} {when(d.approvedAt)}</>}
      </p>

      <h2 className="work-subheading">Items it carries</h2>
      <ul className="work-link-list">
        {d.items.map((i) => (
          <li key={i.key}><Link to={`/work/item/${i.key}`}>{i.key}</Link> {i.title} <span className="work-muted">· {i.status}</span></li>
        ))}
      </ul>
      <p className="work-muted">Only the items in projects you belong to are listed.</p>

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
            <input className="work-field-input" aria-label="Note" placeholder="What failed" value={note} onChange={(e) => setNote(e.target.value)} />
            <button type="button" className="btn btn-sm" disabled={busy} onClick={() => act(() => failDeployment(id, body))}>Mark as failed</button>
          </div>
        </div>
      )}
      {(d.state === 'verified' || d.state === 'failed') && d.mayRun && (
        <div className="work-actions">
          <input className="work-field-input" aria-label="Note" placeholder="Why it was rolled back" value={note} onChange={(e) => setNote(e.target.value)} />
          <button type="button" className="btn btn-sm" disabled={busy} onClick={() => act(() => rollBackDeployment(id, body))}>Mark as rolled back</button>
        </div>
      )}

      <h2 className="work-subheading">History</h2>
      <ul className="work-timeline">
        {history.map((h) => (
          <li key={h.id}>
            {who(h.actorName, h.actorKind)} {h.from ? `moved it to ${STATE_LABEL[h.to] || h.to}` : 'requested it'}
            <span className="work-muted"> · {when(h.at)}</span>
            {h.note && <blockquote className="work-waiting-note">{h.note}</blockquote>}
          </li>
        ))}
      </ul>
    </article>
  );
}
