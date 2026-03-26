import { useState } from 'react';
import { api } from '../api/client';

export default function ChangeNotesPanel({ pageName }) {
  const [expanded, setExpanded] = useState(false);
  const [versions, setVersions] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const toggle = async () => {
    if (!expanded && versions === null) {
      setLoading(true);
      try {
        const data = await api.getHistory(pageName);
        setVersions(data.versions || []);
      } catch (err) {
        setError(err.message || 'Failed to load history');
      } finally {
        setLoading(false);
      }
    }
    setExpanded(v => !v);
  };

  const chevron = expanded ? '▾' : '▸';

  return (
    <div style={{ marginBottom: 'var(--space-lg)' }}>
      <button
        onClick={toggle}
        style={{
          color: 'var(--text-muted)',
          fontSize: '0.8rem',
          cursor: 'pointer',
          background: 'none',
          border: 'none',
          padding: '0',
          userSelect: 'none',
        }}
      >
        Change Notes {chevron}
      </button>

      {expanded && (
        <div
          style={{
            marginTop: 'var(--space-sm)',
            background: 'var(--bg-elevated)',
            border: '1px solid var(--border)',
            borderRadius: 'var(--radius-md)',
            padding: 'var(--space-md)',
          }}
        >
          {loading && <div style={{ color: 'var(--text-muted)', fontSize: '0.875rem' }}>Loading…</div>}
          {error && <div className="error-banner">{error}</div>}
          {versions && versions.length === 0 && (
            <div style={{ color: 'var(--text-muted)', fontSize: '0.875rem' }}>No history available.</div>
          )}
          {versions && versions.length > 0 && (
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.875rem' }}>
              <thead>
                <tr>
                  {['Version', 'Author', 'Date', 'Note'].map(h => (
                    <th key={h} style={{
                      textAlign: 'left',
                      color: 'var(--text-muted)',
                      fontSize: '0.75rem',
                      fontWeight: 500,
                      textTransform: 'uppercase',
                      letterSpacing: '0.04em',
                      paddingBottom: 'var(--space-xs)',
                      borderBottom: '1px solid var(--border)',
                    }}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {versions.map(v => (
                  <tr key={v.version} style={{ borderBottom: '1px solid var(--border)' }}>
                    <td style={{ padding: 'var(--space-xs) var(--space-sm) var(--space-xs) 0', whiteSpace: 'nowrap', color: 'var(--text-muted)' }}>
                      v{v.version}
                    </td>
                    <td style={{ padding: 'var(--space-xs) var(--space-sm)', whiteSpace: 'nowrap' }}>
                      {v.author || '—'}
                    </td>
                    <td style={{ padding: 'var(--space-xs) var(--space-sm)', whiteSpace: 'nowrap', color: 'var(--text-muted)' }}>
                      {v.lastModified ? new Date(v.lastModified).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' }) : '—'}
                    </td>
                    <td style={{ padding: 'var(--space-xs) 0 var(--space-xs) var(--space-sm)', color: v.changeNote ? 'inherit' : 'var(--text-muted)' }}>
                      {v.changeNote || '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  );
}
