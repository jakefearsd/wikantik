import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { listAgents, listProducts, listQueues } from '../../api/work';
import { useToast } from '../../hooks/useToast';
import { createStarterQueues } from './starterQueues';

const DISMISS_KEY = 'wikantik.work.gettingStarted.dismissed';

function readDismissed() {
  try { return window.localStorage.getItem(DISMISS_KEY) != null; } catch (err) {
    console.warn('WorkGettingStarted: could not read the dismissed flag', err);
    return false;
  }
}

function writeDismissed() {
  try { window.localStorage.setItem(DISMISS_KEY, new Date().toISOString()); } catch (err) {
    console.warn('WorkGettingStarted: could not store the dismissed flag', err);
  }
}

function Step({ id, done, label, children }) {
  return (
    <li data-testid={`step-${id}`}>
      <span className="work-chip">{done ? 'Done' : 'Not yet'}</span> {label}
      {!done && <> {children}</>}
    </li>
  );
}

/**
 * The first-run card on /work, for wiki admins only. `projects` is the viewer's project list from getMe; `onCreateProject` opens the new-project
 * form in place. Any failed load leaves the card out: it must never block the page.
 */
export default function WorkGettingStarted({ isAdmin, projects, onCreateProject }) {
  const toast = useToast();
  const [dismissed, setDismissed] = useState(readDismissed);
  const [data, setData] = useState(null); // admins: { products, queues, agents }
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);

  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);

  const hasProject = (projects || []).some((p) => !p.archived);

  const load = useCallback(() => Promise.resolve().then(() => Promise.all([listProducts(), listQueues(), listAgents()]))
    .then(([products, queues, agents]) => mounted.current && setData({ products: products || [], queues: queues || [], agents: agents || [] }))
    .catch((err) => {
      console.warn('WorkGettingStarted: could not load the setup state', err);
      if (mounted.current) setFailed(true);
    }), []);

  useEffect(() => {
    if (!isAdmin || dismissed) return undefined;
    load();
    return undefined;
  }, [isAdmin, dismissed, load]);

  function dismiss() {
    writeDismissed();
    setDismissed(true);
  }

  function starterQueues() {
    if (busy) return;
    setBusy(true);
    createStarterQueues((data?.queues || []).map((q) => q.key))
      .then(({ created, failed: bad }) => {
        if (created.length) toast.success(`Created ${created.join(' and ')}`);
        if (bad.length) toast.error(`Could not create ${bad.join(' and ')}.`);
        return load();
      })
      .finally(() => { if (mounted.current) setBusy(false); });
  }

  if (dismissed || failed) return null;

  const dismissButton = <button type="button" className="btn btn-ghost btn-sm" onClick={dismiss}>Dismiss</button>;

  // A non-admin gets no card: WorkHome's empty project list already says who to ask.
  if (!isAdmin || !data) return null;
  const product = data.products.some((p) => !p.archived);
  const queue = data.queues.some((q) => !q.retiredAt);
  const agent = data.agents.some((a) => !a.retiredAt);
  // An admin sees the card until a project and an active product, queue and agent all exist.
  if (hasProject && product && queue && agent) return null;

  return (
    <section className="work-getting-started" aria-labelledby="work-getting-started-title">
      <h2 id="work-getting-started-title" className="work-subheading">Getting started</h2>
      <ul className="work-getting-started-steps">
        <Step id="product" done={product} label="Create a product (optional unless you track branches or deploys)">
          <Link to="/work/products">Create a product</Link>
        </Step>
        <Step id="project" done={hasProject} label="Create a project">
          <button type="button" className="btn btn-sm" onClick={onCreateProject}>Create a project</button>
        </Step>
        <Step id="queues" done={queue} label="Create queues">
          <button type="button" className="btn btn-sm" disabled={busy} onClick={starterQueues}>Create starter queues</button>
        </Step>
        <Step id="agent" done={agent} label="Register an agent (optional)">
          <Link to="/work/agents">Register an agent</Link>
        </Step>
      </ul>
      <p className="work-muted">New epics and agent-created items start as drafts, and the board hides drafts until you choose Show.</p>
      {dismissButton}
    </section>
  );
}
