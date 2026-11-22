import { useId, useState } from 'react';
import Modal from '../ui/Modal';
import { createSprint } from '../../api/work';

const FIELDS = ['name', 'goal', 'startsOn', 'endsOn'];

function FormBody({ projectKey, onClose, onCreated }) {
  const uid = useId();
  const [values, setValues] = useState({ name: '', goal: '', startsOn: '', endsOn: '' });
  const [errors, setErrors] = useState({});
  const [saving, setSaving] = useState(false);
  const set = (name) => (e) => setValues((v) => ({ ...v, [name]: e.target.value }));

  function problems() {
    const found = {};
    if (!values.name.trim()) found.name = 'A sprint needs a name.';
    if (!values.startsOn) found.startsOn = 'Choose a start date.';
    if (!values.endsOn) found.endsOn = 'Choose an end date.';
    if (values.startsOn && values.endsOn && values.endsOn < values.startsOn) {
      found.endsOn = 'The end date must be on or after the start date.';
    }
    return found;
  }

  function submit(e) {
    e.preventDefault();
    if (saving) return;
    const found = problems();
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    const body = { name: values.name.trim(), startsOn: values.startsOn, endsOn: values.endsOn };
    if (values.goal.trim()) body.goal = values.goal.trim();
    setSaving(true);
    createSprint(projectKey, body)
      .then((sprint) => { onCreated?.(sprint); onClose(); })
      .catch((err) => {
        const field = err?.body?.field;
        setErrors({ [FIELDS.includes(field) ? field : 'form']: err?.body?.message || err?.message || 'Could not create the sprint.' });
        setSaving(false);
      });
  }

  const fieldError = (name) => errors[name] && <span className="work-field-error" role="alert">{errors[name]}</span>;
  return (
    <form className="work-form" onSubmit={submit} noValidate>
      <h2 id="work-sprint-form-title" className="work-form-title">New sprint</h2>
      <label className="work-form-field" htmlFor={`${uid}-name`}>Name</label>
      <input id={`${uid}-name`} className="form-input" value={values.name} maxLength={60} onChange={set('name')} />
      {fieldError('name')}
      <label className="work-form-field" htmlFor={`${uid}-goal`}>Goal</label>
      <textarea id={`${uid}-goal`} className="form-input" rows={2} value={values.goal} maxLength={500} onChange={set('goal')} />
      {fieldError('goal')}
      <div className="work-form-row">
        <div>
          <label className="work-form-field" htmlFor={`${uid}-starts`}>Starts on</label>
          <input id={`${uid}-starts`} type="date" className="form-input" value={values.startsOn} onChange={set('startsOn')} />
          {fieldError('startsOn')}
        </div>
        <div>
          <label className="work-form-field" htmlFor={`${uid}-ends`}>Ends on</label>
          <input id={`${uid}-ends`} type="date" className="form-input" value={values.endsOn} onChange={set('endsOn')} />
          {fieldError('endsOn')}
        </div>
      </div>
      {fieldError('form')}
      <div className="modal-actions">
        <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
        <button type="submit" className="btn btn-primary" disabled={saving}>Create sprint</button>
      </div>
    </form>
  );
}

/** Modal "New sprint" form (a lead's). onCreated receives the new sprint. */
export default function WorkSprintForm({ isOpen, onClose, projectKey, onCreated }) {
  return (
    <Modal isOpen={isOpen} onClose={onClose} labelledBy="work-sprint-form-title" className="work-form-modal">
      {isOpen && <FormBody projectKey={projectKey} onClose={onClose} onCreated={onCreated} />}
    </Modal>
  );
}
