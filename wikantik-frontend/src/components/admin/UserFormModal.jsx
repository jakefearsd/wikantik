import { useState, useEffect } from 'react';

export default function UserFormModal({ user, isOpen, onClose, onSave }) {
  const isEdit = !!user;
  const [form, setForm] = useState({ loginName: '', fullName: '', email: '', password: '' });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (user) {
      setForm({ loginName: user.loginName, fullName: user.fullName || '', email: user.email || '', password: '' });
    } else {
      setForm({ loginName: '', fullName: '', email: '', password: '' });
    }
    setError(null);
  }, [user, isOpen]);

  if (!isOpen) return null;

  const handleSubmit = async (e) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await onSave(form);
      onClose();
    } catch (err) {
      setError(err.message || 'Failed to save user');
    } finally {
      setSaving(false);
    }
  };

  const set = (key) => (e) => setForm(f => ({ ...f, [key]: e.target.value }));

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-lg)' }}>
          {isEdit ? 'Edit User' : 'Create User'}
        </h2>

        {error && <div className="error-banner">{error}</div>}

        <form onSubmit={handleSubmit}>
          <div className="form-field">
            <label>Login Name</label>
            <input
              type="text"
              value={form.loginName}
              onChange={set('loginName')}
              disabled={isEdit}
              required
              autoFocus={!isEdit}
            />
          </div>
          <div className="form-field">
            <label>Full Name</label>
            <input type="text" value={form.fullName} onChange={set('fullName')} autoFocus={isEdit} />
          </div>
          <div className="form-field">
            <label>Email</label>
            <input type="email" value={form.email} onChange={set('email')} />
          </div>
          <div className="form-field">
            <label>{isEdit ? 'New Password (leave blank to keep)' : 'Password'}</label>
            <input type="password" value={form.password} onChange={set('password')} required={!isEdit} minLength={8} />
            {(!isEdit || form.password.length > 0) && (
              <small style={{ color: form.password.length >= 8 ? 'var(--color-success, #22c55e)' : 'var(--color-muted, #888)', marginTop: '0.25rem', display: 'block' }}>
                {form.password.length} of 8 minimum characters
              </small>
            )}
          </div>

          <div className="modal-actions">
            <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving ? 'Saving…' : isEdit ? 'Save Changes' : 'Create User'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
