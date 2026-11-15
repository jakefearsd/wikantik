import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import EmptyState from '../ui/EmptyState';
import Spinner from '../ui/Spinner';
import { useToast } from '../../hooks/useToast';
import { getBoard, getMe, listProducts, patchItem } from '../../api/work';
import { useWorkProject } from './useWorkProject';
import WorkCard from './WorkCard';
import WorkItemForm from './WorkItemForm';
import { STATUS_LABELS, addToColumns, groupByEpic, moveInColumns, orderColumns, rankForInsert } from './boardModel';

const CHANGERS = new Set(['member', 'lead']);

function Column({ column, productKeys, canEdit, grouped, allItems, onDragStart, onMove, onDrop }) {
  const { status, total, items } = column;
  const count = total > items.length ? `${items.length} of ${total}` : String(total);
  const card = (item) => (
    <WorkCard key={item.id} item={item} productKey={productKeys.get(item.productId)} draggable={canEdit}
      onDragStart={onDragStart} onMove={canEdit ? onMove : undefined} />
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
  const { project, role } = useWorkProject();
  const toast = useToast();
  const canEdit = CHANGERS.has(role);
  const [state, setState] = useState({ projectKey: null, columns: null });
  const [notFound, setNotFound] = useState(false);
  const [loadError, setLoadError] = useState(null);
  const [showAll, setShowAll] = useState(false);
  const [grouped, setGrouped] = useState(false);
  const [waiting, setWaiting] = useState([]);
  const [products, setProducts] = useState([]);
  const [formOpen, setFormOpen] = useState(false);
  const dragged = useRef(null);
  const columns = state.projectKey === project.key ? state.columns : null;

  const setColumns = useCallback((next) => setState((s) => ({
    projectKey: project.key,
    columns: typeof next === 'function' ? next(s.projectKey === project.key ? s.columns : null) : next,
  })), [project.key]);

  const fail = useCallback((e) => {
    if (e?.status === 404) setNotFound(true);
    else setLoadError(e?.message || 'Could not load the board.');
  }, []);

  const load = useCallback(() => getBoard(project.key)
    .then((cols) => { setNotFound(false); setLoadError(null); setColumns(cols || []); })
    .catch(fail), [project.key, setColumns, fail]);

  useEffect(() => {
    load();
    getMe()
      .then((me) => setWaiting((me?.waitingOn || []).filter((i) => i.projectId === project.id)))
      .catch((e) => console.warn('WorkBoard: failed to load the waiting-on list', e));
    listProducts()
      .then((list) => setProducts(list || []))
      .catch((e) => console.warn('WorkBoard: failed to load products', e));
  }, [load, project.id]);

  const productKeys = new Map(products.map((p) => [p.id, p.key]));

  function move(item, status, index) {
    const current = columns.find((c) => c.status === item.status);
    const target = columns.find((c) => c.status === status);
    const others = (target?.items || []).filter((i) => i.key !== item.key);
    const at = index ?? others.length;
    if (status === item.status && current && current.items.findIndex((i) => i.key === item.key) === at) return;
    const rank = rankForInsert(others, at, { append: status === item.status });
    const changes = status === item.status ? { rank } : { status };
    if (status !== item.status && rank !== undefined) changes.rank = rank;
    const previous = columns;
    setColumns(moveInColumns(columns, item.key, status, at, rank));
    patchItem(item.key, { expectedVersion: item.version, changes })
      .then((view) => setColumns((cols) => (cols || []).map((c) => ({
        ...c, items: c.items.map((i) => (i.key === item.key ? { ...i, ...view.item } : i)),
      }))))
      .catch((err) => {
        if (err?.status === 409) {
          toast.info(`${item.key} changed while you were moving it; the board was reloaded.`);
          load();
        } else if (err?.status === 404) {
          setNotFound(true);
        } else {
          toast.error(err?.message || `Could not move ${item.key}.`);
          setColumns(previous);
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
        {canEdit && (
          <button type="button" className="btn btn-primary work-new-item" onClick={() => setFormOpen(true)}>New item</button>
        )}
      </div>
      {waiting.length > 0 && (
        <div className="work-waiting" data-testid="waiting-lane">
          <strong>Waiting on you ({waiting.length})</strong>
          <ul className="work-waiting-list">
            {waiting.map((i) => (
              <li key={i.id}><Link to={`/work/item/${i.key}`}>{i.key}</Link> {i.title}</li>
            ))}
          </ul>
        </div>
      )}
      <div className="work-columns">
        {ordered.map((c) => (
          <Column key={c.status} column={c} productKeys={productKeys} canEdit={canEdit} grouped={grouped}
            allItems={allItems}
            onDragStart={(item) => { dragged.current = item.key; }}
            onMove={(item, status) => move(item, status)} onDrop={onDrop} />
        ))}
      </div>
      <WorkItemForm isOpen={formOpen} project={project} products={products} onClose={() => setFormOpen(false)}
        onCreated={(item) => {
          setColumns((cols) => addToColumns(cols || [], item));
          toast.success(`Created ${item.key}`);
        }} />
    </div>
  );
}
