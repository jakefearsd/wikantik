import { useState, useEffect, useMemo, useRef } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import Modal from './ui/Modal';
import Select from './ui/Select';
import Spinner from './ui/Spinner';
import EmptyState from './ui/EmptyState';

const UNSET = Symbol('unset');
const ROW_CAP = 200;
const POLL_MS = 1000;
const POLL_MAX_MS = 10000;
const STATUS = {
  NEW: ['New', ''],
  CREATED: ['Created', ''],
  WILL_FAIL: ['Will fail', 'import-dialog-status-bad'],
  FAILED: ['Failed', 'import-dialog-status-bad'],
  SKIPPED_EXISTS: ['Already exists', 'import-dialog-status-muted'],
  SKIPPED_RESERVED: ['Reserved name', 'import-dialog-status-muted'],
  SKIPPED_UNREFERENCED: ['Not referenced', 'import-dialog-status-muted'],
};
const statusInfo = (status) => STATUS[status] || [String(status), ''];

const MODES = [
  ['folders', 'Folders become clusters'],
  ['fixed', 'Put everything in cluster…'],
  ['none', 'No clusters'],
];

function jobPhase(job) {
  return job.state === 'RUNNING' ? 'running' : 'result';
}

function Totals({ totals: t }) {
  return (
    <div className="import-dialog-totals" data-testid="import-totals">
      <div>{t.pagesNew} new · {t.pagesSkippedExisting} already exist · {t.pagesSkippedReserved} reserved · {t.pagesFailing} will fail</div>
      <div>{t.attachments} attachments ({t.attachmentsBlocked} blocked, {t.attachmentsSkipped} unreferenced)</div>
      <div>Clusters: {t.hubsCreate} new hubs, {t.clustersJoin} joined</div>
    </div>
  );
}

