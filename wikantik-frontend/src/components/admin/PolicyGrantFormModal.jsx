import { useState, useEffect } from 'react';

const ACTION_OPTIONS = {
  page: ['view', 'comment', 'edit', 'modify', 'upload', 'rename', 'delete'],
  wiki: ['createPages', 'createGroups', 'editPreferences', 'editProfile', 'login'],
  group: ['view', 'edit'],
};

const BUILT_IN_ROLES = ['Anonymous', 'Asserted', 'Authenticated', 'Admin', 'AllGroup'];

export default function PolicyGrantFormModal({ grant, isOpen, onClose, onSave }) {
  const isEdit = !!grant;
  const [form, setForm] = useState({
    principalType: 'role',
    principalName: '',
    permissionType: 'page',
    target: '*',
    selectedActions: [],
    allPermission: false,
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (grant) {
      const actions = (grant.actions || '').split(',').map(a => a.trim()).filter(Boolean);
      const isAll = actions.includes('*') || grant.permissionType === 'all';
      setForm({
        principalType: grant.principalType || 'role',
        principalName: grant.principalName || '',
        permissionType: isAll ? 'page' : (grant.permissionType || 'page'),
        target: grant.target || '*',
        selectedActions: isAll ? [] : actions,
        allPermission: isAll,
      });
    } else {
      setForm({
        principalType: 'role',
        principalName: '',
        permissionType: 'page',
        target: '*',
        selectedActions: [],
        allPermission: false,
      });
    }
    setError(null);
  }, [grant, isOpen]);

  if (!isOpen) return null;

  const set = (key) => (e) => setForm(f => ({ ...f, [key]: e.target.value }));

  const handlePermissionTypeChange = (e) => {
    setForm(f => ({ ...f, permissionType: e.target.value, selectedActions: [] }));
  };

  const handleActionToggle = (action) => {
    setForm(f => {
      const selected = f.selectedActions.includes(action)
        ? f.selectedActions.filter(a => a !== action)
        : [...f.selectedActions, action];
      return { ...f, selectedActions: selected };
    });
  };

  const handleAllPermissionToggle = () => {
    setForm(f => ({
      ...f,
      allPermission: !f.allPermission,
      selectedActions: [],
    }));
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const data = {
        principalType: form.principalType,
        principalName: form.principalName,
        permissionType: form.allPermission ? 'all' : form.permissionType,
        target: form.allPermission ? '*' : form.target,
        actions: form.allPermission ? '*' : form.selectedActions.join(','),
      };
      await onSave(data, isEdit ? grant.id : undefined);
      onClose();
    } catch (err) {
      setError(err.message || 'Failed to save policy grant');
    } finally {
      setSaving(false);
    }
  };

  const availableActions = ACTION_OPTIONS[form.permissionType] || [];

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-lg)' }}>
          {isEdit ? 'Edit Policy Grant' : 'Create Policy Grant'}
        </h2>

        {error && <div className="error-banner">{error}</div>}

        <form onSubmit={handleSubmit}>
          <div className="form-field">
            <label>Principal Type</label>
            <select value={form.principalType} onChange={set('principalType')}>
              <option value="role">Role</option>
              <option value="user">User</option>
              <option value="group">Group</option>
            </select>
          </div>

          <div className="form-field">
            <label>Principal Name</label>
            <input
              type="text"
              value={form.principalName}
              onChange={set('principalName')}
              required
              autoFocus
              list={form.principalType === 'role' ? 'built-in-roles' : undefined}
            />
            {form.principalType === 'role' && (
              <datalist id="built-in-roles">
                {BUILT_IN_ROLES.map(r => (
                  <option key={r} value={r} />
                ))}
              </datalist>
            )}
          </div>

          <label style={{
            display: 'flex',
            alignItems: 'center',
            gap: 'var(--space-sm, 8px)',
            padding: 'var(--space-sm, 8px) var(--space-md, 12px)',
            background: form.allPermission ? 'var(--color-warning-bg, #fce4b8)' : 'var(--color-bg-subtle, #f5f0eb)',
            borderRadius: 'var(--radius, 6px)',
            cursor: 'pointer',
            fontWeight: form.allPermission ? 600 : 400,
            fontSize: '0.9rem',
            marginBottom: 'var(--space-md, 12px)',
            border: '1px solid var(--color-border, #d4c9bc)',
          }}>
            <input
              type="checkbox"
              checked={form.allPermission}
              onChange={handleAllPermissionToggle}
              style={{ width: '16px', height: '16px' }}
            />
            Grant AllPermission (full admin access)
          </label>

          {!form.allPermission && (
            <>
              <div className="form-field">
                <label>Permission Type</label>
                <select value={form.permissionType} onChange={handlePermissionTypeChange}>
                  <option value="page">Page</option>
                  <option value="wiki">Wiki</option>
                  <option value="group">Group</option>
                </select>
              </div>

              <div className="form-field">
                <label>Target</label>
                <input
                  type="text"
                  value={form.target}
                  onChange={set('target')}
                  placeholder="* (all pages)"
                />
              </div>

              <div className="form-field">
                <label>Actions</label>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--space-sm)' }}>
                  {availableActions.map(action => (
                    <label key={action} style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-xs)' }}>
                      <input
                        type="checkbox"
                        checked={form.selectedActions.includes(action)}
                        onChange={() => handleActionToggle(action)}
                      />
                      {action}
                    </label>
                  ))}
                </div>
              </div>
            </>
          )}

          <div className="modal-actions">
            <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving ? 'Saving…' : isEdit ? 'Save Changes' : 'Create Grant'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
