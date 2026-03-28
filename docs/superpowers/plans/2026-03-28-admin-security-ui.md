# Admin Security UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Security" tab to the React admin panel with Groups and Policy Grants management pages.

**Architecture:** Three new React components (AdminSecurityPage, GroupFormModal, PolicyGrantFormModal) plus API client extensions and routing updates. Follows the existing AdminUsersPage/UserFormModal patterns exactly — same state management, same CSS classes, same modal pattern.

**Tech Stack:** React 18, React Router v6, Vite, custom CSS design system (no frameworks)

**Spec:** `docs/superpowers/specs/2026-03-28-admin-security-ui-design.md`

---

## File Structure

| File | Responsibility |
|------|---------------|
| `wikantik-frontend/src/api/client.js` | MODIFY — add admin group and policy grant API methods |
| `wikantik-frontend/src/components/admin/AdminSecurityPage.jsx` | NEW — Security tab page with Groups and Policy Grants sub-sections |
| `wikantik-frontend/src/components/admin/GroupFormModal.jsx` | NEW — create/edit group modal with member list |
| `wikantik-frontend/src/components/admin/PolicyGrantFormModal.jsx` | NEW — create/edit policy grant modal with context-sensitive checkboxes |
| `wikantik-frontend/src/components/admin/AdminLayout.jsx` | MODIFY — add Security nav tab |
| `wikantik-frontend/src/main.jsx` | MODIFY — add /admin/security route |
| `wikantik-frontend/src/components/Sidebar.jsx` | MODIFY — add Security link in admin section |

---

### Task 1: API Client Extensions

**Files:**
- Modify: `wikantik-frontend/src/api/client.js`

- [ ] **Step 1: Add group management API methods**

Add inside the `admin: {` object in `client.js`, after the `flushCache` method (before the closing `},`):

```javascript
    // Group Management
    listGroups: () => request('/admin/groups'),

    getGroup: (name) =>
      request(`/admin/groups/${encodeURIComponent(name)}`),

    updateGroup: (name, data) =>
      request(`/admin/groups/${encodeURIComponent(name)}`, {
        method: 'PUT',
        body: JSON.stringify(data),
      }),

    deleteGroup: (name) =>
      request(`/admin/groups/${encodeURIComponent(name)}`, {
        method: 'DELETE',
      }),

    // Policy Grant Management
    listPolicyGrants: () => request('/admin/policy'),

    createPolicyGrant: (data) =>
      request('/admin/policy', {
        method: 'POST',
        body: JSON.stringify(data),
      }),

    updatePolicyGrant: (id, data) =>
      request(`/admin/policy/${id}`, {
        method: 'PUT',
        body: JSON.stringify(data),
      }),

    deletePolicyGrant: (id) =>
      request(`/admin/policy/${id}`, {
        method: 'DELETE',
      }),
```

**API response shapes for reference:**

Groups list: `{ groups: [{ name, members: [string], creator, created, modifier, lastModified }] }`
Single group: `{ name, members: [string], creator, created, modifier, lastModified }`
Group update (PUT body): `{ members: ["alice", "bob"] }`

Policy list: `{ grants: [{ id, principalType, principalName, permissionType, target, actions }] }`
Policy create (POST body): `{ principalType, principalName, permissionType, target, actions }`
Policy update (PUT body): `{ principalType, principalName, permissionType, target, actions }`

- [ ] **Step 2: Verify the build compiles**

Run: `cd wikantik-frontend && npm run build`
Expected: Build succeeds (no import errors)

- [ ] **Step 3: Commit**

```bash
git add wikantik-frontend/src/api/client.js
git commit -m "Add admin API client methods for groups and policy grants"
```

---

### Task 2: GroupFormModal Component

**Files:**
- Create: `wikantik-frontend/src/components/admin/GroupFormModal.jsx`

This modal handles both creating and editing groups. It follows the `UserFormModal` pattern exactly.

- [ ] **Step 1: Create GroupFormModal.jsx**

