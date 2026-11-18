import { useState } from 'react';
import Modal from '../ui/Modal';
import Select from '../ui/Select';
import TagInput from '../ui/TagInput';

/** The page permissions an access rule can grant, in the order the wiki documents them. */
export const ACCESS_PERMISSIONS = ['view', 'comment', 'edit', 'modify', 'upload', 'rename', 'delete'];

/** The fields live inside the modal, so each opening starts from the defaults. */
function AccessRuleFields({ onClose, onInsert }) {
  const [permission, setPermission] = useState('view');
  const [principals, setPrincipals] = useState([]);
  const submit = (e) => {
    e.preventDefault();
    if (principals.length === 0) return;
    onInsert({ permission, principals });
  };
  return (
    <form onSubmit={submit}>
      <h2 id="access-rule-title" style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-md)' }}>Access rule</h2>
      <label className="field-label" htmlFor="access-rule-permission">Permission</label>
      <Select id="access-rule-permission" value={permission} options={ACCESS_PERMISSIONS} onChange={setPermission} />
      <label className="field-label" htmlFor="access-rule-names" style={{ marginTop: 'var(--space-md)' }}>Allowed to</label>
      <TagInput id="access-rule-names" value={principals} onChange={setPrincipals} placeholder="Add a group, role or user…" />
      <p className="field-hint">Press Enter after each name. Only the names listed keep this permission on the page.</p>
      <div className="modal-actions">
        <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
        <button type="submit" className="btn btn-primary" disabled={principals.length === 0}>Insert</button>
      </div>
    </form>
  );
}

/**
 * Asks for an access rule's permission and names. `onInsert({ permission, principals })` only ever receives one of
 * the seven permissions and at least one name; writing the rule into the page is the caller's job.
 */
export default function AccessRuleForm({ open, onClose, onInsert }) {
  return (
    <Modal isOpen={open} onClose={onClose} labelledBy="access-rule-title" testId="access-rule-form">
      <AccessRuleFields onClose={onClose} onInsert={onInsert} />
    </Modal>
  );
}
