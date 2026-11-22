import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import Modal from '../ui/Modal';
import Select from '../ui/Select';
import Spinner from '../ui/Spinner';
import { useToast } from '../../hooks/useToast';
import { closeSprint, listItems, patchItem, startSprint } from '../../api/work';
import { ARCHIVED_TEXT, isArchivedRefusal } from './archived';
import { STATUS_LABELS } from './boardModel';
import { activeSprint, formatPoints, openSprints } from './sprintModel';
import { useWorkProject } from './useWorkProject';
import WorkSprintForm from './WorkSprintForm';

const OPEN_STATUSES = 'draft,refining,ready,in_progress,review';
const LIMIT = 200;
const BACKLOG = 'none';

const dayText = (iso) => {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
};
const plural = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`;

function summaryText(s) {
  const c = s.current || {};
  if (s.state === 'active') return `${c.doneItems ?? 0} of ${plural(c.items ?? 0, 'item')} · ${formatPoints(c.donePoints ?? 0)} of ${formatPoints(c.points ?? 0)} pts`;
  if (s.state === 'closed' && s.committed) {
    return `${s.completed?.items ?? 0} of ${plural(s.committed.items, 'item')} · ${formatPoints(s.completed?.points ?? 0)} of ${formatPoints(s.committed.points)} pts`;
  }
  return `${plural(c.items ?? 0, 'item')} · ${formatPoints(c.points ?? 0)} pts`;
}

function Row({ item, targets, canPlan, onPlan, onDragStart }) {
  return (
    <li className="work-sprint-row" data-item-key={item.key} draggable={canPlan}
      onDragStart={canPlan ? (e) => {
        if (e.dataTransfer) { e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', item.key); }
        onDragStart(item);
      } : undefined}>
      <Link to={`/work/item/${item.key}`} draggable={false}>{item.key}</Link>
      <span className="work-story-title">{item.title}</span>
      <span className="work-story-status">{STATUS_LABELS[item.status] || item.status}</span>
      <span className="work-sprint-points">{item.estimate == null ? '—' : `${formatPoints(item.estimate)} pts`}</span>
      {canPlan && (
        <Select ariaLabel={`Plan ${item.key} into`} placeholder="Plan into…" value="" options={targets}
          onChange={(v) => v && onPlan(item, v === BACKLOG ? null : Number(v))} />
      )}
    </li>
  );
}

function CloseDialog({ sprint, planned, onCancel, onConfirm, busy }) {
  const [target, setTarget] = useState(BACKLOG);
  return (
    <Modal isOpen onClose={onCancel} labelledBy="work-sprint-close-title" className="work-form-modal">
      <div className="work-form">
        <h2 id="work-sprint-close-title" className="work-form-title">Close {sprint.name}</h2>
        <label className="work-form-field" htmlFor="work-sprint-move-to">Move unfinished work to</label>
        <Select id="work-sprint-move-to" ariaLabel="Move unfinished work to" value={target} onChange={setTarget}
          options={[{ value: BACKLOG, label: 'Backlog' }, ...planned.map((p) => ({ value: String(p.id), label: p.name }))]} />
        <div className="modal-actions">
          <button type="button" className="btn btn-ghost" onClick={onCancel}>Cancel</button>
          <button type="button" className="btn btn-primary" disabled={busy}
            onClick={() => onConfirm(target === BACKLOG ? null : Number(target))}>Close sprint</button>
        </div>
      </div>
    </Modal>
  );
}

/** The backlog and each open sprint's items for a project; `loadItems` reloads them, and only the latest answer is kept. */
function useSprintItems(projectKey, openIds, toast) {
  const [data, setData] = useState({ projectKey: null, bySprint: {}, backlog: null });
  const seq = useRef(0);
  const loadItems = useCallback(() => {
    const mine = ++seq.current;
    const ids = openIds ? openIds.split(',') : [];
    return Promise.all([
      listItems({ project: projectKey, sprint: BACKLOG, status: OPEN_STATUSES, limit: LIMIT }),
      ...ids.map((id) => listItems({ project: projectKey, sprint: id, limit: LIMIT })),
    ]).then(([backlog, ...lists]) => {
      if (mine !== seq.current) return;
      const bySprint = {};
      ids.forEach((id, i) => { bySprint[id] = (lists[i] || []).filter((x) => x.level !== 'epic'); });
      setData({ projectKey, bySprint, backlog: (backlog || []).filter((x) => x.level !== 'epic') });
    }).catch((e) => {
      console.warn('WorkSprints: could not load the sprint items', e);
      if (mine === seq.current) toast.error(e?.message || 'Could not load the sprint items.');
    });
  }, [projectKey, openIds, toast]);
  useEffect(() => { loadItems(); }, [loadItems]);
  return { data, loadItems };
}

/** Sprint planning for a project: the active sprint, the planned ones, the backlog, and the closed sprints' reports. */
export default function WorkSprints() {
  const { project, role, sprints = [], reloadSprints = () => {} } = useWorkProject();
  const toast = useToast();
  const canPlan = (role === 'member' || role === 'lead') && !project.archived;
  const isLead = role === 'lead' && !project.archived;
  const [formOpen, setFormOpen] = useState(false);
  const [closing, setClosing] = useState(null);
  const [busy, setBusy] = useState(false);
  const [over, setOver] = useState(null);
  const [dragKey, setDragKey] = useState(null);
  const open = openSprints(sprints);
  const openIds = openSprints(sprints).map((s) => s.id).join(',');
  const { data, loadItems } = useSprintItems(project.key, openIds, toast);

  const refresh = useCallback(() => { reloadSprints(); return loadItems(); }, [reloadSprints, loadItems]);

  function refused(e, fallback) {
    if (isArchivedRefusal(e)) toast.info(`${ARCHIVED_TEXT}; nothing was changed.`);
    else if (e?.status === 409) toast.info(e.reason === 'stale' || e.body?.reason === 'stale' || !e.body?.reason
      ? 'That changed while you were working; the page was reloaded.' : (e.message || fallback));
    else toast.error(e?.message || fallback);
    return refresh();
  }

  function plan(item, sprintId) {
    if ((item.sprintId ?? null) === sprintId) return Promise.resolve();
    return patchItem(item.key, { expectedVersion: item.version, changes: { sprint: sprintId } })
      .then(() => refresh())
      .catch((e) => refused(e, `Could not plan ${item.key}.`));
  }

  function onDrop(sprintId, e) {
    e.preventDefault();
    setOver(null);
    const key = dragKey;
    setDragKey(null);
    const all = [...(data.backlog || []), ...Object.values(data.bySprint).flat()];
    const item = key && all.find((i) => i.key === key);
    if (item && canPlan) plan(item, sprintId);
  }

  // A drop zone: a sprint (its id) or the backlog (null); `over` holds the zone's test id while a card is above it.
  const zone = (id) => {
    const name = id === null ? 'backlog' : `sprint-${id}`;
    return {
      'data-testid': name,
      onDragOver: canPlan ? (e) => { e.preventDefault(); if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'; setOver(name); } : undefined,
      onDragLeave: canPlan ? (e) => { if (!e.currentTarget.contains(e.relatedTarget)) setOver((o) => (o === name ? null : o)); } : undefined,
      onDrop: canPlan ? (e) => onDrop(id, e) : undefined,
    };
  };

  function runLead(call, fallback) {
    setBusy(true);
    return call()
      .then(() => { setClosing(null); return refresh(); })
      .catch((e) => refused(e, fallback))
      .finally(() => setBusy(false));
  }

  function requestClose(sprint) {
    // From the sprint's own counts: the loaded lists are capped.
    const c = sprint.current || {};
    if ((c.items ?? 0) > (c.doneItems ?? 0)) setClosing(sprint);
    else runLead(() => closeSprint(sprint.id, { version: sprint.version, moveOpenTo: null }), `Could not close ${sprint.name}.`);
  }

  if (data.projectKey !== project.key || data.backlog === null) return <Spinner label="Loading sprints…" />;

  const targetsFor = (item) => [
    ...open.filter((s) => s.id !== item.sprintId).map((s) => ({ value: String(s.id), label: s.name })),
    ...(item.sprintId == null ? [] : [{ value: BACKLOG, label: 'Backlog' }]),
  ];
  const active = activeSprint(sprints);
  const planned = sprints.filter((s) => s.state === 'planned');
  const closed = sprints.filter((s) => s.state === 'closed');
  const card = (s) => {
    const items = data.bySprint[String(s.id)] || [];
    return (
      <section key={s.id} className={`work-sprint${over === `sprint-${s.id}` ? ' work-column-over' : ''}`} aria-label={s.name} {...zone(s.id)}>
        <header className="work-sprint-head">
          <h3 className="work-subheading">{s.name} <span className="work-muted">· {s.state} · {dayText(s.startsOn)} – {dayText(s.endsOn)}</span></h3>
          <span className="work-sprint-summary">{summaryText(s)}</span>
        </header>
        {s.goal && <p className="work-sprint-goal">{s.goal}</p>}
        {items.length === 0 ? <p className="work-muted">Nothing planned yet.</p> : (
          <ul className="work-sprint-list">
            {items.map((i) => <Row key={i.id} item={i} targets={targetsFor(i)} canPlan={canPlan} onPlan={plan} onDragStart={(it) => setDragKey(it.key)} />)}
          </ul>
        )}
        <div className="work-actions">
          {isLead && s.state === 'planned' && (
            <button type="button" className="btn btn-secondary btn-sm" disabled={busy}
              onClick={() => runLead(() => startSprint(s.id, s.version), `Could not start ${s.name}.`)}>Start</button>
          )}
          {s.state === 'active' && <Link className="btn btn-ghost btn-sm" to={`/work/${project.key}/sprints/${s.id}`}>Report</Link>}
          {isLead && s.state === 'active' && (
            <button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => requestClose(s)}>Close sprint</button>
          )}
        </div>
      </section>
    );
  };

  return (
    <div className="work-sprints">
      <div className="work-board-toolbar">
        {isLead && <button type="button" className="btn btn-primary" onClick={() => setFormOpen(true)}>New sprint</button>}
      </div>
      {!active && planned.length === 0 && <p className="work-muted">No sprint is planned or running.</p>}
      {active && card(active)}
      {planned.map(card)}
      <section className={`work-sprint work-backlog${over === 'backlog' ? ' work-column-over' : ''}`} aria-label="Backlog" {...zone(null)}>
        <h3 className="work-subheading">Backlog <span className="work-muted">(open stories and tasks with no sprint)</span></h3>
        {data.backlog.length === 0 ? <p className="work-muted">The backlog is empty.</p> : (
          <ul className="work-sprint-list">
            {data.backlog.map((i) => <Row key={i.id} item={i} targets={targetsFor(i)} canPlan={canPlan} onPlan={plan} onDragStart={(it) => setDragKey(it.key)} />)}
          </ul>
        )}
      </section>
      {closed.length > 0 && (
        <section className="work-sprint-closed" aria-label="Closed sprints">
          <h3 className="work-subheading">Closed sprints</h3>
          <ul className="work-sprint-list">
            {closed.map((s) => (
              <li key={s.id} className="work-sprint-row">
                <Link to={`/work/${project.key}/sprints/${s.id}`}>{s.name}</Link>
                <span className="work-muted">{dayText(s.startsOn)} – {dayText(s.endsOn)}</span>
                <span className="work-sprint-summary">{summaryText(s)}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
      <WorkSprintForm isOpen={formOpen} projectKey={project.key} onClose={() => setFormOpen(false)}
        onCreated={() => { reloadSprints(); }} />
      {closing && (
        <CloseDialog sprint={closing} planned={planned} busy={busy} onCancel={() => setClosing(null)}
          onConfirm={(moveOpenTo) => runLead(() => closeSprint(closing.id, { version: closing.version, moveOpenTo }), `Could not close ${closing.name}.`)} />
      )}
    </div>
  );
}
