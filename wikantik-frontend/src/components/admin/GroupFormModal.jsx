import { useState, useEffect } from 'react';

export default function GroupFormModal({ group, isOpen, onClose, onSave }) {
  const isEdit = !!group;
  const [form, setForm] = useState({ name: '', members: [] });
  const [newMember, setNewMember] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (group) {
      setForm({ name: group.name, members: [...(group.members || [])] });
    } else {
      setForm({ name: '', members: [] });
    }
    setNewMember('');
    setError(null);
  }, [group, isOpen]);

  if (!isOpen) return null;

  const handleAddMember = () => {
    const trimmed = newMember.trim();
    if (!trimmed) return;
    if (form.members.includes(trimmed)) {
      setError(`"${trimmed}" is already a member`);
      return;
    }
    setForm(f => ({ ...f, members: [...f.members, trimmed] }));
    setNewMember('');
    setError(null);
  };

  const handleRemoveMember = (member) => {
    setForm(f => ({ ...f, members: f.members.filter(m => m !== member) }));
  };

  const handleMemberKeyDown = (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      handleAddMember();
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await onSave({ name: form.name, members: form.members });
      onClose();
    } catch (err) {
      setError(err.message || 'Failed to save group');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-lg)' }}>
          {isEdit ? 'Edit Group' : 'Create Group'}
        </h2>

        {error && <div className="error-banner">{error}</div>}

        <form onSubmit={handleSubmit}>
          <div className="form-field">
            <label>Group Name</label>
            <input
              type="text"
              value={form.name}
              onChange={(e) => setForm(f => ({ ...f, name: e.target.value }))}
              disabled={isEdit}
              required
              autoFocus={!isEdit}
            />
          </div>

          <div className="form-field">
            <label>Members</label>
            {form.members.length > 0 ? (
              <ul style={{ listStyle: 'none', padding: 0, margin: '0 0 var(--space-sm) 0' }}>
                {form.members.map(member => (
                  <li key={member} style={{
                    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                    padding: 'var(--space-xs) var(--space-sm)',
                    borderBottom: '1px solid var(--color-border, #e5e7eb)'
                  }}>
                    <span>{member}</span>
                    <button
                      type="button"
                      className="btn btn-ghost btn-sm btn-danger"
                      onClick={() => handleRemoveMember(member)}
                    >
                      Remove
                    </button>
                  </li>
                ))}
              </ul>
            ) : (
              <p style={{ color: 'var(--color-muted, #888)', margin: '0 0 var(--space-sm) 0' }}>
                No members yet
              </p>
            )}
            <div style={{ display: 'flex', gap: 'var(--space-sm)' }}>
              <input
                type="text"
                value={newMember}
                onChange={(e) => setNewMember(e.target.value)}
                onKeyDown={handleMemberKeyDown}
                placeholder="Enter member name…"
                style={{ flex: 1 }}
              />
              <button type="button" className="btn btn-ghost btn-sm" onClick={handleAddMember}>
                Add
              </button>
            </div>
          </div>

          <div className="modal-actions">
            <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving ? 'Saving…' : isEdit ? 'Save Changes' : 'Create Group'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
