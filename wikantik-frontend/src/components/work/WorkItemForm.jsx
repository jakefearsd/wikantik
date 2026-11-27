import { useEffect, useId, useRef, useState } from 'react';
import Modal from '../ui/Modal';
import Select from '../ui/Select';
import { listItems, createItem } from '../../api/work';
import useAsyncAction from '../../hooks/useAsyncAction';
import AssigneePicker from './AssigneePicker';
import { PRIORITY_OPTIONS, TYPE_OPTIONS } from './boardModel';
import WorkLabels, { withPending } from './WorkLabels';

const LEVELS = [
  { value: 'task', label: 'Task', parentLevel: 'story' },
  { value: 'story', label: 'Story', parentLevel: 'epic' },
  { value: 'epic', label: 'Epic', parentLevel: null },
];
const KINDS = ['software', 'docs', 'admin'];
const OPEN_STATUSES = ['draft', 'refining', 'ready', 'in_progress', 'review'];

/** The project default if that product is active, else the only active product, else none. */
function initialProduct(project, products) {
  const active = (products || []).filter((p) => !p.archived);
  if (project.defaultProductKey && active.some((p) => p.key === project.defaultProductKey)) return project.defaultProductKey;
  return active.length === 1 ? active[0].key : '';
}

function FormBody({ project, products, members, queues, labelSuggestions, onClose, onCreated }) {
  const uid = useId();
  const [level, setLevel] = useState('task');
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [parentKey, setParentKey] = useState('');
  const [initialKey] = useState(() => initialProduct(project, products));
  const [productKey, setProductKey] = useState(initialKey);
  const [showMore, setShowMore] = useState(false);
  const [kind, setKind] = useState('software');
  const [type, setType] = useState('feature');
  const [labels, setLabels] = useState([]);
  const typedLabel = useRef(''); // text in the label box not yet turned into a chip; submit includes it
  const [priority, setPriority] = useState('3');
  const [dueOn, setDueOn] = useState('');
  const [target, setTarget] = useState({});
  const [candidates, setCandidates] = useState([]);
  const { run: create, busy: saving, error } = useAsyncAction(createItem, {
    fallbackMessage: 'Could not create the item.',
    onSuccess: (view) => {
      onCreated?.(view.item, view);
      onClose();
    },
  });

  useEffect(() => {
    let cancelled = false;
    listItems({ project: project.key, status: OPEN_STATUSES, limit: 200 })
      .then((rows) => { if (!cancelled) setCandidates(rows || []); })
      .catch((e) => {
        console.warn('WorkItemForm: failed to load parent candidates', e);
        if (!cancelled) setCandidates([]);
      });
    return () => { cancelled = true; };
  }, [project.key]);

  const current = LEVELS.find((l) => l.value === level);
  const parents = current.parentLevel ? candidates.filter((i) => i.level === current.parentLevel) : [];

  function chooseLevel(next) {
    setLevel(next);
    setParentKey('');
    if (next === 'epic') setProductKey(initialKey);
  }

  function submit(e) {
    e.preventDefault();
    if (!title.trim() || saving) return;
    const draft = { project: project.key, level, kind, title: title.trim(), body, priority: Number(priority) };
    if (parentKey && current.parentLevel) draft.parentKey = parentKey;
    if (level !== 'epic') draft.productKey = productKey;
    if (dueOn) draft.dueOn = dueOn;
    if (level !== 'epic') draft.type = type;
    const allLabels = withPending(labels, typedLabel.current);
    if (allLabels.length > 0) draft.labels = allLabels;
    if (target.queue) draft.queue = target.queue;
    else if (target.assignee) draft.assignee = target.assignee;
    create(draft);
  }

  const productField = (
    <div>
      <label className="work-form-field" htmlFor={`${uid}-product`}>Product</label>
      <Select id={`${uid}-product`} ariaLabel="Product" placeholder="No product" value={productKey}
        options={(products || []).filter((p) => !p.archived).map((p) => ({ value: p.key, label: p.name || p.key }))}
        onChange={setProductKey} />
    </div>
  );
  const productName = (products || []).find((p) => p.key === productKey);
  const summary = [
    level !== 'epic' && `Product: ${productKey ? (productName?.name || productKey) : 'none'}`,
    level !== 'epic' && (TYPE_OPTIONS.find((o) => o.value === type)?.label || type),
    kind.charAt(0).toUpperCase() + kind.slice(1),
    `P${priority}`,
  ].filter(Boolean).join(' · ');

  return (
    <form className="work-form" onSubmit={submit}>
      <h2 id="work-item-form-title" className="work-form-title">New item</h2>
      <fieldset className="work-form-levels">
        <legend>Level</legend>
        {LEVELS.map((l) => (
          <label key={l.value} className="work-form-radio">
            <input type="radio" name={`${uid}-level`} value={l.value} checked={level === l.value}
              onChange={() => chooseLevel(l.value)} /> {l.label}
          </label>
        ))}
      </fieldset>
      <label className="work-form-field" htmlFor={`${uid}-title`}>Title</label>
      <input id={`${uid}-title`} className="form-input" value={title} maxLength={300}
        onChange={(e) => setTitle(e.target.value)} />
      <label className="work-form-field" htmlFor={`${uid}-body`}>Description</label>
      <textarea id={`${uid}-body`} className="form-input work-form-body" rows={4} value={body}
        onChange={(e) => setBody(e.target.value)} />
      <div className="work-form-row">
        {current.parentLevel && (
          <div>
            <label className="work-form-field" htmlFor={`${uid}-parent`}>Parent</label>
            <Select id={`${uid}-parent`} ariaLabel="Parent" placeholder="No parent" value={parentKey}
              options={parents.map((p) => ({ value: p.key, label: `${p.key} ${p.title}` }))}
              onChange={setParentKey} />
          </div>
        )}
        {level !== 'epic' && !initialKey && productField}
        <div>
          <label className="work-form-field" htmlFor={`${uid}-assignee`}>Assignee</label>
          <AssigneePicker id={`${uid}-assignee`} members={members} queues={queues} value={target}
            onChange={(t) => setTarget(t.queue ? { queue: t.queue } : t.assignee ? { assignee: t.assignee } : {})} />
        </div>
      </div>
      <button type="button" className="btn btn-ghost btn-sm work-form-more" aria-expanded={showMore}
        onClick={() => setShowMore((v) => !v)}>
        More options ({summary})
      </button>
      {showMore && (
        <div className="work-form-more-body">
          <div className="work-form-row">
            {level !== 'epic' && initialKey && productField}
            {level !== 'epic' && (
              <div>
                <label className="work-form-field" htmlFor={`${uid}-type`}>Type</label>
                <Select id={`${uid}-type`} ariaLabel="Type" value={type} options={TYPE_OPTIONS} onChange={setType} />
                <p className="work-muted">What kind of work: feature, bug or chore</p>
              </div>
            )}
            <div>
              <label className="work-form-field" htmlFor={`${uid}-kind`}>Kind</label>
              <Select id={`${uid}-kind`} ariaLabel="Kind" value={kind} options={KINDS} onChange={setKind} />
              <p className="work-muted">Which area: software, docs or admin</p>
            </div>
            <div>
              <label className="work-form-field" htmlFor={`${uid}-priority`}>Priority</label>
              <Select id={`${uid}-priority`} ariaLabel="Priority" value={priority}
                options={PRIORITY_OPTIONS} onChange={setPriority} />
            </div>
            <div>
              <label className="work-form-field" htmlFor={`${uid}-due`}>Due date</label>
              <input id={`${uid}-due`} type="date" className="form-input" value={dueOn}
                onChange={(e) => setDueOn(e.target.value)} />
            </div>
          </div>
          <span className="work-form-field">Labels</span>
          <WorkLabels labels={labels} suggestions={labelSuggestions} editable onChange={setLabels}
            onPending={(t) => { typedLabel.current = t; }} />
        </div>
      )}
      {error && <div role="alert" className="work-form-error">{error}</div>}
      <div className="modal-actions">
        <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
        <button type="submit" className="btn btn-primary" disabled={!title.trim() || saving}>Create item</button>
      </div>
    </form>
  );
}

/** Modal "New item" form. onCreated receives the created Item and the full ItemView; the board adds it without reloading. */
export default function WorkItemForm({ isOpen, onClose, project, products, members, queues, labelSuggestions = [], onCreated }) {
  return (
    <Modal isOpen={isOpen} onClose={onClose} labelledBy="work-item-form-title" className="work-form-modal">
      {isOpen && <FormBody project={project} products={products} members={members} queues={queues} labelSuggestions={labelSuggestions} onClose={onClose} onCreated={onCreated} />}
    </Modal>
  );
}
