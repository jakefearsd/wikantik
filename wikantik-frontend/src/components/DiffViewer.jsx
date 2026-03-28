import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { api } from '../api/client';
import '../styles/article.css';

export default function DiffViewer() {
  const { name } = useParams();
  const [versions, setVersions] = useState(null);
  const [fromVer, setFromVer] = useState(null);
  const [toVer, setToVer] = useState(null);
  const [diffHtml, setDiffHtml] = useState(null);
  const [loading, setLoading] = useState(true);
  const [diffLoading, setDiffLoading] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    setLoading(true);
    api.getHistory(name)
      .then(data => {
        const vers = data.versions || [];
        setVersions(vers);
        if (vers.length >= 2) {
          setFromVer(vers[vers.length - 1].version);
          setToVer(vers[0].version);
        } else if (vers.length === 1) {
          setFromVer(vers[0].version);
          setToVer(vers[0].version);
        }
      })
      .catch(err => setError(err.message || 'Failed to load version history'))
      .finally(() => setLoading(false));
  }, [name]);

  useEffect(() => {
    if (fromVer == null || toVer == null || fromVer === toVer) {
      setDiffHtml(null);
      return;
    }
    setDiffLoading(true);
    setError(null);
    api.getDiff(name, fromVer, toVer)
      .then(data => setDiffHtml(data.diffHtml || data.diff || ''))
      .catch(err => setError(err.message || 'Failed to load diff'))
      .finally(() => setDiffLoading(false));
  }, [name, fromVer, toVer]);

  if (loading) {
    return (
      <div className="page-enter" style={{ textAlign: 'center', padding: 'var(--space-2xl)', color: 'var(--text-muted)' }}>
        Loading version history...
      </div>
    );
  }

  if (error && !versions) {
    return (
      <div className="page-enter">
        <div className="error-banner">{error}</div>
      </div>
    );
  }

  return (
    <div className="page-enter">
      <h2 style={{
        fontFamily: 'var(--font-display)',
        fontSize: '1.5rem',
        fontWeight: 700,
        marginBottom: 'var(--space-lg)',
      }}>
        Compare versions: {name}
      </h2>

      {versions && versions.length < 2 && (
        <p style={{ color: 'var(--text-muted)', fontFamily: 'var(--font-ui)', fontSize: '0.9rem' }}>
          This page has fewer than two versions. Nothing to compare.
        </p>
      )}

      {versions && versions.length >= 2 && (
        <>
          <div style={{
            display: 'flex',
            gap: 'var(--space-lg)',
            alignItems: 'center',
            marginBottom: 'var(--space-xl)',
            flexWrap: 'wrap',
          }}>
            <label style={{ fontFamily: 'var(--font-ui)', fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
              From version:
              <select
                value={fromVer ?? ''}
                onChange={e => setFromVer(Number(e.target.value))}
                style={{
                  marginLeft: 'var(--space-sm)',
                  padding: 'var(--space-xs) var(--space-sm)',
                  border: '1px solid var(--border)',
                  borderRadius: 'var(--radius-sm)',
                  background: 'var(--bg)',
                  color: 'var(--text)',
                  fontFamily: 'var(--font-ui)',
                  fontSize: '0.85rem',
                }}
              >
                {versions.map(v => (
                  <option key={v.version} value={v.version}>
                    v{v.version} — {v.author || 'unknown'} ({v.changeNote || 'no note'})
                  </option>
                ))}
              </select>
            </label>

            <label style={{ fontFamily: 'var(--font-ui)', fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
              To version:
              <select
                value={toVer ?? ''}
                onChange={e => setToVer(Number(e.target.value))}
                style={{
                  marginLeft: 'var(--space-sm)',
                  padding: 'var(--space-xs) var(--space-sm)',
                  border: '1px solid var(--border)',
                  borderRadius: 'var(--radius-sm)',
                  background: 'var(--bg)',
                  color: 'var(--text)',
                  fontFamily: 'var(--font-ui)',
                  fontSize: '0.85rem',
                }}
              >
                {versions.map(v => (
                  <option key={v.version} value={v.version}>
                    v{v.version} — {v.author || 'unknown'} ({v.changeNote || 'no note'})
                  </option>
                ))}
              </select>
            </label>
          </div>

          {error && <div className="error-banner">{error}</div>}

          {fromVer === toVer && (
            <p style={{ color: 'var(--text-muted)', fontFamily: 'var(--font-ui)', fontSize: '0.9rem' }}>
              Select two different versions to see changes.
            </p>
          )}

          {diffLoading && (
            <div style={{ textAlign: 'center', padding: 'var(--space-xl)', color: 'var(--text-muted)' }}>
              Loading diff...
            </div>
          )}

          {diffHtml && !diffLoading && (
            <article
              className="article-prose"
              style={{
                background: 'var(--bg-elevated)',
                border: '1px solid var(--border)',
                borderRadius: 'var(--radius-md)',
                padding: 'var(--space-lg)',
                overflowX: 'auto',
              }}
              dangerouslySetInnerHTML={{ __html: diffHtml }}
            />
          )}
        </>
      )}
    </div>
  );
}
