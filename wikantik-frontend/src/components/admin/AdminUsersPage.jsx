import { useState, useEffect, useMemo } from 'react';
import { api } from '../../api/client';
import UserFormModal from './UserFormModal';
import '../../styles/admin.css';

export default function AdminUsersPage() {
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [search, setSearch] = useState('');
  const [sortKey, setSortKey] = useState('loginName');
  const [sortAsc, setSortAsc] = useState(true);

  // Modal state
  const [modalOpen, setModalOpen] = useState(false);
  const [editingUser, setEditingUser] = useState(null);
  const [confirmDelete, setConfirmDelete] = useState(null);

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

  useEffect(() => { loadUsers(); }, []);

  const filtered = useMemo(() => {
    const q = search.toLowerCase();
    let list = users;
    if (q) {
      list = list.filter(u =>
        u.loginName?.toLowerCase().includes(q) ||
        u.fullName?.toLowerCase().includes(q) ||
        u.email?.toLowerCase().includes(q)
      );
    }
    list = [...list].sort((a, b) => {
      const va = (a[sortKey] || '').toString().toLowerCase();
      const vb = (b[sortKey] || '').toString().toLowerCase();
      return sortAsc ? va.localeCompare(vb) : vb.localeCompare(va);
    });
    return list;
  }, [users, search, sortKey, sortAsc]);

  const handleSort = (key) => {
    if (sortKey === key) {
      setSortAsc(!sortAsc);
    } else {
      setSortKey(key);
      setSortAsc(true);
    }
  };

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

  const sortIcon = (key) => {
    if (sortKey !== key) return '';
    return sortAsc ? ' \u25B2' : ' \u25BC';
  };

  if (loading) return <div className="admin-loading">Loading users…</div>;
  if (error) return <div className="error-banner">{error}</div>;

  return (
    <div className="admin-users page-enter">
      <div className="admin-toolbar">
        <input
          type="text"
          className="admin-search"
          placeholder="Search users…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <button
          className="btn btn-primary"
          onClick={() => { setEditingUser(null); setModalOpen(true); }}
        >
          + Create User
        </button>
      </div>

      <div className="admin-table-wrapper">
        <table className="admin-table">
          <thead>
            <tr>
              <th onClick={() => handleSort('loginName')}>Login{sortIcon('loginName')}</th>
              <th onClick={() => handleSort('fullName')}>Full Name{sortIcon('fullName')}</th>
              <th onClick={() => handleSort('email')}>Email{sortIcon('email')}</th>
              <th onClick={() => handleSort('created')}>Created{sortIcon('created')}</th>
              <th>Status</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {filtered.length === 0 ? (
              <tr><td colSpan="6" className="admin-empty">No users found</td></tr>
            ) : filtered.map(u => (
              <tr key={u.loginName}>
                <td className="admin-cell-primary">{u.loginName}</td>
                <td>{u.fullName || '—'}</td>
                <td>{u.email || '—'}</td>
                <td className="admin-cell-date">{formatDate(u.created)}</td>
                <td>
                  <span className={`admin-badge ${u.locked ? 'locked' : 'active'}`}>
                    {u.locked ? 'Locked' : 'Active'}
                  </span>
                </td>
                <td className="admin-cell-actions">
                  <button
                    className="btn btn-ghost btn-sm"
                    onClick={() => { setEditingUser(u); setModalOpen(true); }}
                  >
                    Edit
                  </button>
                  <button
                    className="btn btn-ghost btn-sm"
                    onClick={() => handleToggleLock(u)}
                  >
                    {u.locked ? 'Unlock' : 'Lock'}
                  </button>
                  <button
                    className="btn btn-ghost btn-sm btn-danger"
                    onClick={() => setConfirmDelete(u.loginName)}
                  >
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

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