```jsx
import { useState, useEffect } from 'react';

export default function GroupFormModal({ group, isOpen, onClose, onSave }) {
  const isEdit = !!group;
  const [name, setName] = useState('');
  const [members, setMembers] = useState([]);
  const [newMember, setNewMember] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (group) {
      setName(group.name);
      setMembers([...(group.members || [])]);
    } else {
      setName('');
      setMembers([]);
    }
    setNewMember('');
    setError(null);
  }, [group, isOpen]);

  if (!isOpen) return null;

  const handleAddMember = () => {
    const trimmed = newMember.trim();
    if (trimmed && !members.includes(trimmed)) {
      setMembers([...members, trimmed]);
      setNewMember('');
    }
  };

  const handleRemoveMember = (memberName) => {
    setMembers(members.filter(m => m !== memberName));
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      handleAddMember();
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!isEdit && !name.trim()) return;
    setSaving(true);
    setError(null);
    try {
      await onSave({ name: isEdit ? group.name : name.trim(), members });
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
          {isEdit ? `Edit Group: ${group.name}` : 'Create Group'}
        </h2>

        {error && <div className="error-banner">{error}</div>}

        <form onSubmit={handleSubmit}>
          <div className="form-field">
            <label>Group Name</label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              disabled={isEdit}
              required
              autoFocus={!isEdit}
            />
          </div>

          <div className="form-field">
            <label>Members</label>
            <div style={{
              border: '1px solid var(--color-border, #d4c9bc)',
              borderRadius: 'var(--radius, 6px)',
              padding: 'var(--space-sm, 8px)',
              marginBottom: 'var(--space-sm, 8px)',
              minHeight: '48px',
            }}>
              {members.length === 0 && (
                <div style={{ color: 'var(--color-muted, #888)', fontSize: '0.85rem', padding: '4px 8px' }}>
                  No members yet
                </div>
              )}
              {members.map(m => (
                <div key={m} style={{
                  display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                  padding: '4px 8px', background: 'var(--color-bg-subtle, #f5f0eb)',
                  borderRadius: '4px', marginBottom: '4px',
                }}>
                  <span style={{ fontSize: '0.85rem' }}>{m}</span>
                  <button
                    type="button"
                    className="btn btn-ghost btn-sm btn-danger"
                    onClick={() => handleRemoveMember(m)}
                    style={{ padding: '2px 8px', fontSize: '0.75rem' }}
                  >
                    Remove
                  </button>
                </div>
              ))}
            </div>
            <div style={{ display: 'flex', gap: 'var(--space-xs, 6px)' }}>
              <input
                type="text"
                value={newMember}
                onChange={(e) => setNewMember(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="Username…"
                style={{ flex: 1 }}
              />
              <button type="button" className="btn btn-ghost" onClick={handleAddMember}>
                Add
              </button>
            </div>
          </div>

          <div className="modal-actions">
            <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving ? 'Saving…' : isEdit ? 'Save Group' : 'Create Group'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd wikantik-frontend && npm run build`
Expected: Build succeeds

- [ ] **Step 3: Commit**

```bash
git add wikantik-frontend/src/components/admin/GroupFormModal.jsx
git commit -m "Add GroupFormModal component for create/edit group"
```

---

### Task 3: PolicyGrantFormModal Component

**Files:**
- Create: `wikantik-frontend/src/components/admin/PolicyGrantFormModal.jsx`

This modal creates/edits policy grants with context-sensitive action checkboxes that change based on the selected permission type.

- [ ] **Step 1: Create PolicyGrantFormModal.jsx**

