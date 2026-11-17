import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import Badge from '../ui/Badge';
import EmptyState from '../ui/EmptyState';
import Spinner from '../ui/Spinner';
import { useDocumentTitle } from '../../hooks/useDocumentTitle';
import { useToast } from '../../hooks/useToast';
import {
  addComment, addLink, editComment, getItem, handoffItem, listItems, listMembers, listProducts, listQueues, patchItem,
  releaseItem, removeComment, removeLink,
} from '../../api/work';
import { ARCHIVED_TEXT, isArchivedRefusal } from './archived';
import { PRIORITY_OPTIONS, STATUSES, STATUS_LABELS } from './boardModel';
import WorkBodyEditor from './WorkBodyEditor';
import WorkComments from './WorkComments';
import WorkEpicView from './WorkEpicView';
import AssigneePicker, { targetOf, targetValue } from './AssigneePicker';
import WorkClaim from './WorkClaim';
import WorkFieldRow from './WorkFieldRow';
import WorkHandoff, { handoffShape } from './WorkHandoff';
import WorkChanges from './WorkChanges';
import WorkLinks from './WorkLinks';
import WorkPolicyNote from './WorkPolicyNote';
import WorkShippedIn from './WorkShippedIn';
import WorkTimeline from './WorkTimeline';
import { isPast, useExpiryClock } from './workTime';

const EPIC_STATUSES = ['draft', 'in_progress', 'done', 'cancelled'];
const KIND_OPTIONS = ['software', 'docs', 'admin'].map((k) => ({ value: k, label: k }));
const PARENT_LEVEL = { story: 'epic', task: 'story' };
const AUTONOMY_OPTIONS = [
  { value: '', label: 'Inherited' }, { value: 'supervised', label: 'Supervised' },
  { value: 'delegated', label: 'Delegated' }, { value: 'autonomous', label: 'Autonomous' },
];
const DONE_MEANS_OPTIONS = [
  { value: '', label: 'Inherited' }, { value: 'merged', label: 'Merged' }, { value: 'in_production', label: 'In production' },
];
const ALLOW_DIRECT_OPTIONS = [
  { value: '', label: 'Inherited' }, { value: 'true', label: 'Yes' }, { value: 'false', label: 'No' },
];
const labelOf = (options, value) => options.find((o) => o.value === value)?.label ?? value;
const blankToNull = (v) => (v === '' ? null : v);
const dateText = (iso) => (iso ? new Date(iso).toLocaleDateString() : '');

/** What the server holds for a field, shown beside what the person typed after a 409. */
const queueText = (name) => `Queue · ${name || 'a queue'}`;

function theirValue(current, field) {
  const item = current?.item;
  if (!item) return '(unavailable)';
  if (field === 'assignee') return item.queue ? queueText(item.queueName) : item.assigneeName || '(nobody)';
  if (field === 'queue') return item.queue ? queueText(item.queueName) : '(none)';
  if (field === 'parentKey' || field === 'productKey') return current[field] || '(none)';
  const v = item[field];
  return v == null || v === '' ? '(empty)' : String(v);
}

/** What the person typed, in words: a login is shown as the member's display name. */
function mineText(field, value, nameOf, queueNameOf) {
  if (value == null || value === '') return field === 'assignee' || field === 'queue' ? '(nobody)' : '(empty)';
  if (field === 'queue') return queueText(queueNameOf(value));
  return field === 'assignee' ? nameOf(value) : String(value);
}

/** The control-string value the field editors use for a field of `view`; compared with what an editor opened with. */
function valueOf(view, name) {
  const item = view.item;
  switch (name) {
    case 'parentKey': return view.parentKey || '';
    case 'productKey': return view.productKey || '';
    case 'assignee': return targetValue(item);
    case 'priority': return String(item.priority);
    case 'dueOn': return item.dueOn || '';
    case 'kind': return item.kind || 'software';
    case 'allowDirect': return item.allowDirect == null ? '' : String(item.allowDirect);
    case 'autonomy': case 'doneMeans': return item[name] || '';
    case 'body': return item.body ?? '';
    case 'handoff': return JSON.stringify(handoffShape(item.status, item.assignee, item.queue, undefined));
    default: return item[name] ?? '';
  }
}

