import { useEffect, useId, useState } from 'react';
import Modal from '../ui/Modal';
import Select from '../ui/Select';
import { listItems, createItem } from '../../api/work';

const LEVELS = [
  { value: 'task', label: 'Task', parentLevel: 'story' },
  { value: 'story', label: 'Story', parentLevel: 'epic' },
  { value: 'epic', label: 'Epic', parentLevel: null },
];
const KINDS = ['software', 'docs', 'admin'];
const OPEN_STATUSES = ['draft', 'refining', 'ready', 'in_progress', 'review'];

function FormBody({ project, products, onClose, onCreated }) {
  const uid = useId();
  const [level, setLevel] = useState('task');
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [parentKey, setParentKey] = useState('');
  const [productKey, setProductKey] = useState('');
  const [kind, setKind] = useState('software');
  const [priority, setPriority] = useState('3');
  const [dueOn, setDueOn] = useState('');
  const [candidates, setCandidates] = useState([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

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
    if (next === 'epic') setProductKey('');
  }

  function submit(e) {
    e.preventDefault();
    if (!title.trim() || saving) return;
    setSaving(true);
    setError(null);
    const draft = { project: project.key, level, kind, title: title.trim(), body, priority: Number(priority) };
    if (parentKey && current.parentLevel) draft.parentKey = parentKey;
    if (productKey && level !== 'epic') draft.productKey = productKey;
    if (dueOn) draft.dueOn = dueOn;
    createItem(draft)
      .then((view) => {
        onCreated?.(view.item);
        onClose();
      })
      .catch((err) => {
        setError(err.message || 'Could not create the item.');
        setSaving(false);
      });
  }

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
        {level !== 'epic' && (
          <div>
            <label className="work-form-field" htmlFor={`${uid}-product`}>Product</label>
            <Select id={`${uid}-product`} ariaLabel="Product" placeholder="No product" value={productKey}
              options={(products || []).map((p) => ({ value: p.key, label: p.name || p.key }))}
              onChange={setProductKey} />
          </div>
        )}
        <div>
          <label className="work-form-field" htmlFor={`${uid}-kind`}>Kind</label>
          <Select id={`${uid}-kind`} ariaLabel="Kind" value={kind} options={KINDS} onChange={setKind} />
        </div>
        <div>
          <label className="work-form-field" htmlFor={`${uid}-priority`}>Priority</label>
          <Select id={`${uid}-priority`} ariaLabel="Priority" value={priority}
            options={['1', '2', '3', '4'].map((p) => ({ value: p, label: `P${p}` }))} onChange={setPriority} />
        </div>
        <div>
          <label className="work-form-field" htmlFor={`${uid}-due`}>Due date</label>
          <input id={`${uid}-due`} type="date" className="form-input" value={dueOn}
            onChange={(e) => setDueOn(e.target.value)} />
        </div>
      </div>
      {error && <div role="alert" className="work-form-error">{error}</div>}
      <div className="modal-actions">
        <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
        <button type="submit" className="btn btn-primary" disabled={!title.trim() || saving}>Create item</button>
      </div>
    </form>
  );
}

/** Modal "New item" form. onCreated receives the created Item; the board adds it without reloading. */
export default function WorkItemForm({ isOpen, onClose, project, products, onCreated }) {
  return (
    <Modal isOpen={isOpen} onClose={onClose} labelledBy="work-item-form-title" className="work-form-modal">
      {isOpen && <FormBody project={project} products={products} onClose={onClose} onCreated={onCreated} />}
    </Modal>
  );
}