```jsx
import { useState, useEffect } from 'react';

const PAGE_ACTIONS = ['view', 'comment', 'edit', 'modify', 'upload', 'rename', 'delete'];
const WIKI_ACTIONS = ['createPages', 'createGroups', 'editPreferences', 'editProfile', 'login'];
const GROUP_ACTIONS = ['view', 'edit'];

const ACTIONS_BY_TYPE = { page: PAGE_ACTIONS, wiki: WIKI_ACTIONS, group: GROUP_ACTIONS };

const BUILTIN_ROLES = ['All', 'Anonymous', 'Asserted', 'Authenticated', 'Admin'];

export default function PolicyGrantFormModal({ grant, isOpen, onClose, onSave }) {
  const isEdit = !!grant;
  const [form, setForm] = useState({
    principalType: 'role',
    principalName: '',
    permissionType: 'page',
    target: '*',
    actions: [],
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (grant) {
      const actions = grant.actions === '*' ? ['*'] : grant.actions.split(',').map(a => a.trim());
      setForm({
        principalType: grant.principalType,
        principalName: grant.principalName,
        permissionType: grant.permissionType,
        target: grant.target,
        actions,
      });
    } else {
      setForm({ principalType: 'role', principalName: '', permissionType: 'page', target: '*', actions: [] });
    }
    setError(null);
  }, [grant, isOpen]);

  if (!isOpen) return null;

  const availableActions = ACTIONS_BY_TYPE[form.permissionType] || PAGE_ACTIONS;
  const isAllPermission = form.actions.includes('*');

  const handleToggleAction = (action) => {
    setForm(f => {
      const has = f.actions.includes(action);
      const newActions = has ? f.actions.filter(a => a !== action) : [...f.actions.filter(a => a !== '*'), action];
      return { ...f, actions: newActions };
    });
  };

  const handleToggleAll = () => {
    setForm(f => {
      if (f.actions.includes('*')) {
        return { ...f, actions: [] };
      }
      return { ...f, actions: ['*'] };
    });
  };

  const handlePermissionTypeChange = (newType) => {
    setForm(f => ({ ...f, permissionType: newType, actions: [] }));
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!form.principalName.trim()) return;
    setSaving(true);
    setError(null);
    try {
      const actionsStr = form.actions.join(',');
      await onSave({
        principalType: form.principalType,
        principalName: form.principalName.trim(),
        permissionType: form.permissionType,
        target: form.target || '*',
        actions: actionsStr,
      }, isEdit ? grant.id : null);
      onClose();
    } catch (err) {
      setError(err.message || 'Failed to save grant');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-lg)' }}>
          {isEdit ? 'Edit Policy Grant' : 'Add Policy Grant'}
        </h2>

        {error && <div className="error-banner">{error}</div>}

        <form onSubmit={handleSubmit}>
          <div style={{ display: 'flex', gap: 'var(--space-md, 12px)' }}>
            <div className="form-field" style={{ flex: 1 }}>
              <label>Principal Type</label>
              <select
                value={form.principalType}
                onChange={(e) => setForm(f => ({ ...f, principalType: e.target.value }))}
              >
                <option value="role">role</option>
                <option value="group">group</option>
              </select>
            </div>
            <div className="form-field" style={{ flex: 2 }}>
              <label>Principal Name</label>
              <input
                type="text"
                value={form.principalName}
                onChange={(e) => setForm(f => ({ ...f, principalName: e.target.value }))}
                required
                list="role-hints"
                autoFocus
              />
              {form.principalType === 'role' && (
                <datalist id="role-hints">
                  {BUILTIN_ROLES.map(r => <option key={r} value={r} />)}
                </datalist>
              )}
            </div>
          </div>

          <div style={{ display: 'flex', gap: 'var(--space-md, 12px)' }}>
            <div className="form-field" style={{ flex: 1 }}>
              <label>Permission Type</label>
              <select
                value={form.permissionType}
                onChange={(e) => handlePermissionTypeChange(e.target.value)}
              >
                <option value="page">page</option>
                <option value="wiki">wiki</option>
                <option value="group">group</option>
              </select>
            </div>
            <div className="form-field" style={{ flex: 1 }}>
              <label>Target</label>
              <input
                type="text"
                value={form.target}
                onChange={(e) => setForm(f => ({ ...f, target: e.target.value }))}
                placeholder="*"
              />
            </div>
          </div>

          <div className="form-field">
            <label style={{ marginBottom: 'var(--space-sm, 8px)' }}>
              {form.permissionType.charAt(0).toUpperCase() + form.permissionType.slice(1)} Actions
            </label>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--space-xs, 6px)' }}>
              <label style={{
                display: 'flex', alignItems: 'center', gap: '4px', fontSize: '0.85rem',
                padding: '4px 8px', background: isAllPermission ? 'var(--color-warning-bg, #fce4b8)' : 'var(--color-bg-subtle, #f5f0eb)',
                borderRadius: '4px', cursor: 'pointer', fontWeight: isAllPermission ? 600 : 400,
              }}>
                <input type="checkbox" checked={isAllPermission} onChange={handleToggleAll} />
                * (all)
              </label>
              {!isAllPermission && availableActions.map(action => (
                <label key={action} style={{
                  display: 'flex', alignItems: 'center', gap: '4px', fontSize: '0.85rem',
                  padding: '4px 8px',
                  background: form.actions.includes(action) ? 'var(--color-bg-muted, #e8e0d8)' : 'var(--color-bg-subtle, #f5f0eb)',
                  borderRadius: '4px', cursor: 'pointer',
                  fontWeight: form.actions.includes(action) ? 600 : 400,
                }}>
                  <input
                    type="checkbox"
                    checked={form.actions.includes(action)}
                    onChange={() => handleToggleAction(action)}
                  />
                  {action}
                </label>
              ))}
            </div>
            <small style={{ color: 'var(--color-muted, #888)', marginTop: '6px', display: 'block', fontStyle: 'italic' }}>
              Showing {form.permissionType} actions. Change Permission Type to see others.
            </small>
          </div>

          <div className="modal-actions">
            <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
            <button type="submit" className="btn btn-primary" disabled={saving || (form.actions.length === 0)}>
              {saving ? 'Saving…' : isEdit ? 'Save Grant' : 'Add Grant'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd wikantik-frontend && npm run build`