function PlanTable({ pages }) {
  const [filter, setFilter] = useState('');
  const matches = useMemo(() => {
    const q = filter.trim().toLowerCase();
    if (!q) return pages;
    return pages.filter((p) => p.vaultPath.toLowerCase().includes(q) || p.name.toLowerCase().includes(q));
  }, [pages, filter]);
  const shown = matches.slice(0, ROW_CAP);
  return (
    <>
      <input
        type="text"
        className="form-input"
        placeholder="Filter by path or page name…"
        aria-label="Filter pages"
        data-testid="import-filter"
        value={filter}
        onChange={(e) => setFilter(e.target.value)}
        style={{ marginBottom: 'var(--space-sm)' }}
      />
      <div className="import-dialog-tablewrap">
        <table className="import-dialog-table" data-testid="import-pages">
          <thead>
            <tr><th>Vault path</th><th>Page</th><th>Status</th><th>Notes</th></tr>
          </thead>
          <tbody>
            {shown.map((p) => (
              <tr key={p.vaultPath}>
                <td>{p.vaultPath}</td>
                <td>{p.name}</td>
                <td className={statusInfo(p.status)[1] || undefined}>{statusInfo(p.status)[0]}</td>
                <td>{p.reason || (p.warnings || []).join('; ')}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {matches.length > shown.length && (
        <div className="import-dialog-more">… {matches.length - shown.length} more</div>
      )}
      {matches.length === 0 && <EmptyState message="No pages match the filter." />}
    </>
  );
}

function Attachments({ attachments }) {
  const problems = (attachments || []).filter((a) => a.status === 'BLOCKED' || a.status === 'FAILED');
  if (!problems.length) return null;
  return (
    <details className="import-dialog-more" data-testid="import-attachments">
      <summary>{problems.length} attachments will not be imported</summary>
      <ul className="import-dialog-warnings">
        {problems.map((a) => <li key={a.vaultPath}>{a.vaultPath} — {a.reason || a.status}</li>)}
      </ul>
    </details>
  );
}

function Warnings({ groups }) {
  const entries = Object.entries(groups || {});
  if (!entries.length) return null;
  return (
    <ul className="import-dialog-warnings" data-testid="import-warnings">
      {entries.map(([kind, n]) => <li key={kind}>{n} × {kind}</li>)}
    </ul>
  );
}

function Progress({ job }) {
  return (
    <div data-testid="import-progress">
      <progress className="import-dialog-progress" aria-label="Import progress" value={job.done || 0} max={job.total || 1} />
      <div className="import-dialog-progress-label" role="status" aria-live="polite">
        {job.done || 0} / {job.total || 0}{job.current ? ` — ${job.current}` : ''}
      </div>
      <p className="import-dialog-more">You can close this dialog; the import keeps running.</p>
    </div>
  );
}

function pageLink(name) {
  return <Link to={`/wiki/${encodeURIComponent(name)}`}>{name}</Link>;
}

function Result({ job }) {
  const results = job.results || [];
  const created = results.filter((r) => r.kind === 'page' && r.status === 'CREATED');
  const skipped = results.filter((r) => String(r.status).startsWith('SKIPPED'));
  const failed = results.filter((r) => r.status === 'FAILED');
  const hub = job.summary?.hubs?.[0];
  return (
    <div data-testid="import-result">
      {job.state === 'FAILED' && <div className="error-banner">{job.message || 'The import failed.'}</div>}
      <p>{created.length} pages created{skipped.length ? `, ${skipped.length} skipped` : ''}{failed.length ? `, ${failed.length} failed` : ''}.</p>
      {hub && <p><Link to={`/wiki/${encodeURIComponent(hub)}`} data-testid="import-open-hub">Open imported hub</Link></p>}
      <ul className="import-dialog-result-list">
        {created.map((r) => <li key={`c-${r.name}`}>{pageLink(r.name)}</li>)}
        {skipped.map((r) => <li key={`s-${r.kind}-${r.name}`}>{r.name} — skipped: {r.reason}</li>)}
        {failed.map((r) => <li key={`f-${r.kind}-${r.name}`} className="import-dialog-status-bad">{r.name} — failed: {r.reason}</li>)}
      </ul>
    </div>
  );
}

/**
 * "Import Obsidian vault" dialog: pick a zip, review a server-computed plan,
 * apply it as a background job and watch progress. Closing never cancels the
 * job; reopening resumes it via GET /jobs/current.
 */
export default function ImportDialog({ isOpen, onClose }) {
  const [phase, setPhase] = useState('choose');
  const [file, setFile] = useState(null);
  const [options, setOptions] = useState({ clusterMode: 'folders', cluster: '' });
  const [plan, setPlan] = useState(null);
  const [job, setJob] = useState(null);
  const [error, setError] = useState(null);
  const [clusters, setClusters] = useState(null);
  const [loading, setLoading] = useState(true);
  const [applying, setApplying] = useState(false);
  const planSeq = useRef(0);
  const touched = useRef(false);

  const [prevIsOpen, setPrevIsOpen] = useState(UNSET);
  if (isOpen !== prevIsOpen) {
    setPrevIsOpen(isOpen);
    if (isOpen) {
      setPhase('choose');
      setFile(null);
      setOptions({ clusterMode: 'folders', cluster: '' });
      setPlan(null);
      setJob(null);
      setError(null);
      setLoading(true);
      setApplying(false);
    }
  }

  // On open: resume the user's current/most recent job, if any.
  useEffect(() => {
    if (!isOpen) return undefined;
    let cancelled = false;
    touched.current = false;
    api.importVault.current()
      .then((data) => {
        if (cancelled) return;
        setLoading(false);
        if (!data || touched.current) return;
        setJob(data);
        setPhase(jobPhase(data));
      })
      .catch((err) => {
        if (cancelled) return;
        setLoading(false);
        if (err?.status === 404 || touched.current) return;
        console.warn('ImportDialog: failed to load the current import job', err);
        setError(err?.message || 'Failed to load the current import job.');
      });
    return () => { cancelled = true; };
  }, [isOpen]);

  // Poll the running job (1 s, exponential backoff on errors); stops on
  // unmount, close, or any terminal state.
  const jobId = job?.jobId;
  useEffect(() => {
    if (!isOpen || phase !== 'running' || !jobId) return undefined;
    let cancelled = false;
    let timer = null;
    let failures = 0;
    const schedule = () => {
      const delay = Math.min(POLL_MS * 2 ** failures, POLL_MAX_MS);
      timer = setTimeout(tick, delay);
    };
    const tick = () => {
      api.importVault.job(jobId)
        .then((data) => {
          if (cancelled) return;
          failures = 0;
          setError(null);
          setJob(data);
          if (data.state === 'RUNNING') schedule(); else setPhase('result');
        })
        .catch((err) => {
          if (cancelled) return;
          if (err?.status === 404) {
            console.warn('ImportDialog: job no longer available', err);
            setError("This import's results are no longer available.");
            setPhase('choose');
            return;
          }
          failures += 1;
          console.warn('ImportDialog: job poll failed', err);
          setError(err?.message || 'Lost contact with the import job; retrying…');
          schedule();
        });
    };
    schedule();
    return () => { cancelled = true; clearTimeout(timer); };
  }, [isOpen, phase, jobId]);

  // Load cluster names once, the first time "fixed" is chosen.
  const needClusters = isOpen && options.clusterMode === 'fixed' && clusters === null;
  useEffect(() => {
    if (!needClusters) return undefined;
    let cancelled = false;
    api.listClusters()
      .then((res) => { if (!cancelled) setClusters((res?.clusters || []).map((c) => c.name)); })
      .catch((err) => {
        console.warn('ImportDialog: failed to load clusters', err);
        if (!cancelled) { setClusters([]); setError(err?.message || 'Failed to load clusters.'); }
      });
    return () => { cancelled = true; };
  }, [needClusters]);

  if (!isOpen) return null;

  const runPlan = (f, opts, notice = null) => {
    const seq = ++planSeq.current;
    setPhase('planning');
    setError(notice);
    api.importVault.plan(f, opts)
      .then((data) => {
        if (seq !== planSeq.current) return;
        setPlan(data);
        setPhase('plan');
      })
      .catch((err) => {
        if (seq !== planSeq.current) return;
        console.warn('ImportDialog: plan request failed', err);
        setPlan(null);
        setError(err?.message || 'Could not plan the import.');
        setPhase('choose');
      });
  };

  const onFile = (e) => {
    const f = e.target.files?.[0];
    if (!f) return;
    touched.current = true;
    setFile(f);
    if (options.clusterMode === 'fixed' && !options.cluster) { setPlan(null); setPhase('choose'); return; }
    runPlan(f, options);
  };

  const changeOptions = (next) => {
    touched.current = true;
    setOptions(next);
    if (!file) return;
    if (next.clusterMode === 'fixed' && !next.cluster) { planSeq.current++; setPlan(null); setPhase('choose'); return; }
    runPlan(file, next);
  };

  const showCurrent = () => api.importVault.current()
    .then((data) => { setJob(data); setPhase(jobPhase(data)); })
    .catch((err) => {
      console.warn('ImportDialog: failed to load the running import job', err);
      setError(err?.message || 'An import is already running.');
    });

  const onApply = () => {
    if (applying) return;
    setApplying(true);
    setError(null);
    api.importVault.apply(file, options, plan.planHash)
      .then((res) => {
        setJob({ jobId: res.jobId, state: 'RUNNING', done: 0, total: 0, results: [], summary: {} });
        setPhase('running');
      })
      .catch((err) => {
        console.warn('ImportDialog: apply failed', err);
        setApplying(false);
        const msg = err?.message || 'Import failed.';
        if (err?.status === 409 && /changed since/.test(msg)) runPlan(file, options, msg);
        else if (err?.status === 409 && /already running/i.test(msg)) { setError(msg); showCurrent(); }
        else setError(msg);
      });
  };

  const planning = phase === 'planning';
  const picking = phase === 'choose' || phase === 'planning' || phase === 'plan';

  return (
    <Modal isOpen={isOpen} onClose={onClose} labelledBy="import-dialog-title" className="search-dialog export-dialog import-dialog" testId="import-dialog">
      <h2 id="import-dialog-title" style={{ fontFamily: 'var(--font-display)', fontSize: '1.5rem', marginBottom: 'var(--space-lg)', textAlign: 'center' }}>
        Import Obsidian vault
      </h2>
      {error && <div className="error-banner" role="alert" style={{ marginBottom: 'var(--space-md)' }}>{error}</div>}

      {loading && <Spinner label="Checking for a running import…" />}
      {picking && !loading && (
        <>
          <label className="field-label" htmlFor="import-file">Vault (.zip)</label>
          <input id="import-file" type="file" accept=".zip,application/zip" data-testid="import-file" onChange={onFile} style={{ marginBottom: 'var(--space-md)' }} />
          <div className="import-dialog-modes" role="radiogroup" aria-label="Clusters" data-testid="import-cluster-mode">
            {MODES.map(([value, label]) => (
              <label key={value}>
                <input
                  type="radio"
                  name="import-cluster-mode"
                  checked={options.clusterMode === value}
                  onChange={() => changeOptions({ clusterMode: value, cluster: value === 'fixed' ? options.cluster : '' })}
                />
                {label}
              </label>
            ))}
            {options.clusterMode === 'fixed' && (
              <Select
                value={options.cluster}
                options={clusters || []}
                onChange={(cluster) => changeOptions({ ...options, cluster })}
                placeholder="Choose a cluster…"
                ariaLabel="Cluster"
                data-testid="import-cluster"
              />
            )}
          </div>
        </>
      )}

      {planning && <Spinner label="Planning import…" />}
      {phase === 'plan' && plan && (
        <>
          <Totals totals={plan.totals} />
          <PlanTable pages={plan.pages || []} />
          <Attachments attachments={plan.attachments} />
          <Warnings groups={plan.warningGroups} />
        </>
      )}
      {phase === 'running' && job && <Progress job={job} />}
      {phase === 'result' && job && <Result job={job} />}

      <div className="modal-actions">
        <button type="button" className="btn btn-ghost" onClick={onClose}>{phase === 'running' ? 'Close (keeps running)' : 'Close'}</button>
        {phase === 'result' && (
          <button type="button" className="btn btn-primary" onClick={() => { setJob(null); setPlan(null); setFile(null); setPhase('choose'); }}>
            Import another
          </button>
        )}
        {picking && (
          <button
            type="button"
            className="btn btn-primary"
            data-testid="import-apply"
            disabled={applying || planning || phase !== 'plan' || !plan || plan.totals.pagesNew === 0}
            onClick={onApply}
          >
            Import {plan ? plan.totals.pagesNew : 0} pages
          </button>
        )}
      </div>
    </Modal>
  );
}
