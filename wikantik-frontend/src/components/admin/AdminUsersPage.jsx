import { useState, useEffect, useMemo } from 'react';
import { api } from '../../api/client';
import AdminPage from './AdminPage';
import PageHeader from './PageHeader';
import UserFormModal from './UserFormModal';
import { AdminTable } from './table';
import '../../styles/admin.css';

const COLUMNS = [
  { id: 'loginName', label: 'Login', sortable: true },
  { id: 'fullName', label: 'Full Name', render: (u) => u.fullName || '—', sortable: true },
  { id: 'email', label: 'Email', render: (u) => u.email || '—' },
  {
    id: 'created',
    label: 'Created',
    render: (u) => <span className="admin-cell-date">{formatDate(u.created)}</span>,
    sortable: true,
  },
  {
    id: 'lastLogin',
    label: 'Last login',
    render: (u) => <span className="admin-cell-date">{formatDateTime(u.lastLogin)}</span>,
    sortable: true,
  },
  {
    id: 'locked',
    label: 'Status',
    render: (u) => (
      <span className={`admin-badge ${u.locked ? 'locked' : 'active'}`}>
        {u.locked ? 'Locked' : 'Active'}
      </span>
    ),
  },
];

export default function AdminUsersPage() {
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [currentUser, setCurrentUser] = useState(null);

  // Modal state
  const [modalOpen, setModalOpen] = useState(false);
  const [editingUser, setEditingUser] = useState(null);
  const [confirmDelete, setConfirmDelete] = useState(null);

  // Add-to-group modal state
  const [groupModalOpen, setGroupModalOpen] = useState(false);
  const [pendingBulkRows, setPendingBulkRows] = useState(null);
  const [pendingBulkResolve, setPendingBulkResolve] = useState(null);

  const loadUsers = async () => {
    try {
      const data = await api.admin.listUsers();
      setUsers(data.users || []);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadUsers();
    api.getUser().then(u => setCurrentUser(u)).catch(() => {});
  }, []);

  const handleCreate = async (form) => {
    await api.admin.createUser(form);
    await loadUsers();
  };

  const handleUpdate = async (form) => {
    const data = { fullName: form.fullName, email: form.email };
    if (form.password) data.password = form.password;
    await api.admin.updateUser(form.loginName, data);
    await loadUsers();
  };

  const handleDelete = async (loginName) => {
    await api.admin.deleteUser(loginName);
    setConfirmDelete(null);
    await loadUsers();
  };

  const handleToggleLock = async (user) => {
    if (user.locked) {
      await api.admin.unlockUser(user.loginName);
    } else {
      await api.admin.lockUser(user.loginName);
    }
    await loadUsers();
  };

  // Build bulk actions; disable delete when actor is in selected set.
  const bulkActions = useMemo(() => [
    {
      id: 'lock',
      label: 'Lock',
      variant: 'default',
      confirm: {
        title: 'Lock Users',
        body: (selected) => (
          <p>Lock {selected.length} user{selected.length !== 1 ? 's' : ''}? They will be unable to log in.</p>
        ),
        confirmLabel: 'Lock Users',
      },
    },
    {
      id: 'unlock',
      label: 'Unlock',
      variant: 'default',
      confirm: {
        title: 'Unlock Users',
        body: (selected) => (
          <p>Unlock {selected.length} user{selected.length !== 1 ? 's' : ''}?</p>
        ),
        confirmLabel: 'Unlock Users',
      },
    },
    {
      id: 'delete',
      label: 'Delete',
      variant: 'danger',
      confirm: {
        title: 'Delete Users',
        body: (selected) => (
          <p>
            Permanently delete{' '}
            <strong>{selected.length} user{selected.length !== 1 ? 's' : ''}</strong>? This cannot be undone.
          </p>
        ),
        confirmLabel: 'Delete Users',
      },
      disabled: (rows) =>
        currentUser && rows.some(r => r.loginName === currentUser.loginName)
          ? 'Cannot delete yourself'
          : false,
    },
    {
      id: 'add-to-group',
      label: 'Add to group…',
      variant: 'default',
      // No generic confirm — handled by the group picker modal.
    },
  ], [currentUser]);

  const handleBulkAction = async (action, selectedRows) => {
    const ids = selectedRows.map(u => u.loginName);

    if (action.id === 'add-to-group') {
      // Return a Promise that resolves when the group picker completes.
      return new Promise((resolve) => {
        setPendingBulkRows(selectedRows);
        setPendingBulkResolve(() => async (groupName) => {
          setGroupModalOpen(false);
          setPendingBulkRows(null);
          setPendingBulkResolve(null);
          if (!groupName) {
            // Cancelled — return a no-op result so the table clears selection.
            resolve({ succeeded: [], failed: [], status: 'completed', message: 'Cancelled' });
            return;
          }
          const result = await api.admin.bulkUserAction('add-to-group', ids, { group: groupName });
          await loadUsers();
          resolve(result);
        });
        setGroupModalOpen(true);
      });
    }

    const result = await api.admin.bulkUserAction(action.id, ids);
    await loadUsers();
    return result;
  };

  const rowAction = (u) => [
    {
      id: 'edit',
      label: 'Edit',
      variant: 'default',
      onClick: () => { setEditingUser(u); setModalOpen(true); },
    },
    {
      id: 'lock',
      label: u.locked ? 'Unlock' : 'Lock',
      variant: 'default',
      onClick: () => handleToggleLock(u),
    },
    {
      id: 'delete',
      label: 'Delete',
      variant: 'danger',
      onClick: () => setConfirmDelete(u.loginName),
    },
  ];

  return (
    <AdminPage loading={loading} error={error} loadingLabel="Loading users…">
      <PageHeader
        title="Users"
        description="Manage accounts, roles, and access."
        actions={
          <button
            className="btn btn-primary"
            onClick={() => { setEditingUser(null); setModalOpen(true); }}
          >
            + Create User
          </button>
        }
      />

      <AdminTable
        rows={users}
        getRowKey={(u) => u.loginName}
        columns={COLUMNS}
        selectable
        bulkActions={bulkActions}
        onBulkAction={handleBulkAction}
        emptyMessage="No users found"
        rowAction={rowAction}
        kind="user"
        searchable={{ placeholder: 'Filter users…' }}
        initialSort={{ columnId: 'loginName', direction: 'asc' }}
      />

      <UserFormModal
        isOpen={modalOpen}
        user={editingUser}
        onClose={() => { setModalOpen(false); setEditingUser(null); }}
        onSave={editingUser ? handleUpdate : handleCreate}
      />

      {confirmDelete && (
        <div className="modal-overlay" onClick={() => setConfirmDelete(null)}>
          <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
            <h3 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-md)' }}>
              Delete User
            </h3>
            <p>Are you sure you want to delete <strong>{confirmDelete}</strong>? This cannot be undone.</p>
            <div className="modal-actions" style={{ marginTop: 'var(--space-lg)' }}>
              <button className="btn btn-ghost" onClick={() => setConfirmDelete(null)}>Cancel</button>
              <button className="btn btn-primary btn-danger" onClick={() => handleDelete(confirmDelete)}>
                Delete User
              </button>
            </div>
          </div>
        </div>
      )}

      {groupModalOpen && (
        <BulkAddToGroupModal
          rows={pendingBulkRows}
          onConfirm={pendingBulkResolve}
        />
      )}
    </AdminPage>
  );
}