Expected: Build succeeds

- [ ] **Step 3: Commit**

```bash
git add wikantik-frontend/src/components/admin/PolicyGrantFormModal.jsx
git commit -m "Add PolicyGrantFormModal with context-sensitive action checkboxes"
```

---

### Task 4: AdminSecurityPage Component

**Files:**
- Create: `wikantik-frontend/src/components/admin/AdminSecurityPage.jsx`

This is the main Security tab page with two sub-sections: Groups and Policy Grants. Follows the AdminUsersPage pattern for data loading, search, sort, and CRUD operations.

- [ ] **Step 1: Create AdminSecurityPage.jsx**

```jsx
import { useState, useEffect, useMemo } from 'react';
import { api } from '../../api/client';
import GroupFormModal from './GroupFormModal';
import PolicyGrantFormModal from './PolicyGrantFormModal';
import '../../styles/admin.css';

export default function AdminSecurityPage() {
  const [tab, setTab] = useState('groups');

  return (
    <div className="admin-security page-enter">
      <div className="admin-subtabs" style={{
        display: 'flex', gap: 'var(--space-sm, 8px)', marginBottom: 'var(--space-lg, 16px)',
      }}>
        <button
          className={`btn ${tab === 'groups' ? 'btn-primary' : 'btn-ghost'}`}
          onClick={() => setTab('groups')}
        >
          Groups
        </button>
        <button
          className={`btn ${tab === 'grants' ? 'btn-primary' : 'btn-ghost'}`}
          onClick={() => setTab('grants')}
        >
          Policy Grants
        </button>
      </div>

      {tab === 'groups' && <GroupsSection />}
      {tab === 'grants' && <PolicyGrantsSection />}
    </div>
  );
}

// ====================== Groups Sub-Section ======================

function GroupsSection() {
  const [groups, setGroups] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [search, setSearch] = useState('');
  const [sortKey, setSortKey] = useState('name');
  const [sortAsc, setSortAsc] = useState(true);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingGroup, setEditingGroup] = useState(null);
  const [confirmDelete, setConfirmDelete] = useState(null);
  const [message, setMessage] = useState(null);

  const loadGroups = async () => {
    try {
      const data = await api.admin.listGroups();
      setGroups(data.groups || []);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadGroups(); }, []);

  const filtered = useMemo(() => {
    const q = search.toLowerCase();
    let list = groups;
    if (q) {
      list = list.filter(g => g.name?.toLowerCase().includes(q));
    }
    list = [...list].sort((a, b) => {
      const va = (a[sortKey] || '').toString().toLowerCase();
      const vb = (b[sortKey] || '').toString().toLowerCase();
      return sortAsc ? va.localeCompare(vb) : vb.localeCompare(va);
    });
    return list;
  }, [groups, search, sortKey, sortAsc]);

  const handleSort = (key) => {
    if (sortKey === key) setSortAsc(!sortAsc);
    else { setSortKey(key); setSortAsc(true); }
  };

  const handleSave = async ({ name, members }) => {
    await api.admin.updateGroup(name, { members });
    setMessage({ type: 'success', text: `Group "${name}" saved.` });
    await loadGroups();
  };

  const handleDelete = async (name) => {
    try {
      await api.admin.deleteGroup(name);
      setConfirmDelete(null);
      setMessage({ type: 'success', text: `Group "${name}" deleted.` });
      await loadGroups();
    } catch (err) {
      setConfirmDelete(null);
      setMessage({ type: 'error', text: err.message });
    }
  };

  const sortIcon = (key) => {
    if (sortKey !== key) return '';
    return sortAsc ? ' \u25B2' : ' \u25BC';
  };

  if (loading) return <div className="admin-loading">Loading groups…</div>;
  if (error) return <div className="error-banner">{error}</div>;

  return (
    <>
      {message && (
        <div className={`admin-message ${message.type}`} onClick={() => setMessage(null)}>
          {message.text}
        </div>
      )}

      <div className="admin-toolbar">
        <input
          type="text"
          className="admin-search"
          placeholder="Search groups…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <button
          className="btn btn-primary"
          onClick={() => { setEditingGroup(null); setModalOpen(true); }}
        >
          + Create Group
        </button>
      </div>

      <div className="admin-table-wrapper">
        <table className="admin-table">
          <thead>
            <tr>
              <th onClick={() => handleSort('name')}>Name{sortIcon('name')}</th>
              <th>Members</th>
              <th style={{ textAlign: 'center' }}>Count</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {filtered.length === 0 ? (
              <tr><td colSpan="4" className="admin-empty">No groups found</td></tr>
            ) : filtered.map(g => (
              <tr key={g.name}>
                <td className="admin-cell-primary">{g.name}</td>
                <td style={{ color: 'var(--color-muted, #888)', fontSize: '0.85rem' }}>
                  {(g.members || []).join(', ') || '—'}
                </td>
                <td style={{ textAlign: 'center' }}>
                  <span className="admin-badge active">{(g.members || []).length}</span>
                </td>
                <td className="admin-cell-actions">
                  <button
                    className="btn btn-ghost btn-sm"
                    onClick={() => { setEditingGroup(g); setModalOpen(true); }}
                  >
                    Edit
                  </button>
                  <button
                    className="btn btn-ghost btn-sm btn-danger"
                    disabled={g.name === 'Admin'}
                    title={g.name === 'Admin' ? 'Cannot delete Admin group' : ''}
                    onClick={() => g.name !== 'Admin' && setConfirmDelete(g.name)}
                  >
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <GroupFormModal
        isOpen={modalOpen}
        group={editingGroup}
        onClose={() => { setModalOpen(false); setEditingGroup(null); }}
        onSave={handleSave}
      />

      {confirmDelete && (
        <div className="modal-overlay" onClick={() => setConfirmDelete(null)}>
          <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
            <h3 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-md)' }}>
              Delete Group
            </h3>
            <p>Are you sure you want to delete <strong>{confirmDelete}</strong>? This cannot be undone.</p>
            <div className="modal-actions" style={{ marginTop: 'var(--space-lg)' }}>
              <button className="btn btn-ghost" onClick={() => setConfirmDelete(null)}>Cancel</button>
              <button className="btn btn-primary btn-danger" onClick={() => handleDelete(confirmDelete)}>
                Delete Group
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

// ====================== Policy Grants Sub-Section ======================

function PolicyGrantsSection() {
  const [grants, setGrants] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingGrant, setEditingGrant] = useState(null);
  const [confirmDelete, setConfirmDelete] = useState(null);
  const [message, setMessage] = useState(null);

  const loadGrants = async () => {
    try {
      const data = await api.admin.listPolicyGrants();
      setGrants(data.grants || []);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadGrants(); }, []);

  const handleSave = async (data, id) => {
    if (id != null) {
      await api.admin.updatePolicyGrant(id, data);
    } else {
      await api.admin.createPolicyGrant(data);
    }
    setMessage({ type: 'success', text: 'Policy grant saved.' });
    await loadGrants();
  };

  const handleDelete = async (id) => {
    try {
      await api.admin.deletePolicyGrant(id);
      setConfirmDelete(null);
      setMessage({ type: 'success', text: 'Policy grant deleted.' });
      await loadGrants();
    } catch (err) {
      setConfirmDelete(null);
      setMessage({ type: 'error', text: err.message });
    }
  };

  if (loading) return <div className="admin-loading">Loading policy grants…</div>;
  if (error) return <div className="error-banner">{error}</div>;

  return (
    <>
      {message && (
        <div className={`admin-message ${message.type}`} onClick={() => setMessage(null)}>
          {message.text}
        </div>
      )}

      <div className="admin-toolbar" style={{ justifyContent: 'flex-end' }}>
        <button
          className="btn btn-primary"
          onClick={() => { setEditingGrant(null); setModalOpen(true); }}
        >
          + Add Grant
        </button>
      </div>

      <div className="admin-table-wrapper">
        <table className="admin-table">
          <thead>
            <tr>
              <th>Principal</th>
              <th>Type</th>
              <th>Target</th>
              <th>Actions</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {grants.length === 0 ? (
              <tr><td colSpan="5" className="admin-empty">No policy grants configured</td></tr>
            ) : grants.map(g => (
              <tr key={g.id}>
                <td>
                  <span className="admin-badge active" style={{ marginRight: '6px', fontSize: '0.7rem' }}>
                    {g.principalType}
                  </span>
                  <span className="admin-cell-primary">{g.principalName}</span>
                </td>
                <td>{g.permissionType}</td>
                <td style={{ fontFamily: 'var(--font-mono, monospace)' }}>{g.target}</td>
                <td>
                  {g.actions === '*' ? (
                    <span style={{
                      background: 'var(--color-warning-bg, #fce4b8)',
                      padding: '1px 6px', borderRadius: '4px', fontSize: '0.8rem', fontWeight: 600,
                    }}>
                      * (all)
                    </span>
                  ) : g.actions.split(',').map(a => (
                    <span key={a} style={{
                      background: 'var(--color-bg-muted, #e8e0d8)',
                      padding: '1px 6px', borderRadius: '4px', fontSize: '0.8rem', marginRight: '3px',
                      display: 'inline-block', marginBottom: '2px',
                    }}>
                      {a.trim()}
                    </span>
                  ))}
                </td>
                <td className="admin-cell-actions">
                  <button
                    className="btn btn-ghost btn-sm"
                    onClick={() => { setEditingGrant(g); setModalOpen(true); }}
                  >
                    Edit
                  </button>
                  <button
                    className="btn btn-ghost btn-sm btn-danger"
                    onClick={() => setConfirmDelete(g)}
                  >
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <PolicyGrantFormModal
        isOpen={modalOpen}
        grant={editingGrant}
        onClose={() => { setModalOpen(false); setEditingGrant(null); }}
        onSave={handleSave}
      />

      {confirmDelete && (
        <div className="modal-overlay" onClick={() => setConfirmDelete(null)}>
          <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
            <h3 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-md)' }}>
              Delete Policy Grant
            </h3>
            <p>
              Delete grant for <strong>{confirmDelete.principalName}</strong> ({confirmDelete.permissionType}: {confirmDelete.actions})?
            </p>
            <div className="modal-actions" style={{ marginTop: 'var(--space-lg)' }}>
              <button className="btn btn-ghost" onClick={() => setConfirmDelete(null)}>Cancel</button>
              <button className="btn btn-primary btn-danger" onClick={() => handleDelete(confirmDelete.id)}>
                Delete Grant
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd wikantik-frontend && npm run build`
Expected: Build succeeds

