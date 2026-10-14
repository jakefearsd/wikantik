import { useState } from 'react';
import { displayNameChanged, displayNameError, isDisplayNameRejection } from '../../utils/displayName';

// Derives the form's field values from a user record (edit mode) or the blank
// defaults (create mode). Shared by the initial-mount lazy initializer and the
// render-time prop-change adjustment below, so both paths agree.
function buildForm(user) {
  return user
    ? { loginName: user.loginName, fullName: user.fullName || '', email: user.email || '', bio: user.bio || '', password: '' }
    : { loginName: '', fullName: '', email: '', bio: '', password: '' };
}

export default function UserFormModal({ user, isOpen, onClose, onSave }) {
  const isEdit = !!user;
  const [form, setForm] = useState(() => buildForm(user));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  // The server's 400/409 message for the display name, and whether the field has been left once.
  const [nameError, setNameError] = useState(null);
  const [nameTouched, setNameTouched] = useState(false);

  const [prevUser, setPrevUser] = useState(user);
  const [prevIsOpen, setPrevIsOpen] = useState(isOpen);
  if (user !== prevUser || isOpen !== prevIsOpen) {
    setPrevUser(user);
    setPrevIsOpen(isOpen);
    setForm(buildForm(user));
    setError(null);
    setNameError(null);
    setNameTouched(false);
  }

  if (!isOpen) return null;

  // A new account must have a valid display name. For an existing account the server re-checks it
  // only when it changes, so a legacy name that breaks the rules does not block other edits.
  const nameChanged = !isEdit || displayNameChanged(user.fullName, form.fullName);
  const clientNameError = nameChanged ? displayNameError(form.fullName) : null;
  const shownNameError = nameError || (nameTouched ? clientNameError : null);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError(null);
    setNameError(null);
    if (clientNameError) {
      setNameTouched(true);
      return;
    }
    setSaving(true);
    try {
      await onSave({ ...form, fullName: form.fullName.trim() });
      onClose();
    } catch (err) {
      if (err.status === 403) {
        setError('Session expired or not authorized. Please refresh the page and log in again.');
      } else if (isDisplayNameRejection(err, isEdit && nameChanged && !form.password)) {
        setNameError(err.message || 'That display name is not allowed');
      } else {
        setError(err.message || 'Failed to save user');
      }
    } finally {
      setSaving(false);
    }
  };

  const set = (key) => (e) => setForm(f => ({ ...f, [key]: e.target.value }));
  const setName = (e) => { set('fullName')(e); setNameError(null); };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-lg)' }}>
          {isEdit ? 'Edit User' : 'Create User'}
        </h2>

        {error && <div className="error-banner">{error}</div>}

        <form onSubmit={handleSubmit}>
          <div className="form-field">
            <label htmlFor="user-form-login-name">Login name</label>
            <input
              id="user-form-login-name"
              type="text"
              value={form.loginName}
              onChange={set('loginName')}
              disabled={isEdit}
              required
              autoFocus={!isEdit}
            />
            <small style={{ color: 'var(--text-secondary)', display: 'block', marginTop: '0.25rem' }}>
              Used to sign in and in access rules. Only administrators and the account owner see it.
            </small>
          </div>
          <div className="form-field">
            <label htmlFor="user-form-display-name">Display name</label>
            <input
              id="user-form-display-name"
              type="text"
              value={form.fullName}
              onChange={setName}
              onBlur={() => setNameTouched(true)}
              autoFocus={isEdit}
              aria-required="true"
              aria-invalid={shownNameError ? 'true' : undefined}
              aria-describedby={shownNameError ? 'user-form-display-name-error' : undefined}
            />
            {shownNameError ? (
              <small
                id="user-form-display-name-error"
                role="alert"
                data-testid="display-name-error"
                style={{ color: 'var(--color-danger, #ef4444)', display: 'block', marginTop: '0.25rem' }}
              >
                {shownNameError}
              </small>
            ) : (
              <small style={{ color: 'var(--text-secondary)', display: 'block', marginTop: '0.25rem' }}>
                What other people see. Required, 2 to 64 characters, no &ldquo;@&rdquo;, unique.
              </small>
            )}
          </div>
          <div className="form-field">
            <label>Email</label>
            <input type="email" value={form.email} onChange={set('email')} />
          </div>
          <div className="form-field">
            <label>Bio</label>
            <textarea
              value={form.bio}
              onChange={set('bio')}
              maxLength={1000}
              rows={3}
              placeholder="User bio..."
            />
            <small style={{ color: 'var(--text-secondary)', display: 'block', marginTop: '0.25rem' }}>
              {form.bio.length} / 1000
            </small>
          </div>
          <div className="form-field">
            <label>{isEdit ? 'New Password (leave blank to keep)' : 'Password'}</label>
            <input type="password" value={form.password} onChange={set('password')} required={!isEdit} minLength={8} />
            {(!isEdit || form.password.length > 0) && (
              <small style={{ color: form.password.length >= 8 ? 'var(--color-success, #22c55e)' : 'var(--text-secondary)', marginTop: '0.25rem', display: 'block' }}>
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
