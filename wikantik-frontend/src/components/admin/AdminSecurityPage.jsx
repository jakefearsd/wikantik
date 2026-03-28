import { useState, useEffect, useMemo } from 'react';
import { api } from '../../api/client';
import GroupFormModal from './GroupFormModal';
import PolicyGrantFormModal from './PolicyGrantFormModal';
import '../../styles/admin.css';

export default function AdminSecurityPage() {
  const [section, setSection] = useState('groups');

  return (
    <div className="admin-security page-enter">
      <div style={{ display: 'flex', gap: 'var(--space-sm)', marginBottom: 'var(--space-lg)' }}>
        <button
          className={`btn ${section === 'groups' ? 'btn-primary' : 'btn-ghost'}`}
          onClick={() => setSection('groups')}
        >
          Groups
        </button>
        <button
          className={`btn ${section === 'grants' ? 'btn-primary' : 'btn-ghost'}`}
          onClick={() => setSection('grants')}
        >
          Policy Grants
        </button>
      </div>

      {section === 'groups' ? <GroupsSection /> : <GrantsSection />}
    </div>
  );
}

/* ─── Groups Section ────────────────────────────────────────── */

function GroupsSection() {
  const [groups, setGroups] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [search, setSearch] = useState('');
  const [sortKey, setSortKey] = useState('name');
  const [sortAsc, setSortAsc] = useState(true);
  const [message, setMessage] = useState(null);

  // Modal state
  const [modalOpen, setModalOpen] = useState(false);
  const [editingGroup, setEditingGroup] = useState(null);
  const [confirmDelete, setConfirmDelete] = useState(null);

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
      list = list.filter(g =>
        g.name?.toLowerCase().includes(q) ||
        (g.members || []).some(m => m.toLowerCase().includes(q))
      );
    }
    list = [...list].sort((a, b) => {
      let va, vb;
      if (sortKey === 'count') {
        va = (a.members || []).length;
        vb = (b.members || []).length;
        return sortAsc ? va - vb : vb - va;
      }
      va = (a[sortKey] || '').toString().toLowerCase();
      vb = (b[sortKey] || '').toString().toLowerCase();
      return sortAsc ? va.localeCompare(vb) : vb.localeCompare(va);
    });
    return list;
  }, [groups, search, sortKey, sortAsc]);

  const handleSort = (key) => {
    if (sortKey === key) {
      setSortAsc(!sortAsc);
    } else {
      setSortKey(key);
      setSortAsc(true);
    }
  };

  const showMessage = (text, type) => {
    setMessage({ text, type });
    setTimeout(() => setMessage(null), 4000);
  };

  const handleSave = async ({ name, members }) => {
    await api.admin.updateGroup(name, { members });
    await loadGroups();
    showMessage(editingGroup ? `Group "${name}" updated` : `Group "${name}" created`, 'success');
  };

  const handleDelete = async (name) => {
    try {
      await api.admin.deleteGroup(name);
      setConfirmDelete(null);
      await loadGroups();
      showMessage(`Group "${name}" deleted`, 'success');
    } catch (err) {
      showMessage(err.message || 'Failed to delete group', 'error');
      setConfirmDelete(null);
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
        <div className={`admin-message ${message.type}`}>{message.text}</div>
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
              <th onClick={() => handleSort('count')}>Count{sortIcon('count')}</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {filtered.length === 0 ? (
              <tr><td colSpan="4" className="admin-empty">No groups found</td></tr>
            ) : filtered.map(g => (
              <tr key={g.name}>
                <td className="admin-cell-primary">{g.name}</td>
                <td>{(g.members || []).join(', ') || '—'}</td>
                <td>
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
                    onClick={() => setConfirmDelete(g.name)}
                    disabled={g.name === 'Admin'}
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
            <p>Are you sure you want to delete group <strong>{confirmDelete}</strong>? This cannot be undone.</p>
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

/* ─── Grants Section ────────────────────────────────────────── */

function GrantsSection() {
  const [grants, setGrants] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [message, setMessage] = useState(null);

  // Modal state
  const [modalOpen, setModalOpen] = useState(false);
  const [editingGrant, setEditingGrant] = useState(null);
  const [confirmDelete, setConfirmDelete] = useState(null);

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

  const showMessage = (text, type) => {
    setMessage({ text, type });
    setTimeout(() => setMessage(null), 4000);
  };

  const handleSave = async (data, id) => {
    if (id != null) {
      await api.admin.updatePolicyGrant(id, data);
    } else {
      await api.admin.createPolicyGrant(data);
    }
    await loadGrants();
    showMessage(id != null ? 'Policy grant updated' : 'Policy grant created', 'success');
  };

  const handleDelete = async (id) => {
    try {
      await api.admin.deletePolicyGrant(id);
      setConfirmDelete(null);
      await loadGrants();
      showMessage('Policy grant deleted', 'success');
    } catch (err) {
      showMessage(err.message || 'Failed to delete policy grant', 'error');
      setConfirmDelete(null);
    }
  };

  const isAllPermission = (grant) =>
    grant.permissionType === 'all' || grant.actions === '*';

  if (loading) return <div className="admin-loading">Loading policy grants…</div>;
  if (error) return <div className="error-banner">{error}</div>;

  return (
    <>
      {message && (
        <div className={`admin-message ${message.type}`}>{message.text}</div>
      )}

      <div className="admin-toolbar">
        <span />
        <button
          className="btn btn-primary"
          onClick={() => { setEditingGrant(null); setModalOpen(true); }}
        >
          + Create Grant
        </button>
      </div>

      <div className="admin-table-wrapper">
        <table className="admin-table">
          <thead>
            <tr>
              <th>Principal</th>
              <th>Type</th>
              <th>Target</th>
              <th style={{ width: '40%' }}>Actions</th>
              <th style={{ width: '120px', textAlign: 'right' }}>Manage</th>
            </tr>
          </thead>
          <tbody>
            {grants.length === 0 ? (
              <tr><td colSpan="5" className="admin-empty">No policy grants found</td></tr>
            ) : grants.map(g => (
              <tr key={g.id}>
                <td className="admin-cell-primary">
                  <span className="admin-badge active" style={{ marginRight: 'var(--space-xs)' }}>
                    {g.principalType}
                  </span>
                  {g.principalName}
                </td>
                <td>{isAllPermission(g) ? 'AllPermission' : g.permissionType}</td>
                <td>{g.target || '*'}</td>
                <td>
                  {isAllPermission(g) ? (
                    <span
                      className="admin-badge"
                      style={{
                        background: 'var(--color-warning, #f59e0b)',
                        color: '#000',
                      }}
                    >
                      * (all)
                    </span>
                  ) : (
                    (g.actions || '').split(',').filter(Boolean).map(action => (
                      <span
                        key={action}
                        className="admin-badge active"
                        style={{ marginRight: 'var(--space-xs)', marginBottom: 'var(--space-xs)', display: 'inline-block' }}
                      >
                        {action.trim()}
                      </span>
                    ))
                  )}
                </td>
                <td className="admin-cell-actions" style={{ justifyContent: 'flex-end' }}>
                  <button
                    className="btn btn-ghost btn-sm"
                    onClick={() => { setEditingGrant(g); setModalOpen(true); }}
                  >
                    Edit
                  </button>
                  <button
                    className="btn btn-ghost btn-sm btn-danger"
                    onClick={() => setConfirmDelete(g.id)}
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

      {confirmDelete != null && (
        <div className="modal-overlay" onClick={() => setConfirmDelete(null)}>
          <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
            <h3 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-md)' }}>
              Delete Policy Grant
            </h3>
            <p>Are you sure you want to delete this policy grant? This cannot be undone.</p>
            <div className="modal-actions" style={{ marginTop: 'var(--space-lg)' }}>
              <button className="btn btn-ghost" onClick={() => setConfirmDelete(null)}>Cancel</button>
              <button className="btn btn-primary btn-danger" onClick={() => handleDelete(confirmDelete)}>
                Delete Grant
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
