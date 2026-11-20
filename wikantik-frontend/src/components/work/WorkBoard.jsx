import { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import Select from '../ui/Select';
import EmptyState from '../ui/EmptyState';
import Spinner from '../ui/Spinner';
import { useToast } from '../../hooks/useToast';
import {
  acknowledgeItem, getBoard, getChangeCounts, getMe, listMembers, listProducts, listQueues, patchItem,
} from '../../api/work';
import { ARCHIVED_TEXT, isArchivedRefusal } from './archived';
import { useWorkProject } from './useWorkProject';
import WaitingLane from './WaitingLane';
import WorkCard from './WorkCard';
import WorkItemForm from './WorkItemForm';
import { STATUS_LABELS, addToColumns, groupByEpic, moveInColumns, orderColumns, rankForInsert, revertCard } from './boardModel';

const CHANGERS = new Set(['member', 'lead']);

function Column({ column, canEdit, grouped, allItems, changeCounts, over, onDragStart, onDragEnd, onMove, onDrop, onOver, onLeave }) {
  const { status, total, items } = column;
  const count = total > items.length ? `${items.length} of ${total}` : String(total);
  const card = (item) => (
    <WorkCard key={item.id} item={item} productKey={item.productKey} changes={changeCounts?.[item.key]} draggable={canEdit}
      onDragStart={onDragStart} onDragEnd={onDragEnd} onMove={canEdit ? onMove : undefined} />
  );
  return (
    <section
      className={`work-column${over ? ' work-column-over' : ''}`}
      data-testid={`column-${status}`}
      aria-label={STATUS_LABELS[status]}
      onDragOver={canEdit ? (e) => { e.preventDefault(); if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'; onOver(status); } : undefined}
      onDragLeave={canEdit ? (e) => { if (!e.currentTarget.contains(e.relatedTarget)) onLeave(status); } : undefined}
      onDrop={canEdit ? (e) => onDrop(status, e) : undefined}
    >
      <h3 className="work-column-title">{STATUS_LABELS[status]} <span className="work-column-count">{count}</span></h3>
      <div className="work-column-cards">
        {grouped
          ? groupByEpic(items, allItems).map((g) => (
            <div key={g.id} className="work-epic-group" data-testid={`epic-group-${g.id}`}>
              <div className="work-epic-title">{g.title}</div>
              {g.items.map(card)}
            </div>))
          : items.map(card)}
      </div>
    </section>
  );
}

export default function WorkBoard() {
  const { project, role, reload } = useWorkProject();
  const toast = useToast();
  const canEdit = CHANGERS.has(role) && !project.archived;
  const [state, setState] = useState({ projectKey: null, columns: null });
  const [notFound, setNotFound] = useState(false);
  const [loadError, setLoadError] = useState(null);
  const [showAll, setShowAll] = useState(false);
  const [grouped, setGrouped] = useState(false);
  const [waiting, setWaiting] = useState([]);
  const [changeCounts, setChangeCounts] = useState({});
  const [products, setProducts] = useState([]);
  const [members, setMembers] = useState([]);
  const [queues, setQueues] = useState([]);
  const [params, setParams] = useSearchParams();
  const productFilter = params.get('product') || '';
  const assigneeFilter = params.get('assignee') || '';
  const queueFilter = params.get('queue') || '';
  const [formOpen, setFormOpen] = useState(false);
  const dragged = useRef(null);
  const loadSeq = useRef(0);
  const [overStatus, setOverStatus] = useState(null);
  const [announcement, setAnnouncement] = useState('');
  // The project on screen right now: an answer for any other project must change nothing.
  const shownProject = useRef(project.key);
  useEffect(() => { shownProject.current = project.key; }, [project.key]);
  const columns = state.projectKey === project.key ? state.columns : null;

  const setColumns = useCallback((next) => {
    if (shownProject.current !== project.key) return;
    setState((s) => ({
    projectKey: project.key,
    columns: typeof next === 'function' ? next(s.projectKey === project.key ? s.columns : null) : next,
    }));
  }, [project.key]);

  const fail = useCallback((e) => {
    if (e?.status === 404) setNotFound(true);
    else setLoadError(e?.message || 'Could not load the board.');
  }, []);

  // Only the latest request may write state: an older response arriving late is dropped.
  const load = useCallback(() => {
    const seq = ++loadSeq.current;
    return getBoard(project.key,
      {
        ...(productFilter ? { product: productFilter } : {}),
        ...(assigneeFilter ? { assignee: assigneeFilter } : {}),
        ...(queueFilter ? { queue: queueFilter } : {}),
      })
      .then((cols) => {
        if (seq !== loadSeq.current) return;
        setNotFound(false); setLoadError(null); setColumns(cols || []);
        // card badges: which items have a live change; the board works without them
        getChangeCounts(project.key)
          .then((counts) => { if (seq === loadSeq.current) setChangeCounts(counts || {}); })
          .catch((e) => console.warn('WorkBoard: failed to load the change counts', e));
      })
      .catch((e) => { if (seq === loadSeq.current) fail(e); });
  }, [project.key, productFilter, assigneeFilter, queueFilter, setColumns, fail]);

  function setFilter(name, value) {
    const next = new URLSearchParams(params);
    if (value) next.set(name, value); else next.delete(name);
    setParams(next, { replace: true });
  }

  // One select filters by a person or a queue; the URL keeps whichever was chosen, never both.
  function setAssigneeFilter(value) {
    const next = new URLSearchParams(params);
    next.delete('assignee');
    next.delete('queue');
    if (value.startsWith('queue:')) next.set('queue', value.slice('queue:'.length));
    else if (value) next.set('assignee', value);
    setParams(next, { replace: true });
  }

  useEffect(() => { load(); }, [load]);

  const loadWaiting = useCallback(() => getMe()
    .then((me) => setWaiting((me?.waitingOn || []).filter((i) => i.projectId === project.id)))
    .catch((e) => console.warn('WorkBoard: failed to load the waiting-on list', e)), [project.id]);

  const acknowledge = useCallback((item) => acknowledgeItem(item.key)
    .then(loadWaiting)
    .catch((e) => toast.error(e?.message || `Could not acknowledge ${item.key}.`)), [loadWaiting, toast]);

  useEffect(() => {
    loadWaiting();
    listProducts()
      .then((list) => setProducts(list || []))
      .catch((e) => console.warn('WorkBoard: failed to load products', e));
    listMembers(project.key)
      .then((list) => setMembers(list || []))
      .catch((e) => console.warn('WorkBoard: failed to load members', e));
    listQueues()
      .then((list) => setQueues(list || []))
      .catch((e) => console.warn('WorkBoard: failed to load queues', e));
  }, [project.id, project.key, loadWaiting]);

  function move(item, status, index) {
    const current = columns.find((c) => c.status === item.status);
    const target = columns.find((c) => c.status === status);
    const others = (target?.items || []).filter((i) => i.key !== item.key);
    const at = index ?? others.length;
    if (status === item.status && current && current.items.findIndex((i) => i.key === item.key) === at) return;
    const rank = rankForInsert(others, at, { append: status === item.status });
    const changes = status === item.status ? { rank } : { status };
    if (status !== item.status && rank !== undefined) changes.rank = rank;
    // Name the card it lands before: equal ranks cannot say which side of a tie a drop meant.
    if (at < others.length) changes.before = others[at].key;
    const movedIn = project.key;
    const stale = () => shownProject.current !== movedIn;
    const original = columns.flatMap((c) => c.items).find((i) => i.key === item.key);
    setColumns(moveInColumns(columns, item.key, status, at, rank));
    patchItem(item.key, { expectedVersion: item.version, changes })
      .then((view) => {
        if (stale()) return;
        if (!view?.item) {
          // Saved, but the answer says nothing about the card: show what the server has.
          load();
          return;
        }
        setColumns((cols) => (cols || []).map((c) => ({
          ...c, items: c.items.map((i) => (i.key === item.key ? { ...i, ...view.item } : i)),
        })));
        // A different rank back means the server renumbered the column to keep the drop's place: show its order.
        if (rank !== undefined && view.item.rank !== rank) load();
        setAnnouncement(`Moved ${item.key} to ${STATUS_LABELS[status] || status}.`);
        // A person is never locked out of a claimed item, but the move ends the claim: say so once it has happened.
        if (original?.claimedByAgent != null && view.item.claimedByAgent == null) {
          toast.info(`${original.claimedByName || 'An agent'}'s claim on ${item.key} was released by the move.`);
        }
      })
      .catch((err) => {
        if (stale()) return;
        if (isArchivedRefusal(err)) {
          toast.info(`${ARCHIVED_TEXT}; nothing was moved.`);
          setColumns((cols) => revertCard(cols || [], original));
          reload();
        } else if (err?.status === 409) {
          toast.info(`${item.key} changed while you were moving it; the board was reloaded.`);
          load();
        } else if (err?.status === 404) {
          setNotFound(true);
        } else {
          toast.error(err?.message || `Could not move ${item.key}.`);
          setColumns((cols) => revertCard(cols || [], original));
        }
      });
  }

  function onDrop(status, e) {
    e.preventDefault();
    const key = dragged.current;
    dragged.current = null;
    setOverStatus(null);
    const item = key && columns.flatMap((c) => c.items).find((i) => i.key === key);
    if (!item || !canEdit) return;
    const others = (columns.find((c) => c.status === status)?.items || []).filter((i) => i.key !== key);
    let index = others.length;
    if (!grouped) {
      const cards = [...e.currentTarget.querySelectorAll('.work-card')].filter((el) => el.dataset.itemKey !== key);
      const hit = cards.findIndex((el) => {
        const r = el.getBoundingClientRect();
        return r.top + r.height / 2 > (e.clientY ?? 0);
      });
      if (hit >= 0) index = hit;
    }
    move(item, status, index);
  }

  if (notFound) return <EmptyState message="Not found" />;
  if (loadError) return <EmptyState message={loadError} />;
  if (!columns) return <Spinner label="Loading board…" />;

  const ordered = orderColumns(columns, showAll);
  const allItems = columns.flatMap((c) => c.items);
  // A new epic (and an agent's item) starts as a draft: say so when drafts are hidden, or it looks as if it was never
  // created. A person's new task or story starts ready.
  const hidden = showAll ? 0 : columns.filter((c) => c.status === 'draft' || c.status === 'cancelled')
    .reduce((n, c) => n + (c.total ?? c.items.length), 0);

  const filtered = !!(productFilter || assigneeFilter || queueFilter);
  const noCards = ordered.every((c) => c.items.length === 0);
  let emptyText = null;
  if (noCards && filtered) emptyText = 'No items match these filters.';
  else if (noCards && hidden === 0) {
    if (!canEdit) emptyText = 'No items yet.';
    else emptyText = showAll ? 'No items yet. Choose New item to add one.'
      : 'No items yet. Choose New item to add one. A new epic starts as a draft, shown with Show draft and cancelled.';
  }

  return (
    <div className="work-board">
      <div className="work-board-toolbar">
        <label className="work-toggle">
          <input type="checkbox" checked={grouped} onChange={(e) => setGrouped(e.target.checked)} /> Group by epic
        </label>
        <label className="work-toggle">
          <input type="checkbox" checked={showAll} onChange={(e) => setShowAll(e.target.checked)} /> Show draft and cancelled
        </label>
        {hidden > 0 && (
          <button type="button" className="btn btn-ghost btn-sm work-hidden-hint" onClick={() => setShowAll(true)}>
            {hidden} draft or cancelled {hidden === 1 ? 'item is' : 'items are'} hidden. Show
          </button>
        )}
        <Select ariaLabel="Filter by product" placeholder="All products" value={productFilter}
          options={products.map((p) => ({ value: p.key, label: p.name || p.key }))}
          onChange={(v) => setFilter('product', v)} />
        <Select ariaLabel="Filter by assignee" placeholder="All assignees"
          value={queueFilter ? `queue:${queueFilter}` : assigneeFilter}
          options={[
            ...members.filter((m) => m.principalKind === 'user' && m.displayName)
              .map((m) => ({ value: m.displayName, label: m.displayName })),
            ...queues.filter((q) => !q.retiredAt || q.key === queueFilter)
              .map((q) => ({ value: `queue:${q.key}`, label: `Queue · ${q.name || q.key}` })),
          ]}
          onChange={setAssigneeFilter} />
        {canEdit && (
          <button type="button" className="btn btn-primary work-new-item" onClick={() => setFormOpen(true)}>New item</button>
        )}
      </div>
      {waiting.length > 0 && <WaitingLane items={waiting} onAcknowledge={canEdit ? acknowledge : undefined} />}
      {emptyText && <p className="work-muted work-board-empty">{emptyText}</p>}
      <div className="work-columns">
        {ordered.map((c) => (
          <Column key={c.status} column={c} canEdit={canEdit} grouped={grouped}
            allItems={allItems} changeCounts={changeCounts}
            onDragStart={(item) => { dragged.current = item.key; }}
            over={overStatus === c.status}
            onOver={setOverStatus} onLeave={(st) => setOverStatus((o) => (o === st ? null : o))}
            onDragEnd={() => { dragged.current = null; setOverStatus(null); }}
            onMove={(item, status) => move(item, status)} onDrop={onDrop} />
        ))}
      </div>
      <div className="work-visually-hidden" role="status" aria-live="polite">{announcement}</div>
      <WorkItemForm isOpen={formOpen} project={project} products={products} members={members} queues={queues} onClose={() => setFormOpen(false)}
        onCreated={(created, view) => {
          const item = {
            ...created, productKey: view?.productKey ?? null, parentKey: view?.parentKey ?? null,
            blocked: !!view?.blocked, commentCount: view?.comments?.length ?? 0,
          };
          const matches = (!productFilter || item.productKey === productFilter)
            && (!assigneeFilter || item.assigneeName === assigneeFilter)
            && (!queueFilter || item.queue === queueFilter);
          if (matches) setColumns((cols) => addToColumns(cols || [], item));
          toast.success(item.status === 'draft' && !showAll
            ? `Created ${item.key} as a draft. Drafts are hidden: tick Show draft and cancelled to see it.`
            : `Created ${item.key}`);
        }} />
    </div>
  );
}