- [ ] **Step 3: Commit**

```bash
git add wikantik-frontend/src/components/admin/AdminSecurityPage.jsx
git commit -m "Add AdminSecurityPage with Groups and Policy Grants sub-sections"
```

---

### Task 5: Routing and Navigation Updates

**Files:**
- Modify: `wikantik-frontend/src/main.jsx`
- Modify: `wikantik-frontend/src/components/admin/AdminLayout.jsx`
- Modify: `wikantik-frontend/src/components/Sidebar.jsx`

- [ ] **Step 1: Add route in main.jsx**

Add the import at the top (after the AdminContentPage import):

```jsx
import AdminSecurityPage from './components/admin/AdminSecurityPage';
```

Add the route inside the `<Route path="/admin">` block (after the `content` route):

```jsx
<Route path="security" element={<AdminSecurityPage />} />
```

The full admin routes block should now be:

```jsx
<Route path="/admin" element={<AdminLayout />}>
  <Route index element={<Navigate to="users" replace />} />
  <Route path="users" element={<AdminUsersPage />} />
  <Route path="content" element={<AdminContentPage />} />
  <Route path="security" element={<AdminSecurityPage />} />
</Route>
```

- [ ] **Step 2: Add Security tab to AdminLayout.jsx**

Add a third NavLink in the `<nav className="admin-nav">` section:

