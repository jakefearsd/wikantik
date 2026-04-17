import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../api/client';

const PREVIEW_CHARS = 200;

export default function ChunkInspectorTab() {
  const [pageInput, setPageInput] = useState('');
  const [loadedPage, setLoadedPage] = useState(null);
  const [chunks, setChunks] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [outliers, setOutliers] = useState(null);
  const [outliersError, setOutliersError] = useState(null);

  useEffect(() => {
    api.admin.getChunkOutliers()
      .then(setOutliers)
      .catch((e) => setOutliersError(e.message || 'Failed to load outliers'));
  }, []);

  const handleLoad = async () => {
    const name = pageInput.trim();
    if (!name) return;
    setLoading(true);
    setError(null);
    setChunks(null);
    try {
      const data = await api.admin.getChunks(name);
      setLoadedPage(data.page || name);
      setChunks(data.chunks || []);
    } catch (e) {
      if (e.code === 'page_not_found' || e.status === 404) {
        setError(`No chunks found for page ${name}`);
      } else {
        setError(e.message || 'Failed to load chunks');
      }
    } finally {
      setLoading(false);
    }
  };

  const onKeyDown = (e) => {
    if (e.key === 'Enter') handleLoad();
  };

  return (
    <div className="chunk-inspector-tab">
      <div className="admin-section-header">
        <h3>Chunk Inspector</h3>
      </div>

      <div className="admin-actions-row">
        <div className="form-field" style={{ flex: 1 }}>
          <label htmlFor="chunk-inspector-page">Page Name</label>
          <input
            id="chunk-inspector-page"
            type="text"
            value={pageInput}
            onChange={(e) => setPageInput(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder="Enter page name…"
          />
        </div>
        <button
          className="btn btn-primary"
          onClick={handleLoad}
          disabled={loading || !pageInput.trim()}
          style={{ alignSelf: 'flex-end' }}
        >
          {loading ? 'Loading…' : 'Load'}
        </button>
      </div>

      {error && (
        <div className="admin-message error" role="alert" style={{ marginTop: 'var(--space-md)' }}>
          {error}
        </div>
      )}

      {chunks !== null && !error && (
        <ChunkList pageName={loadedPage} chunks={chunks} />
      )}

      <div className="admin-section-header" style={{ marginTop: 'var(--space-xl)' }}>
        <h3>Corpus outliers</h3>
      </div>
      {outliersError && (
        <div className="admin-message error" role="alert">{outliersError}</div>
      )}
      {!outliers && !outliersError && (
        <div className="admin-loading">Loading outliers…</div>
      )}
      {outliers && (
        <div className="outlier-grid" style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))',
          gap: 'var(--space-md)',
        }}>
          <OutlierTable
            title="Most chunks"
            rows={outliers.most_chunks}
            columns={[
              { key: 'page_name', label: 'Page', render: (r) => <PageRef name={r.page_name} /> },
              { key: 'chunk_count', label: 'Chunks' },
              { key: 'max_tokens', label: 'Max tok' },
            ]}
          />
          <OutlierTable
            title="Single giant chunk"
            rows={outliers.large_single_chunks}
            columns={[
              { key: 'page_name', label: 'Page', render: (r) => <PageRef name={r.page_name} /> },
              { key: 'max_tokens', label: 'Tokens' },
              { key: 'char_count', label: 'Chars' },
            ]}
          />
          <OutlierTable
            title="Oversized chunks (>max)"
            rows={outliers.oversized_chunks}
            columns={[
              { key: 'page_name', label: 'Page', render: (r) => <PageRef name={r.page_name} /> },
              { key: 'max_tokens', label: 'Tokens' },
              { key: 'char_count', label: 'Chars' },
            ]}
          />
        </div>
      )}
    </div>
  );
}

function ChunkList({ pageName, chunks }) {
  if (chunks.length === 0) {
    return (
      <div className="admin-empty-state" style={{ marginTop: 'var(--space-md)' }}>
        Page has no chunks.
      </div>
    );
  }
  return (
    <div style={{ marginTop: 'var(--space-md)' }}>
      <p style={{ fontFamily: 'var(--font-ui)', color: 'var(--text-secondary)' }}>
        <PageRef name={pageName} /> — <strong>{chunks.length}</strong> chunk{chunks.length !== 1 ? 's' : ''}
      </p>
      {chunks.map((c) => (
        <ChunkCard key={c.chunk_index} chunk={c} />
      ))}
    </div>
  );
}

function ChunkCard({ chunk }) {
  const [expanded, setExpanded] = useState(false);
  const headingLabel = (chunk.heading_path && chunk.heading_path.length > 0)
    ? chunk.heading_path.join(' > ')
    : '(no heading)';
  const preview = chunk.text.length > PREVIEW_CHARS
    ? chunk.text.slice(0, PREVIEW_CHARS) + '…'
    : chunk.text;
  const canExpand = chunk.text.length > PREVIEW_CHARS;
  return (
    <div className="stat-card" style={{
      marginBottom: 'var(--space-md)',
      textAlign: 'left',
      padding: 'var(--space-md)',
    }}>
      <div style={{ fontFamily: 'var(--font-ui)', fontWeight: 600 }}>
        #{chunk.chunk_index} — {headingLabel}
      </div>
      <div className="stat-subtitle" style={{
        fontSize: '0.85em',
        color: 'var(--text-muted)',
        marginTop: 'var(--space-xs)',
      }}>
        {chunk.token_count_estimate} tokens · {chunk.char_count} chars · hash {shortHash(chunk.content_hash)}
      </div>
      {expanded ? (
        <pre
          data-testid={`chunk-full-text-${chunk.chunk_index}`}
          style={{
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
            marginTop: 'var(--space-sm)',
            fontSize: '0.9em',
          }}
        >{chunk.text}</pre>
      ) : (
        <div style={{ marginTop: 'var(--space-sm)', fontSize: '0.9em' }}>{preview}</div>
      )}
      {canExpand && (
        <button
          className="btn btn-ghost"
          style={{ marginTop: 'var(--space-sm)' }}
          onClick={() => setExpanded((v) => !v)}
        >
          {expanded ? 'Collapse' : 'Show full'}
        </button>
      )}
    </div>
  );
}

function OutlierTable({ title, rows, columns }) {
  return (
    <div className="admin-table-wrapper">
      <div style={{
        fontFamily: 'var(--font-ui)',
        fontWeight: 600,
        padding: 'var(--space-sm) 0',
      }}>
        {title}
      </div>
      {(!rows || rows.length === 0) ? (
        <div className="admin-empty-state">No entries.</div>
      ) : (
        <table className="admin-table">
          <thead>
            <tr>
              {columns.map((c) => <th key={c.key}>{c.label}</th>)}
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.page_name}-${i}`}>
                {columns.map((c) => (
                  <td key={c.key}>{c.render ? c.render(r) : r[c.key]}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function PageRef({ name }) {
  if (!name) return <span>—</span>;
  return <Link to={`/wiki/${name}`} className="admin-page-link">{name}</Link>;
}

function shortHash(hash) {
  if (!hash) return '—';
  return hash.length > 10 ? hash.slice(0, 10) : hash;
}
