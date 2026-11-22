import { useState } from 'react';
import Select from '../ui/Select';
import { PRIORITY_OPTIONS, STATUSES, STATUS_LABELS, TYPE_OPTIONS } from './boardModel';

const LEVELS = [{ value: 'epic', label: 'Epic' }, { value: 'story', label: 'Story' }, { value: 'task', label: 'Task' }];
const SORTS = [
  { value: 'updated', label: 'Recently updated' }, { value: 'created', label: 'Newest' },
  { value: 'priority', label: 'Priority' }, { value: 'due', label: 'Due soonest' },
];

/** A group of checkboxes over one multi-valued field. */
function Checks({ legend, name, options, draft, set }) {
  const chosen = draft[name] || [];
  const toggle = (value) => set(name, chosen.includes(value) ? chosen.filter((v) => v !== value) : [...chosen, value]);
  return (
    <fieldset className="work-find-group">
      <legend>{legend}</legend>
      {options.map((o) => (
        <label key={o.value} className="work-toggle">
          <input type="checkbox" checked={chosen.includes(o.value)} onChange={() => toggle(o.value)} /> {o.label}
        </label>
      ))}
    </fieldset>
  );
}

/** A labelled text-like input over one single-valued field. */
function Field({ label, name, draft, set, type = 'text', placeholder }) {
  return (
    <label className="work-toggle">{label}
      <input className="work-field-input" type={type} placeholder={placeholder} value={draft[name] || ''}
        onChange={(e) => set(name, e.target.value)} />
    </label>
  );
}

function sprintOptions(value) {
  const options = [{ value: 'active', label: 'Active sprint' }, { value: 'none', label: 'No sprint' }];
  if (value && /^\d+$/.test(value)) options.push({ value, label: `Sprint ${value}` });
  return options;
}

/**
 * The find filter as a form. It holds a draft of `value`; Apply hands the draft to onApply, Clear hands it an empty
 * filter. A page that changes `value` from outside remounts the bar (key) rather than syncing the draft in an effect.
 */
export default function WorkFilterBar({ value, onApply, projects = [], products = [], queues = [] }) {
  const [draft, setDraft] = useState(value || {});
  const set = (name, v) => setDraft((d) => ({ ...d, [name]: v }));
  const labels = (draft.label || []).join(', ');
  return (
    <form className="work-find-bar" aria-label="Filter" onSubmit={(e) => { e.preventDefault(); onApply(draft); }}>
      {projects.length > 0 && (
        <Checks legend="Projects" name="projects" draft={draft} set={set}
          options={projects.map((p) => ({ value: p.key, label: p.key }))} />
      )}
      <Checks legend="Status" name="status" draft={draft} set={set}
        options={STATUSES.map((s) => ({ value: s, label: STATUS_LABELS[s] }))} />
      <Checks legend="Level" name="level" draft={draft} set={set} options={LEVELS} />
      <Checks legend="Priority" name="priority" draft={draft} set={set} options={PRIORITY_OPTIONS} />
      <div className="work-board-toolbar">
        <Select ariaLabel="Type" placeholder="All types" value={draft.type} options={TYPE_OPTIONS} onChange={(v) => set('type', v)} />
        <label className="work-toggle">Labels
          <input className="work-field-input" value={labels} placeholder="ui, api"
            onChange={(e) => set('label', e.target.value.split(',').map((s) => s.trim()).filter(Boolean))} />
        </label>
        <Field label="Assignee" name="assignee" draft={draft} set={set} placeholder="Display name or me" />
        <Field label="Reporter" name="reporter" draft={draft} set={set} placeholder="Display name or me" />
        <Select ariaLabel="Queue" placeholder="Any queue" value={draft.queue}
          options={queues.map((q) => ({ value: q.key, label: q.name || q.key }))} onChange={(v) => set('queue', v)} />
        <Select ariaLabel="Product" placeholder="Any product" value={draft.product}
          options={products.map((p) => ({ value: p.key, label: p.name || p.key }))} onChange={(v) => set('product', v)} />
        <Select ariaLabel="Sprint" placeholder="Any sprint" value={draft.sprint} options={sprintOptions(draft.sprint)}
          onChange={(v) => set('sprint', v)} />
        <label className="work-toggle">
          <input type="checkbox" checked={draft.watching === true} onChange={(e) => set('watching', e.target.checked || undefined)} /> Watching
        </label>
        <Field label="Text" name="q" draft={draft} set={set} />
        <Field label="Due before" name="dueBefore" type="date" draft={draft} set={set} />
        <Field label="Updated since" name="updatedSince" type="date" draft={draft} set={set} />
        <Select ariaLabel="Sort" value={draft.sort || 'updated'} options={SORTS} onChange={(v) => set('sort', v)} />
      </div>
      <div className="work-actions">
        <button type="submit" className="btn btn-primary btn-sm">Apply</button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => onApply({})}>Clear</button>
      </div>
    </form>
  );
}
