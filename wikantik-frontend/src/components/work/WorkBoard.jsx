import { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import Select from '../ui/Select';
import EmptyState from '../ui/EmptyState';
import Spinner from '../ui/Spinner';
import { useToast } from '../../hooks/useToast';
import { getBoard, getMe, listMembers, listProducts, listQueues, patchItem } from '../../api/work';
import { ARCHIVED_TEXT, isArchivedRefusal } from './archived';
import { useWorkProject } from './useWorkProject';
import WaitingLane from './WaitingLane';
import WorkCard from './WorkCard';
import WorkItemForm from './WorkItemForm';
import { STATUS_LABELS, addToColumns, groupByEpic, moveInColumns, orderColumns, rankForInsert, revertCard } from './boardModel';

const CHANGERS = new Set(['member', 'lead']);

function Column({ column, canEdit, grouped, allItems, onDragStart, onDragEnd, onMove, onDrop }) {
  const { status, total, items } = column;
  const count = total > items.length ? `${items.length} of ${total}` : String(total);
  const card = (item) => (
    <WorkCard key={item.id} item={item} productKey={item.productKey} draggable={canEdit}
      onDragStart={onDragStart} onDragEnd={onDragEnd} onMove={canEdit ? onMove : undefined} />
  );
  return (
    <section
      className="work-column"
      data-testid={`column-${status}`}
      aria-label={STATUS_LABELS[status]}
      onDragOver={canEdit ? (e) => { e.preventDefault(); if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'; } : undefined}
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
  const columns = state.projectKey === project.key ? state.columns : null;

  const setColumns = useCallback((next) => setState((s) => ({
    projectKey: project.key,
    columns: typeof next === 'function' ? next(s.projectKey === project.key ? s.columns : null) : next,
  })), [project.key]);

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

  useEffect(() => {
    getMe()
      .then((me) => setWaiting((me?.waitingOn || []).filter((i) => i.projectId === project.id)))
      .catch((e) => console.warn('WorkBoard: failed to load the waiting-on list', e));
    listProducts()
      .then((list) => setProducts(list || []))
      .catch((e) => console.warn('WorkBoard: failed to load products', e));
    listMembers(project.key)
      .then((list) => setMembers(list || []))
      .catch((e) => console.warn('WorkBoard: failed to load members', e));
    listQueues()
      .then((list) => setQueues(list || []))
      .catch((e) => console.warn('WorkBoard: failed to load queues', e));
  }, [project.id, project.key]);

  function move(item, status, index) {
    const current = columns.find((c) => c.status === item.status);
    const target = columns.find((c) => c.status === status);
    const others = (target?.items || []).filter((i) => i.key !== item.key);
    const at = index ?? others.length;
    if (status === item.status && current && current.items.findIndex((i) => i.key === item.key) === at) return;
    const rank = rankForInsert(others, at, { append: status === item.status });
    const changes = status === item.status ? { rank } : { status };
    if (status !== item.status && rank !== undefined) changes.rank = rank;
    const original = columns.flatMap((c) => c.items).find((i) => i.key === item.key);
    setColumns(moveInColumns(columns, item.key, status, at, rank));
    patchItem(item.key, { expectedVersion: item.version, changes })
      .then((view) => {
        setColumns((cols) => (cols || []).map((c) => ({
          ...c, items: c.items.map((i) => (i.key === item.key ? { ...i, ...view.item } : i)),
        })));
        // A person is never locked out of a claimed item, but the move ends the claim: say so once it has happened.
        if (original?.claimedByAgent != null && view?.item && view.item.claimedByAgent == null) {
          toast.info(`${original.claimedByName || 'An agent'}'s claim on ${item.key} was released by the move.`);
        }
      })
      .catch((err) => {
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

  return (
    <div className="work-board">
      <div className="work-board-toolbar">
        <label className="work-toggle">
          <input type="checkbox" checked={grouped} onChange={(e) => setGrouped(e.target.checked)} /> Group by epic
        </label>
        <label className="work-toggle">
          <input type="checkbox" checked={showAll} onChange={(e) => setShowAll(e.target.checked)} /> Show draft and cancelled
        </label>
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
      {waiting.length > 0 && <WaitingLane items={waiting} />}
      <div className="work-columns">
        {ordered.map((c) => (
          <Column key={c.status} column={c} canEdit={canEdit} grouped={grouped}
            allItems={allItems}
            onDragStart={(item) => { dragged.current = item.key; }}
            onDragEnd={() => { dragged.current = null; }}
            onMove={(item, status) => move(item, status)} onDrop={onDrop} />
        ))}
      </div>
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
          toast.success(`Created ${item.key}`);
        }} />
    </div>
  );
}
