import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../api/client';
import '../../styles/admin.css';

const TABS = ['Dashboard', 'Orphaned Pages', 'Broken Links', 'Versions'];

export default function AdminContentPage() {
  const [tab, setTab] = useState('Dashboard');

  return (
    <div className="admin-content-page page-enter">
      <div className="admin-tabs">
        {TABS.map(t => (
          <button
            key={t}
            className={`admin-tab ${tab === t ? 'active' : ''}`}
            onClick={() => setTab(t)}
          >
            {t}
          </button>
        ))}
      </div>

      {tab === 'Dashboard' && <DashboardTab />}
      {tab === 'Orphaned Pages' && <OrphanedPagesTab />}
      {tab === 'Broken Links' && <BrokenLinksTab />}
      {tab === 'Versions' && <VersionsTab />}
    </div>
  );
}

// ---- Dashboard ----

function DashboardTab() {
  const [stats, setStats] = useState(null);
  const [loading, setLoading] = useState(true);
  const [reindexing, setReindexing] = useState(false);
  const [flushing, setFlushing] = useState(false);
  const [message, setMessage] = useState(null);

  const load = async () => {
    setLoading(true);
    try {
      setStats(await api.admin.getContentStats());
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const handleReindex = async () => {
    setReindexing(true);
    setMessage(null);
    try {
      const result = await api.admin.reindex();
      setMessage({ type: 'success', text: `Reindex started: ${result.pagesQueued} pages queued` });
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setReindexing(false);
    }
  };

  const handleFlushAll = async () => {
    setFlushing(true);
    setMessage(null);
    try {
      const result = await api.admin.flushCache();
      setMessage({ type: 'success', text: `Caches flushed: ${result.entriesRemoved} entries removed` });
      await load();
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setFlushing(false);
    }
  };

  if (loading) return <div className="admin-loading">Loading stats…</div>;

  return (
    <div>
      {message && (
        <div className={`admin-message ${message.type}`}>{message.text}</div>
      )}

      <div className="stats-grid">
        <div className="stat-card">
          <div className="stat-value">{stats?.pageCount ?? '—'}</div>
          <div className="stat-label">Total Pages</div>
        </div>
        <div className="stat-card warning">
          <div className="stat-value">{stats?.orphanedCount ?? '—'}</div>
          <div className="stat-label">Orphaned Pages</div>
        </div>
        <div className="stat-card warning">
          <div className="stat-value">{stats?.brokenLinkCount ?? '—'}</div>
          <div className="stat-label">Broken Links</div>
        </div>
      </div>

      <div className="admin-section-header">
        <h3>Actions</h3>
      </div>
      <div className="admin-actions-row">
        <button className="btn btn-primary" onClick={handleReindex} disabled={reindexing}>
          {reindexing ? 'Rebuilding…' : 'Rebuild Search Index'}
        </button>
        <button className="btn btn-ghost" onClick={handleFlushAll} disabled={flushing}>
          {flushing ? 'Flushing…' : 'Flush All Caches'}
        </button>
      </div>

      {stats?.caches?.length > 0 && (
        <>
          <div className="admin-section-header">
            <h3>Cache Statistics</h3>
          </div>
          <div className="admin-table-wrapper">
            <table className="admin-table">
              <thead>
                <tr>
                  <th>Cache</th>
                  <th>Size</th>
                  <th>Max</th>
                  <th>Hits</th>
                  <th>Misses</th>
                  <th>Hit Ratio</th>
                </tr>
              </thead>
              <tbody>
                {stats.caches.map(c => (
                  <tr key={c.fullName}>
                    <td className="admin-cell-primary">{c.name}</td>
                    <td>{c.size}</td>
                    <td>{c.maxSize}</td>
                    <td>{c.hits.toLocaleString()}</td>
                    <td>{c.misses.toLocaleString()}</td>
                    <td>
                      <span className={`admin-badge ${c.hitRatio > 80 ? 'active' : c.hitRatio > 50 ? '' : 'locked'}`}>
                        {c.hitRatio}%
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}

// ---- Orphaned Pages ----

function OrphanedPagesTab() {
  const [pages, setPages] = useState([]);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState(new Set());
  const [deleting, setDeleting] = useState(false);
  const [message, setMessage] = useState(null);

  const load = async () => {
    setLoading(true);
    try {
      const data = await api.admin.getOrphanedPages();
      setPages(data.pages || []);
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const toggleSelect = (name) => {
    setSelected(prev => {
      const next = new Set(prev);
      next.has(name) ? next.delete(name) : next.add(name);
      return next;
    });
  };

  const toggleAll = () => {
    if (selected.size === pages.length) {
      setSelected(new Set());
    } else {
      setSelected(new Set(pages));
    }
  };

  const handleDelete = async () => {
    if (!selected.size) return;
    setDeleting(true);
    setMessage(null);
    try {
      const result = await api.admin.bulkDeletePages([...selected]);
      setMessage({ type: 'success', text: `Deleted ${result.deleted.length} pages${result.failed.length ? `, ${result.failed.length} failed` : ''}` });
      setSelected(new Set());
      await load();
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setDeleting(false);
    }
  };

  if (loading) return <div className="admin-loading">Loading orphaned pages…</div>;

  return (
    <div>
      {message && <div className={`admin-message ${message.type}`}>{message.text}</div>}

      <div className="admin-toolbar">
        <span className="admin-count">{pages.length} orphaned page{pages.length !== 1 ? 's' : ''}</span>
        {selected.size > 0 && (
          <button className="btn btn-primary btn-danger" onClick={handleDelete} disabled={deleting}>
            {deleting ? 'Deleting…' : `Delete ${selected.size} Selected`}
          </button>
        )}
      </div>

      {pages.length === 0 ? (
        <div className="admin-empty-state">No orphaned pages found. All pages are linked to.</div>
      ) : (
        <div className="admin-table-wrapper">
          <table className="admin-table">
            <thead>
              <tr>
                <th style={{ width: 40 }}>
                  <input type="checkbox" checked={selected.size === pages.length} onChange={toggleAll} />
                </th>
                <th>Page Name</th>
              </tr>
            </thead>
            <tbody>
              {pages.map(name => (
                <tr key={name}>
                  <td><input type="checkbox" checked={selected.has(name)} onChange={() => toggleSelect(name)} /></td>
                  <td>
                    <Link to={`/wiki/${name}`} className="admin-page-link">{name}</Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// ---- Broken Links ----

function BrokenLinksTab() {
  const [links, setLinks] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.admin.getBrokenLinks()
      .then(data => setLinks(data.links || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="admin-loading">Scanning for broken links…</div>;

  return (
    <div>
      <div className="admin-toolbar">
        <span className="admin-count">{links.length} broken link{links.length !== 1 ? 's' : ''}</span>
      </div>

      {links.length === 0 ? (
        <div className="admin-empty-state">No broken links found. All references point to existing pages.</div>
      ) : (
        <div className="admin-table-wrapper">
          <table className="admin-table">
            <thead>
              <tr>
                <th>Missing Page</th>
                <th>Referenced By</th>
              </tr>
            </thead>
            <tbody>
              {links.map(l => (
                <tr key={l.target}>
                  <td className="admin-cell-primary">{l.target}</td>
                  <td>
                    {l.referencedBy.map((ref, i) => (
                      <span key={ref}>
                        {i > 0 && ', '}
                        <Link to={`/wiki/${ref}`} className="admin-page-link">{ref}</Link>
                      </span>
                    ))}
                    <span className="admin-count-badge">{l.referrerCount}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// ---- Version Management ----

function VersionsTab() {
  const [pageName, setPageName] = useState('');
  const [versions, setVersions] = useState(null);
  const [keepLatest, setKeepLatest] = useState(3);
  const [purging, setPurging] = useState(false);
  const [message, setMessage] = useState(null);

  const loadVersions = async () => {
    if (!pageName.trim()) return;
    setMessage(null);
    try {
      const data = await api.getHistory(pageName.trim());
      setVersions(data.versions || []);
    } catch (err) {
      setMessage({ type: 'error', text: `Could not load history: ${err.message}` });
      setVersions(null);
    }
  };

  const handlePurge = async () => {
    setPurging(true);
    setMessage(null);
    try {
      const result = await api.admin.purgeVersions(pageName.trim(), keepLatest);
      setMessage({ type: 'success', text: `Purged ${result.purged} old versions, ${result.remaining} remaining` });
      await loadVersions();
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setPurging(false);
    }
  };

  return (
    <div>
      {message && <div className={`admin-message ${message.type}`}>{message.text}</div>}

      <div className="version-lookup">
        <div className="form-field" style={{ flex: 1 }}>
          <label>Page Name</label>
          <input
            type="text"
            value={pageName}
            onChange={(e) => setPageName(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && loadVersions()}
            placeholder="Enter page name…"
          />
        </div>
        <button className="btn btn-primary" onClick={loadVersions} style={{ alignSelf: 'flex-end' }}>
          Look Up
        </button>
      </div>

      {versions !== null && (
        <div style={{ marginTop: 'var(--space-lg)' }}>
          <p style={{ fontFamily: 'var(--font-ui)', color: 'var(--text-secondary)', marginBottom: 'var(--space-md)' }}>
            <strong>{pageName}</strong> has <strong>{versions.length}</strong> version{versions.length !== 1 ? 's' : ''}
          </p>

          {versions.length > 1 && (
            <div className="purge-controls">
              <div className="form-field">
                <label>Keep Latest</label>
                <input
                  type="number"
                  min="1"
                  max={versions.length}
                  value={keepLatest}
                  onChange={(e) => setKeepLatest(Math.max(1, parseInt(e.target.value) || 1))}
                  style={{ width: '80px' }}
                />
              </div>
              <span className="purge-summary">
                Will purge {Math.max(0, versions.length - keepLatest)} version{versions.length - keepLatest !== 1 ? 's' : ''}
              </span>
              <button
                className="btn btn-primary btn-danger"
                onClick={handlePurge}
                disabled={purging || versions.length <= keepLatest}
              >
                {purging ? 'Purging…' : 'Purge Old Versions'}
              </button>
            </div>
          )}

          <div className="admin-table-wrapper" style={{ marginTop: 'var(--space-md)' }}>
            <table className="admin-table">
              <thead>
                <tr>
                  <th>Version</th>
                  <th>Author</th>
                  <th>Date</th>
                  <th>Change Note</th>
                </tr>
              </thead>
              <tbody>
                {versions.map((v, i) => (
                  <tr key={v.version} className={i < keepLatest ? '' : 'version-purge-target'}>
                    <td className="admin-cell-primary">{v.version}</td>
                    <td>{v.author || '—'}</td>
                    <td className="admin-cell-date">{formatDate(v.lastModified)}</td>
                    <td>{v.changeNote || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}

function formatDate(dateStr) {
  if (!dateStr) return '—';
  try {
    return new Date(dateStr).toLocaleString();
  } catch {
    return dateStr;
  }
}