// ---------------------------------------------------------------------------
// BulkAddToGroupModal — inline group picker for the bulk add-to-group action
// ---------------------------------------------------------------------------

function BulkAddToGroupModal({ rows, onConfirm }) {
  const [groups, setGroups] = useState([]);
  const [selected, setSelected] = useState('');
  const [loadingGroups, setLoadingGroups] = useState(true);
  const [groupError, setGroupError] = useState(null);

  useEffect(() => {
    api.admin.listGroups()
      .then(data => {
        setGroups((data.groups || []).map(g => g.name));
        setLoadingGroups(false);
      })
      .catch(err => {
        setGroupError(err.message);
        setLoadingGroups(false);
      });
  }, []);

  const userCount = rows ? rows.length : 0;

  return (
    <div className="modal-overlay" onClick={() => onConfirm(null)}>
      <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
        <h3 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-md)' }}>
          Add to Group
        </h3>
        <p style={{ marginBottom: 'var(--space-md)' }}>
          Add {userCount} user{userCount !== 1 ? 's' : ''} to a group:
        </p>

        {groupError && <div className="error-banner">{groupError}</div>}

        {loadingGroups ? (
          <p>Loading groups&hellip;</p>
        ) : (
          <div className="form-field">
            <label htmlFor="bulk-group-select">Group</label>
            {groups.length > 0 ? (
              <select
                id="bulk-group-select"
                value={selected}
                onChange={(e) => setSelected(e.target.value)}
                autoFocus
              >
                <option value="">— select a group —</option>
                {groups.map(g => (
                  <option key={g} value={g}>{g}</option>
                ))}
              </select>
            ) : (
              <p style={{ color: 'var(--text-muted)' }}>No groups exist yet. Create a group first.</p>
            )}
          </div>
        )}

        <div className="modal-actions" style={{ marginTop: 'var(--space-lg)' }}>
          <button className="btn btn-ghost" onClick={() => onConfirm(null)}>Cancel</button>
          <button
            className="btn btn-primary"
            disabled={!selected}
            onClick={() => onConfirm(selected)}
          >
            Add to Group
          </button>
        </div>
      </div>
    </div>
  );
}

function formatDate(dateStr) {
  if (!dateStr) return '—';
  try {
    return new Date(dateStr).toLocaleDateString();
  } catch {
    return dateStr;
  }
}

// Last-login wants the time of day too (date + short time), and renders an
// em dash when the account has never authenticated since tracking began.
function formatDateTime(dateStr) {
  if (!dateStr) return '—';
  try {
    return new Date(dateStr).toLocaleString([], {
      year: 'numeric', month: 'short', day: 'numeric',
      hour: '2-digit', minute: '2-digit',
    });
  } catch {
    return dateStr;
  }
}
