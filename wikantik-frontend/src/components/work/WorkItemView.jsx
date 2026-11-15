import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import Badge from '../ui/Badge';
import EmptyState from '../ui/EmptyState';
import Spinner from '../ui/Spinner';
import { useAuth } from '../../hooks/useAuth';
import { useDocumentTitle } from '../../hooks/useDocumentTitle';
import { useToast } from '../../hooks/useToast';
import {
  addComment, addLink, editComment, getItem, listItems, listMembers, listProducts, patchItem, removeComment, removeLink,
} from '../../api/work';
import { PRIORITY_OPTIONS, STATUSES, STATUS_LABELS } from './boardModel';
import WorkBodyEditor from './WorkBodyEditor';
import WorkComments from './WorkComments';
import WorkEpicView from './WorkEpicView';
import WorkFieldRow from './WorkFieldRow';
import WorkHandoff from './WorkHandoff';
import WorkLinks from './WorkLinks';
import WorkTimeline from './WorkTimeline';

const EPIC_STATUSES = ['draft', 'in_progress', 'done', 'cancelled'];
const KIND_OPTIONS = ['software', 'docs', 'admin'].map((k) => ({ value: k, label: k }));
const PARENT_LEVEL = { story: 'epic', task: 'story' };
const AUTONOMY_OPTIONS = [
  { value: '', label: 'Project default' }, { value: 'supervised', label: 'Supervised' },
  { value: 'delegated', label: 'Delegated' }, { value: 'autonomous', label: 'Autonomous' },
];
const DONE_MEANS_OPTIONS = [
  { value: '', label: 'Project default' }, { value: 'merged', label: 'Merged' }, { value: 'in_production', label: 'In production' },
];
const ALLOW_DIRECT_OPTIONS = [
  { value: '', label: 'Project default' }, { value: 'true', label: 'Yes' }, { value: 'false', label: 'No' },
];
const LATER = 'Takes effect in a later release';
const labelOf = (options, value) => options.find((o) => o.value === value)?.label ?? value;
const blankToNull = (v) => (v === '' ? null : v);
const dateText = (iso) => (iso ? new Date(iso).toLocaleDateString() : '');

/** What the server holds for a field, shown beside what the person typed after a 409. */
function theirValue(current, field) {
  const item = current?.item;
  if (!item) return '(unavailable)';
  if (field === 'assignee') return item.assigneeName || '(nobody)';
  if (field === 'parentKey' || field === 'productKey') return current[field] || '(none)';
  const v = item[field];
  return v == null || v === '' ? '(empty)' : String(v);
}

/** What the person typed, in words: a login is shown as the member's display name. */
function mineText(field, value, nameOf) {
  if (value == null || value === '') return field === 'assignee' ? '(nobody)' : '(empty)';
  return field === 'assignee' ? nameOf(value) : String(value);
}

function ConflictBanner({ conflict, nameOf, onReload, onKeep }) {
  const [review, setReview] = useState(false);
  return (
    <div className="work-conflict error-banner" role="alert">
      <p><strong>This item changed while you were editing.</strong> Nothing you typed has been discarded.</p>
      <div className="work-actions">
        <button type="button" className="btn btn-secondary btn-sm" onClick={onReload}>Reload</button>
        <button type="button" className="btn btn-ghost btn-sm" aria-expanded={review} onClick={() => setReview((r) => !r)}>Review their version</button>
      </div>
      {review && (
        <div className="work-conflict-review" data-testid="conflict-review">
          {Object.keys(conflict.changes).map((field) => (
            <div key={field} className="work-conflict-pair">
              <div>
                <h3 className="work-conflict-title">Their version ({field})</h3>
                <pre className="work-conflict-text">{theirValue(conflict.current, field)}</pre>
              </div>
              <div>
                <h3 className="work-conflict-title">Your unsaved text ({field})</h3>
                <pre className="work-conflict-text">{mineText(field, conflict.changes[field], nameOf)}</pre>
              </div>
            </div>
          ))}
          <button type="button" className="btn btn-primary btn-sm" onClick={onKeep}>Keep editing on top of theirs</button>
        </div>
      )}
    </div>
  );
}