```jsx
<NavLink to="/admin/security" className={({ isActive }) => `admin-nav-link ${isActive ? 'active' : ''}`}>
  Security
</NavLink>
```

The full nav should now be:

```jsx
<nav className="admin-nav">
  <NavLink to="/admin/users" className={({ isActive }) => `admin-nav-link ${isActive ? 'active' : ''}`}>
    Users
  </NavLink>
  <NavLink to="/admin/content" className={({ isActive }) => `admin-nav-link ${isActive ? 'active' : ''}`}>
    Content
  </NavLink>
  <NavLink to="/admin/security" className={({ isActive }) => `admin-nav-link ${isActive ? 'active' : ''}`}>
    Security
  </NavLink>
</nav>
```

- [ ] **Step 3: Add Security link to Sidebar.jsx**

In the admin section (after the "Content Management" Link), add:

```jsx
<Link
  to="/admin/security"
  className="sidebar-link"
  onClick={onMobileClose}
>
  Security
</Link>
```

- [ ] **Step 4: Build and verify**

Run: `cd wikantik-frontend && npm run build`
Expected: Build succeeds

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/main.jsx \
       wikantik-frontend/src/components/admin/AdminLayout.jsx \
       wikantik-frontend/src/components/Sidebar.jsx
git commit -m "Add Security tab to admin panel navigation and routing"
```

---

### Task 6: Build, Deploy, and Verify

- [ ] **Step 1: Full Maven build (includes React frontend via npm)**

```bash
mvn clean install -Dmaven.test.skip -T 1C
```
Expected: BUILD SUCCESS

- [ ] **Step 2: Deploy to local Tomcat**

```bash
tomcat/tomcat-11/bin/shutdown.sh 2>/dev/null; sleep 2
rm -rf tomcat/tomcat-11/webapps/ROOT
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war
tomcat/tomcat-11/bin/startup.sh
```

- [ ] **Step 3: Verify in browser**

1. Navigate to `http://localhost:8080/app/admin/security`
2. Log in as admin
3. Verify the "Security" tab appears alongside Users and Content
4. Verify the Groups sub-section shows the Admin group
5. Click Edit on Admin group — verify the member list modal works
6. Switch to Policy Grants sub-section — verify grants table renders
7. Click Add Grant — verify the context-sensitive checkboxes change when switching permission types

- [ ] **Step 4: Commit any fixes if needed**

```bash
git add -A
git commit -m "Fix any UI issues found during manual verification"
```