function ConflictBanner({ banner, nameOf, queueNameOf, onReload, onKeep }) {
  const [review, setReview] = useState(false);
  const [seen, setSeen] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const { changes, current, sameFieldChanged, hasDraft } = banner;
  return (
    <div className="work-conflict error-banner" role="alert">
      <p><strong>This item changed while you were editing.</strong> Nothing you typed has been discarded.</p>
      <div className="work-actions">
        <button type="button" className="btn btn-secondary btn-sm" onClick={() => (hasDraft ? setConfirming(true) : onReload())}>Reload</button>
        <button type="button" className="btn btn-ghost btn-sm" aria-expanded={review} onClick={() => { setReview((r) => !r); setSeen(true); }}>Review their version</button>
        <button
          type="button" className="btn btn-primary btn-sm" disabled={sameFieldChanged && !seen}
          title={sameFieldChanged && !seen ? 'Review their version first: they changed the same field' : undefined}
          onClick={onKeep}
        >Keep editing on top of theirs</button>
      </div>
      {confirming && (
        <div className="work-actions" role="group" aria-label="Confirm reload">
          <span>Discard your unsaved changes and show their version?</span>
          <button type="button" className="btn btn-danger btn-sm" onClick={onReload}>Discard and reload</button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => setConfirming(false)}>Keep editing</button>
        </div>
      )}
      {review && (
        <div className="work-conflict-review" data-testid="conflict-review">
          {Object.keys(changes).filter((f) => !((f === 'assignee' || f === 'queue') && changes[f] === '')).map((field) => (
            <div key={field} className="work-conflict-pair">
              <div>
                <h3 className="work-conflict-title">Their version ({field})</h3>
                <pre className="work-conflict-text">{theirValue(current, field)}</pre>
              </div>
              <div>
                <h3 className="work-conflict-title">Your unsaved text ({field})</h3>
                <pre className="work-conflict-text">{mineText(field, changes[field], nameOf, queueNameOf)}</pre>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function ItemPage({ itemKey }) {
  const toast = useToast();
  const [view, setView] = useState(null);
  const [notFound, setNotFound] = useState(false);
  const [loadError, setLoadError] = useState(null);
  const [members, setMembers] = useState([]);
  const [queues, setQueues] = useState([]);
  const [conflict, setConflict] = useState(null);
  const [products, setProducts] = useState([]);
  const [parents, setParents] = useState([]);
  const [fieldErrors, setFieldErrors] = useState({});
  const [epoch, setEpoch] = useState(0);
  // The one versioned editor that may be open: { name, base, orig, draft }. base is the item version it opened at.
  const [open, setOpen] = useState(null);
  const [activity, setActivity] = useState(0);
  const seq = useRef(0);
  const projectKey = view?.projectKey;

  const clock = useExpiryClock(view?.item.claimedByAgent != null ? view.item.claimExpiresAt : null);

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

  const assigns = (view?.viewerRole === 'member' || view?.viewerRole === 'lead') && !view?.archived;
  useEffect(() => {
    if (!assigns) return undefined;
    let cancelled = false;
    listQueues()
      .then((rows) => { if (!cancelled) setQueues(rows || []); })
      .catch((e) => console.warn('WorkItemView: could not load the queues', e));
    return () => { cancelled = true; };
  }, [assigns]);

  const level = view?.item.level;
  const role0 = view?.viewerRole;
  const editsRelations = (role0 === 'member' || role0 === 'lead') && !view?.archived && level && level !== 'epic';
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

  // An archived project is read-only: a write refused for that reason says so and re-reads the item (which then
  // shows read-only); it is never treated as an edit conflict.
  const fail = useCallback((e, fallback) => {
    if (e?.status === 404) setNotFound(true);
    else if (isArchivedRefusal(e)) { toast.info(`${ARCHIVED_TEXT}; nothing was changed.`); setOpen(null); load(); }
    else toast.error(e?.message || fallback);
  }, [toast, load]);

  /**
   * PATCH; resolves true when accepted, 'restore' when the value was refused and the editor should close,
   * false to keep the caller's draft. The version sent is the one the open editor was opened against (never a
   * refetched one), so the server, not this page, decides whether a concurrent edit is overwritten.
   */
  const submit = useCallback((call, fields, note, source) => {
    const body = { expectedVersion: open?.base ?? view.item.version, ...fields };
    if (note) body.note = note;
    const was = view.item;
    const conflictChanges = source === 'handoff' ? fields : fields.changes;
    setFieldErrors({});
    return call(itemKey, body)
      .then((next) => {
        seq.current += 1; // a load that started before this save must not replace the newer view
        setView(next);
        setConflict(null);
        setActivity((a) => a + 1);
        // A person is never locked out of a claimed item, but changing it ends the claim: say so.
        if (was.claimedByAgent != null && next.item.claimedByAgent == null) {
          toast.info(`${was.claimedByName || 'An agent'}'s claim was released.`);
        }
        return true;
      })
      .catch((e) => {
        if (isArchivedRefusal(e)) fail(e);
        else if (e?.status === 409) setConflict({ changes: conflictChanges, current: e.current || null });
        else if (e?.status === 400 && e.body?.field) {
          setFieldErrors({ [e.body.field]: { message: e.body.message || e.message, source } });
          return e.body.field === 'parentKey' || e.body.field === 'productKey' ? 'restore' : false;
        } else fail(e, 'Could not save the change.');
        return false;
      });
  }, [view, open, itemKey, fail, toast]);

  const save = useCallback((changes, note, source = 'field') => submit(patchItem, { changes }, note, source), [submit]);
  /** A hand-off: `target` is `{ assignee }` or `{ queue }`, never both. */
  const handoff = useCallback((status, target, note) => submit(handoffItem, { status, ...target }, note, 'handoff'), [submit]);

  const openEditor = useCallback((name, orig) => setOpen({
    name, base: view.item.version, orig, draft: name === 'handoff' ? { ...orig, note: '' } : orig,
  }), [view]);
  const closeEditor = useCallback((name) => setOpen((o) => (o && o.name === name ? null : o)), []);
  const setDraft = useCallback((draft) => setOpen((o) => (o ? { ...o, draft } : o)), []);

  /** Runs a discussion or link write, then refreshes the item (and the history). */
  const mutate = useCallback((call, failure) => call()
    .then(() => { setActivity((a) => a + 1); return load().then(() => true); })
    .catch((e) => { fail(e, failure); return false; }), [load, fail]);

  const nameOf = useCallback((login) => members.find((m) => m.principal === login)?.displayName
    || (view?.item.assignee === login ? view.item.assigneeName : null) || 'someone', [members, view]);

  const queueNameOf = useCallback((key) => queues.find((q) => q.key === key)?.name
    || (view?.item.queue === key ? view.item.queueName : null) || null, [queues, view]);

  /** Releases an agent's claim for a person; the item is re-read either way so the panel shows what is true now. */
  const release = useCallback((note) => {
    const holder = view?.item.claimedByName || 'an agent';
    const done = (ok) => {
      if (ok) toast.success(`Released ${holder}'s claim`); else load();
      return ok;
    };
    return releaseItem(itemKey, note)
      .then(() => mutate(() => Promise.resolve(), 'Could not release the claim.'))
      .then(done)
      .catch((e) => {
        if (e?.status === 409 && e.body?.reason === 'not_claimed') {
          // Someone released it, or it expired, while the panel was open: not an error, just refresh.
          toast.info('This claim had already ended.');
          setActivity((a) => a + 1);
          return load().then(() => true);
        }
        return mutate(() => Promise.reject(e), 'Could not release the claim.').then(done);
      });
  }, [view, itemKey, mutate, toast, load]);

  if (notFound) return <EmptyState message="No such item" />;
  if (loadError && !view) return <EmptyState message={loadError} />;
  if (!view) return <Spinner label="Loading…" />;

  const { item } = view;
  const role = view.viewerRole;
  const archived = !!view.archived;
  const canEdit = (role === 'member' || role === 'lead') && !archived;
  const isLead = role === 'lead' && !archived;
  const isEpic = item.level === 'epic';
  const holder = item.claimedByAgent != null ? (item.claimedByName || 'An agent') : null;
  const lapsed = holder != null && isPast(item.claimExpiresAt, clock);
  const claimWarning = (verb) => (lapsed
    ? `${holder}'s claim has lapsed and will be cleared; ${verb} this item clears it now.`
    : `${holder} is working on this; ${verb === 'moving' ? 'moving' : 'reassigning'} it releases its claim.`);
  const statusOptions = (isEpic ? EPIC_STATUSES : STATUSES).map((s) => ({ value: s, label: STATUS_LABELS[s] }));
  const field = (name) => (v) => save({ [name]: v });
  const errEntry = (name) => (fieldErrors[name]?.source === 'handoff' ? undefined : fieldErrors[name]?.message);
  const err = errEntry;
  const handoffError = Object.values(fieldErrors).find((x) => x.source === 'handoff')?.message;
  const editor = (name) => ({
    locked: open != null && open.name !== name,
    onOpen: (orig) => openEditor(name, orig),
    onDraft: setDraft,
    onClose: () => closeEditor(name),
  });
  // Staleness is derived: an editor opened at `base` and an item that has moved on since.
  const stale = open != null && item.version > open.base;
  let banner = null;
  if (conflict || stale) {
    const source = conflict ? conflict.current || view : view;
    const changes = conflict ? conflict.changes
      : open.name === 'handoff' ? open.draft : { [open.name]: open.draft };
    banner = {
      changes,
      current: source,
      hasDraft: !!open && JSON.stringify(open.draft) !== JSON.stringify(open.name === 'handoff' ? { ...open.orig, note: '' } : open.orig),
      sameFieldChanged: !!open && valueOf(source, open.name) !== (typeof open.orig === 'string' ? open.orig : JSON.stringify(open.orig)),
    };
  }
  const withCurrent = (opts, value, label) => (value && !opts.some((o) => o.value === value) ? [...opts, { value, label }] : opts);
  const productOptions = withCurrent([{ value: '', label: 'None' },
    ...products.filter((p) => !p.archived)
      .map((p) => ({ value: p.key, label: p.name ? `${p.key} – ${p.name}` : p.key }))], view.productKey, view.productKey);
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
      {archived && <p className="work-archived-banner" role="status">{ARCHIVED_TEXT}. It is read-only.</p>}
      <WorkClaim item={item} canRelease={canEdit} onRelease={release} />
      {banner && (
        <ConflictBanner
          banner={banner} nameOf={nameOf} queueNameOf={queueNameOf}
          onReload={() => { setConflict(null); setOpen(null); setEpoch((n) => n + 1); load(); }}
          onKeep={() => {
            setConflict(null);
            const rebase = (v) => setOpen((o) => (o ? { ...o, base: v.item.version } : o));
            if (conflict?.current) { setView(conflict.current); rebase(conflict.current); }
            else if (conflict) {
              getItem(itemKey)
                .then((v) => { seq.current += 1; setView(v); rebase(v); })
                .catch((e) => fail(e, 'Could not reload the item.'));
            }
            else rebase(view);
          }}
        />
      )}
      <div className="work-item-layout">
        <div className="work-item-main">
          <WorkBodyEditor
            key={epoch} html={view.bodyHtml} body={item.body} canEdit={canEdit} error={err('body')}
            {...editor('body')}
            onSave={(body) => save({ body })}
          />
          {isEpic && <WorkEpicView view={view} />}
          <WorkLinks
            links={view.links} blocked={view.blocked} canEdit={canEdit}
            onAdd={(kind, target) => mutate(() => addLink(itemKey, { kind, target }), 'Could not add the link.')}
            onRemove={(id) => mutate(() => removeLink(id), 'Could not remove the link.')}
          />
          {!isEpic && <WorkShippedIn itemKey={itemKey} refreshKey={activity} />}
          {!isEpic && (
            <WorkChanges itemKey={itemKey} canEdit={canEdit} refreshKey={activity}
              onChanged={() => setActivity((n) => n + 1)} />
          )}
          <WorkComments
            comments={view.comments} members={members} canComment={canEdit} isLead={isLead}
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
          <WorkFieldRow label="Title" error={err('title')} {...editor('title')} value={item.title} canEdit={canEdit} onSave={field('title')} />
          <WorkFieldRow label="Status" error={err('status')} {...editor('status')} type="select" value={item.status} display={STATUS_LABELS[item.status] || item.status}
            options={statusOptions} canEdit={canEdit} onSave={field('status')}
            helper={holder ? claimWarning('moving') : undefined} />
          <WorkFieldRow label="Assignee" error={err('assignee')} {...editor('assignee')} value={targetValue(item)}
            display={item.queue ? queueText(item.queueName) : item.assigneeName || 'Unassigned'}
            helper={holder ? claimWarning('reassigning') : undefined}
            renderControl={({ value, onChange, disabled }) => (
              <AssigneePicker ariaLabel="Assignee" members={members} queues={queues} value={targetOf(value)} disabled={disabled}
                labels={{ assigneeName: item.assigneeName, queueName: item.queueName }}
                onChange={(t) => onChange(targetValue(t))} />)}
            parse={targetOf} canEdit={canEdit}
            onSave={(t) => save(t.queue ? { queue: t.queue } : t.assignee ? { assignee: t.assignee } : item.queue ? { queue: null } : { assignee: null })} />
          <WorkFieldRow label="Priority" error={err('priority')} {...editor('priority')} type="select" value={String(item.priority)} display={`P${item.priority}`}
            options={PRIORITY_OPTIONS} parse={Number} canEdit={canEdit} onSave={field('priority')} />
          <WorkFieldRow label="Due" error={err('dueOn')} {...editor('dueOn')} type="date" value={item.dueOn || ''} display={item.dueOn || 'No due date'}
            parse={blankToNull} canEdit={canEdit} onSave={field('dueOn')} />
          <WorkFieldRow label="Kind" error={err('kind')} {...editor('kind')} type="select" value={item.kind || 'software'} options={KIND_OPTIONS} canEdit={canEdit} onSave={field('kind')} />
          {!isEpic && (
            <>
              <WorkFieldRow label="Parent" type="select" value={view.parentKey || ''} display={view.parentKey || 'None'}
                options={parentOptions} parse={blankToNull} canEdit={canEdit} error={err('parentKey')} {...editor('parentKey')} onSave={field('parentKey')} />
              <WorkFieldRow label="Product" type="select" value={view.productKey || ''} display={view.productKey || 'None'}
                options={productOptions} parse={blankToNull} canEdit={canEdit} error={err('productKey')} {...editor('productKey')} onSave={field('productKey')} />
            </>
          )}
          {canEdit && (
            <WorkHandoff
              status={item.status} assignee={item.assignee} queue={item.queue} assigneeName={item.assigneeName}
              queueName={item.queueName} statusOptions={statusOptions} members={members} queues={queues} claimHolder={holder} claimLapsed={lapsed}
              error={handoffError} {...editor('handoff')}
              onSend={(status, target, note) => handoff(status, target, note).then((r) => r === true)}
            />
          )}
          <dl className="work-facts">
            <dt>Reporter</dt><dd>{item.reporterName || 'Unknown'}</dd>
            {isEpic && (view.derivedProductKeys || []).length > 0 && (<><dt>Products</dt><dd>{view.derivedProductKeys.join(', ')}</dd></>)}
            <dt>Created</dt><dd>{dateText(item.createdAt)}</dd>
            <dt>Updated</dt><dd>{dateText(item.updatedAt)}</dd>
          </dl>
          <h2 className="work-subheading">Policy</h2>
          <WorkFieldRow label="Autonomy" error={err('autonomy')} {...editor('autonomy')} type="select" value={item.autonomy || ''} display={labelOf(AUTONOMY_OPTIONS, item.autonomy || '')}
            options={AUTONOMY_OPTIONS} parse={blankToNull} canEdit={canEdit} onSave={field('autonomy')} />
          <WorkFieldRow label="Done means" error={err('doneMeans')} {...editor('doneMeans')} type="select" value={item.doneMeans || ''} display={labelOf(DONE_MEANS_OPTIONS, item.doneMeans || '')}
            options={DONE_MEANS_OPTIONS} parse={blankToNull} canEdit={isLead} onSave={field('doneMeans')} />
          <WorkFieldRow label="Allow direct" error={err('allowDirect')} {...editor('allowDirect')} type="select" value={item.allowDirect == null ? '' : String(item.allowDirect)}
            display={labelOf(ALLOW_DIRECT_OPTIONS, item.allowDirect == null ? '' : String(item.allowDirect))}
            options={ALLOW_DIRECT_OPTIONS} parse={(v) => (v === '' ? null : v === 'true')} canEdit={isLead} onSave={field('allowDirect')} />
          <WorkPolicyNote itemKey={itemKey} ownAutonomy={item.autonomy || null} canAcknowledge={canEdit}
            refreshKey={activity} onChanged={() => setActivity((n) => n + 1)} />
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