function ItemPage({ itemKey }) {
  const toast = useToast();
  const { user } = useAuth();
  const [view, setView] = useState(null);
  const [notFound, setNotFound] = useState(false);
  const [loadError, setLoadError] = useState(null);
  const [members, setMembers] = useState([]);
  const [conflict, setConflict] = useState(null);
  const [products, setProducts] = useState([]);
  const [parents, setParents] = useState([]);
  const [fieldErrors, setFieldErrors] = useState({});
  const [epoch, setEpoch] = useState(0);
  const [rebase, setRebase] = useState(0);
  const [activity, setActivity] = useState(0);
  const seq = useRef(0);
  const projectKey = view?.projectKey;
  const currentLogin = user?.authenticated ? (user.loginPrincipal ?? null) : null;

  useDocumentTitle(view ? `${view.item.key} ${view.item.title}` : 'Work item');

  // Only the latest request may write state.
  const load = useCallback(() => {
    const mine = ++seq.current;
    return getItem(itemKey)
      .then((v) => {
        if (mine !== seq.current) return;
        setView(v);
        setNotFound(false);
        setLoadError(null);
      })
      .catch((e) => {
        if (mine !== seq.current) return;
        if (e?.status === 404) setNotFound(true);
        else setLoadError(e?.message || 'Could not load the item.');
      });
  }, [itemKey]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    if (!projectKey) return undefined;
    let cancelled = false;
    listMembers(projectKey)
      .then((rows) => { if (!cancelled) setMembers((rows || []).filter((m) => m.principalKind === 'user')); })
      .catch((e) => console.warn('WorkItemView: could not load the project members', e));
    return () => { cancelled = true; };
  }, [projectKey]);

  const level = view?.item.level;
  const role0 = view?.viewerRole;
  const editsRelations = (role0 === 'member' || role0 === 'lead') && level && level !== 'epic';
  useEffect(() => {
    if (!editsRelations || !projectKey) return undefined;
    let cancelled = false;
    listProducts()
      .then((rows) => { if (!cancelled) setProducts(rows || []); })
      .catch((e) => console.warn('WorkItemView: could not load the products', e));
    listItems({ project: projectKey, level: PARENT_LEVEL[level] })
      .then((rows) => { if (!cancelled) setParents(rows || []); })
      .catch((e) => console.warn('WorkItemView: could not load parent candidates', e));
    return () => { cancelled = true; };
  }, [editsRelations, projectKey, level]);

  const fail = useCallback((e, fallback) => {
    if (e?.status === 404) setNotFound(true);
    else toast.error(e?.message || fallback);
  }, [toast]);

  /**
   * PATCH; resolves true when accepted, 'restore' when the value was refused and the editor should close,
   * false to keep the caller's draft. `base` is the version the editor was opened against (never the refetched
   * one), so the server, not this page, decides whether a concurrent edit is overwritten. A 409 raises the banner.
   */
  const save = useCallback((changes, note, base, source = 'field') => {
    const body = { expectedVersion: base ?? view.item.version, changes };
    if (note) body.note = note;
    setFieldErrors({});
    return patchItem(itemKey, body)
      .then((next) => {
        seq.current += 1; // a load that started before this save must not replace the newer view
        setView(next);
        setConflict(null);
        setActivity((a) => a + 1);
        return true;
      })
      .catch((e) => {
        if (e?.status === 409) setConflict({ changes, current: e.current || null });
        else if (e?.status === 400 && e.body?.field) {
          setFieldErrors({ [e.body.field]: { message: e.body.message || e.message, source } });
          return e.body.field === 'parentKey' || e.body.field === 'productKey' ? 'restore' : false;
        } else fail(e, 'Could not save the change.');
        return false;
      });
  }, [view, itemKey, fail]);

  // An open editor whose base version fell behind the displayed one (a refetch brought someone else's edit):
  // raise the banner now instead of waiting for the save. Idempotent so repeated calls do not loop.
  const markStale = useCallback((changes) => setConflict((prev) => {
    if (prev && prev.current === view && JSON.stringify(prev.changes) === JSON.stringify(changes)) return prev;
    return { changes, current: view };
  }), [view]);

  /** Runs a discussion or link write, then refreshes the item (and the history). */
  const mutate = useCallback((call, failure) => call()
    .then(() => { setActivity((a) => a + 1); return load().then(() => true); })
    .catch((e) => { fail(e, failure); return false; }), [load, fail]);

  const nameOf = useCallback((login) => members.find((m) => m.principal === login)?.displayName
    || (view?.item.assignee === login ? view.item.assigneeName : null) || 'someone', [members, view]);

  const assigneeOptions = useMemo(() => {
    const opts = [{ value: '', label: 'Unassigned' }, ...members.map((m) => ({ value: m.principal, label: m.displayName }))];
    const a = view?.item.assignee;
    if (a && !opts.some((o) => o.value === a)) opts.push({ value: a, label: view.item.assigneeName || 'Former member' });
    return opts;
  }, [members, view]);

  if (notFound) return <EmptyState message="No such item" />;
  if (loadError && !view) return <EmptyState message={loadError} />;
  if (!view) return <Spinner label="Loading…" />;

  const { item } = view;
  const role = view.viewerRole;
  const canEdit = role === 'member' || role === 'lead';
  const isLead = role === 'lead';
  const isEpic = item.level === 'epic';
  const statusOptions = (isEpic ? EPIC_STATUSES : STATUSES).map((s) => ({ value: s, label: STATUS_LABELS[s] }));
  const field = (name) => (v, base) => save({ [name]: v }, undefined, base);
  const errEntry = (name) => (fieldErrors[name]?.source === 'handoff' ? undefined : fieldErrors[name]?.message);
  const err = errEntry;
  const handoffError = Object.values(fieldErrors).find((x) => x.source === 'handoff')?.message;
  const staleFor = (name) => (d) => markStale({ [name]: d });
  const withCurrent = (opts, value, label) => (value && !opts.some((o) => o.value === value) ? [...opts, { value, label }] : opts);
  const productOptions = withCurrent([{ value: '', label: 'None' },
    ...products.map((p) => ({ value: p.key, label: p.name ? `${p.key} – ${p.name}` : p.key }))], view.productKey, view.productKey);
  const parentOptions = withCurrent([{ value: '', label: 'None' },
    ...parents.filter((p) => p.key !== item.key).map((p) => ({ value: p.key, label: `${p.key} – ${p.title}` }))], view.parentKey, view.parentKey);

  return (
    <article className="work-item">
      <nav className="work-crumbs" aria-label="Breadcrumb">
        <Link to={`/work/${view.projectKey}`}>{view.projectKey}</Link>
        {view.parentKey && <> <span aria-hidden="true">/</span> <Link to={`/work/item/${view.parentKey}`}>{view.parentKey}</Link></>}
      </nav>
      <header className="work-item-head">
        <span className="work-item-key">{item.key}</span>
        <span className="work-card-level">{item.level}</span>
        {view.blocked && <Badge variant="danger" title="Blocked by an open item">Blocked</Badge>}
        <h1 className="work-heading">{item.title}</h1>
      </header>
      {conflict && (
        <ConflictBanner
          conflict={conflict} nameOf={nameOf}
          onReload={() => { setConflict(null); setEpoch((n) => n + 1); load(); }}
          onKeep={() => {
            setConflict(null);
            if (conflict.current) { setView(conflict.current); setRebase((n) => n + 1); }
            else load().then(() => setRebase((n) => n + 1));
          }}
        />
      )}
      <div className="work-item-layout">
        <div className="work-item-main">
          <WorkBodyEditor
            key={epoch} html={view.bodyHtml} body={item.body} canEdit={canEdit} error={err('body')}
            version={item.version} rebase={rebase} onStale={staleFor('body')}
            onSave={(body, base) => save({ body }, undefined, base)}
          />
          {isEpic && <WorkEpicView view={view} />}
          <WorkLinks
            links={view.links} blocked={view.blocked} canEdit={canEdit}
            onAdd={(kind, target) => mutate(() => addLink(itemKey, { kind, target }), 'Could not add the link.')}
            onRemove={(id) => mutate(() => removeLink(id), 'Could not remove the link.')}
          />
          <WorkComments
            comments={view.comments} members={members} canComment={canEdit} currentLogin={currentLogin} isLead={isLead}
            onAdd={(body) => mutate(() => addComment(itemKey, body), 'Could not post the comment.')}
            onEdit={(id, body) => mutate(() => editComment(id, body), 'Could not save the comment.')}
            onRemove={(id) => mutate(() => removeComment(id), 'Could not remove the comment.')}
          />
          <section className="work-section" aria-label="History">
            <h2 className="work-subheading">History</h2>
            <WorkTimeline itemKey={itemKey} refreshKey={activity} />
          </section>
        </div>
        <aside className="work-item-side" aria-label="Details" key={epoch}>
          <h2 className="work-subheading">Details</h2>
          <WorkFieldRow label="Title" error={err('title')} version={item.version} rebase={rebase} onStale={staleFor('title')} value={item.title} canEdit={canEdit} onSave={field('title')} />
          <WorkFieldRow label="Status" error={err('status')} version={item.version} rebase={rebase} onStale={staleFor('status')} type="select" value={item.status} display={STATUS_LABELS[item.status] || item.status}
            options={statusOptions} canEdit={canEdit} onSave={field('status')} />
          <WorkFieldRow label="Assignee" error={err('assignee')} version={item.version} rebase={rebase} onStale={staleFor('assignee')} type="select" value={item.assignee || ''} display={item.assigneeName || 'Unassigned'}
            options={assigneeOptions} parse={blankToNull} canEdit={canEdit} onSave={field('assignee')} />
          <WorkFieldRow label="Priority" error={err('priority')} version={item.version} rebase={rebase} onStale={staleFor('priority')} type="select" value={String(item.priority)} display={`P${item.priority}`}
            options={PRIORITY_OPTIONS} parse={Number} canEdit={canEdit} onSave={field('priority')} />
          <WorkFieldRow label="Due" error={err('dueOn')} version={item.version} rebase={rebase} onStale={staleFor('dueOn')} type="date" value={item.dueOn || ''} display={item.dueOn || 'No due date'}
            parse={blankToNull} canEdit={canEdit} onSave={field('dueOn')} />
          <WorkFieldRow label="Kind" error={err('kind')} version={item.version} rebase={rebase} onStale={staleFor('kind')} type="select" value={item.kind || 'software'} options={KIND_OPTIONS} canEdit={canEdit} onSave={field('kind')} />
          {!isEpic && (
            <>
              <WorkFieldRow label="Parent" type="select" value={view.parentKey || ''} display={view.parentKey || 'None'}
                options={parentOptions} parse={blankToNull} canEdit={canEdit} error={err('parentKey')} version={item.version} rebase={rebase} onStale={staleFor('parentKey')} onSave={field('parentKey')} />
              <WorkFieldRow label="Product" type="select" value={view.productKey || ''} display={view.productKey || 'None'}
                options={productOptions} parse={blankToNull} canEdit={canEdit} error={err('productKey')} version={item.version} rebase={rebase} onStale={staleFor('productKey')} onSave={field('productKey')} />
            </>
          )}
          {canEdit && (
            <WorkHandoff
              status={item.status} assignee={item.assignee} statusOptions={statusOptions} assigneeOptions={assigneeOptions}
              error={handoffError} version={item.version} rebase={rebase} onStale={(d) => markStale(d)}
              onSend={(status, assignee, note, base) => save({ status, assignee }, note, base, 'handoff').then((r) => r === true)}
            />
          )}
          <dl className="work-facts">
            <dt>Reporter</dt><dd>{item.reporterName || 'Unknown'}</dd>
            {isEpic && (view.derivedProductKeys || []).length > 0 && (<><dt>Products</dt><dd>{view.derivedProductKeys.join(', ')}</dd></>)}
            <dt>Created</dt><dd>{dateText(item.createdAt)}</dd>
            <dt>Updated</dt><dd>{dateText(item.updatedAt)}</dd>
          </dl>
          <h2 className="work-subheading">Policy</h2>
          <WorkFieldRow label="Autonomy" error={err('autonomy')} version={item.version} rebase={rebase} onStale={staleFor('autonomy')} type="select" value={item.autonomy || ''} display={labelOf(AUTONOMY_OPTIONS, item.autonomy || '')}
            options={AUTONOMY_OPTIONS} parse={blankToNull} canEdit={isLead} helper={LATER} onSave={field('autonomy')} />
          <WorkFieldRow label="Done means" error={err('doneMeans')} version={item.version} rebase={rebase} onStale={staleFor('doneMeans')} type="select" value={item.doneMeans || ''} display={labelOf(DONE_MEANS_OPTIONS, item.doneMeans || '')}
            options={DONE_MEANS_OPTIONS} parse={blankToNull} canEdit={isLead} helper={LATER} onSave={field('doneMeans')} />
          <WorkFieldRow label="Allow direct" error={err('allowDirect')} version={item.version} rebase={rebase} onStale={staleFor('allowDirect')} type="select" value={item.allowDirect == null ? '' : String(item.allowDirect)}
            display={labelOf(ALLOW_DIRECT_OPTIONS, item.allowDirect == null ? '' : String(item.allowDirect))}
            options={ALLOW_DIRECT_OPTIONS} parse={(v) => (v === '' ? null : v === 'true')} canEdit={isLead} helper={LATER} onSave={field('allowDirect')} />
        </aside>
      </div>
    </article>
  );
}

/** /work/item/:key — one item: text, fields, discussion, links and history (an epic adds its stories). */
export default function WorkItemView() {
  const { key } = useParams();
  return <ItemPage key={key} itemKey={key} />;
}
