import { useState, useEffect } from 'react';
import { api } from '../../api/client';
import PageLink from './PageLink';
import PageEditLink from './PageEditLink';

const NO_FM_LIMIT = 50;

export default function ContentEmbeddingsTab() {
  const [status, setStatus] = useState(null);
  const [pairs, setPairs] = useState([]);
  const [noFmPages, setNoFmPages] = useState([]);
  const [noFmTotal, setNoFmTotal] = useState(0);
  const [noFmOffset, setNoFmOffset] = useState(0);
  const [loading, setLoading] = useState(true);
  const [retraining, setRetraining] = useState(false);
  const [backfilling, setBackfilling] = useState(false);
  const [backfillStatus, setBackfillStatus] = useState(null);
  const [error, setError] = useState(null);

  const loadNoFmPages = async (currentOffset) => {
    try {
      const fmResult = await api.knowledge.getPagesWithoutFrontmatter(NO_FM_LIMIT, currentOffset);
      setNoFmPages(fmResult.pages || []);
      setNoFmTotal(fmResult.total || 0);
    } catch (err) {
      setError(err.message);
    }
  };

  const loadData = async () => {
    try {
      const s = await api.knowledge.getEmbeddingStatus();
      setStatus(s);
      await loadNoFmPages(0);
      if (s.content_ready) {
        const result = await api.knowledge.getSimilarPagePairs(50);
        setPairs(result.pairs || []);
      }
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadData(); }, []);

  useEffect(() => {
    if (noFmOffset > 0) {
      loadNoFmPages(noFmOffset);
    }
  }, [noFmOffset]);

  const handleRetrain = async () => {
    setRetraining(true);
    setError(null);
    try {
      await api.knowledge.retrainContent();
      await loadData();
    } catch (err) {
      setError(err.message);
    } finally {
      setRetraining(false);
    }
  };

  const handleBackfill = async () => {
    if (!confirm('This will generate default frontmatter for all pages that lack it. Continue?')) return;
    setBackfilling(true);
    setError(null);
    try {
      await api.knowledge.backfillFrontmatter();
      const poll = setInterval(async () => {
        try {
          const st = await api.knowledge.getBackfillStatus();
          setBackfillStatus(st);
          if (!st.running) {
            clearInterval(poll);
            setBackfilling(false);
            await loadData();
          }
        } catch (err) {
          clearInterval(poll);
          setBackfilling(false);
          setError(err.message);
        }
      }, 2000);
    } catch (err) {
      setBackfilling(false);
      setError(err.message);
    }
  };

  if (loading) return <div className="admin-loading">Loading content embeddings...</div>;
  if (error) return <div className="admin-error">{error}</div>;

  return (
    <div>
      {/* Status bar */}
      <div style={{ padding: 'var(--space-sm)', background: 'var(--surface-secondary)', borderRadius: 'var(--radius-md)', marginBottom: 'var(--space-md)', fontSize: '0.85em' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-md)', flexWrap: 'wrap' }}>
          <span><strong>Content:</strong> {status?.content_ready ? 'Ready' : 'Not trained'}</span>
          {status?.content_ready && (
            <>
              <span>{status.content_entity_count} pages</span>
              <span>dim {status.content_dimension}</span>
            </>
          )}
          {status?.content_last_trained && (
            <span><strong>Trained:</strong> {new Date(status.content_last_trained).toLocaleString()}</span>
          )}
          <button
            className="btn btn-primary btn-sm"
            onClick={handleRetrain}
            disabled={retraining}
          >
            {retraining ? 'Retraining...' : 'Retrain Content'}
          </button>
        </div>
      </div>

      {noFmTotal > 0 && (
        <div style={{ marginBottom: 'var(--space-md)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', marginBottom: 'var(--space-sm)' }}>
            <h4 style={{ fontSize: '0.95em', margin: 0 }}>
              Pages Without Frontmatter <span style={{ color: 'var(--text-muted)', fontWeight: 'normal' }}>({noFmTotal})</span>
            </h4>
            <button className="btn btn-primary btn-sm" onClick={handleBackfill} disabled={backfilling || !status?.content_ready}
              title={!status?.content_ready ? 'Content model must be trained first' : ''}>
              {backfilling ? `Backfilling... ${backfillStatus ? `(${backfillStatus.processed}/${backfillStatus.total})` : ''}` : 'Backfill Frontmatter'}
            </button>
          </div>
          <div style={{ maxHeight: '300px', overflowY: 'auto', border: '1px solid var(--border-color)', borderRadius: 'var(--radius-md)' }}>
            <table className="admin-table">
              <thead>
                <tr>
                  <th>#</th>
                  <th>Page Name</th>
                  <th>Last Modified</th>
                </tr>
              </thead>
              <tbody>
                {noFmPages.map((p, i) => (
                  <tr key={p.name}>
                    <td style={{ color: 'var(--text-muted)', width: '40px' }}>{noFmOffset + i + 1}</td>
                    <td>
                      <PageEditLink name={p.name} />
                    </td>
                    <td style={{ fontSize: '0.85em', color: 'var(--text-muted)' }}>
                      {p.lastModified ? new Date(p.lastModified).toLocaleString() : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {noFmTotal > NO_FM_LIMIT && (
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 'var(--space-sm)', fontSize: '0.85em' }}>
              <button className="btn btn-sm" onClick={() => setNoFmOffset(Math.max(0, noFmOffset - NO_FM_LIMIT))} disabled={noFmOffset === 0}>Prev</button>
              <span>Showing {noFmOffset + 1}–{noFmOffset + noFmPages.length} of {noFmTotal}</span>
              <button className="btn btn-sm" onClick={() => setNoFmOffset(noFmOffset + NO_FM_LIMIT)} disabled={noFmOffset + noFmPages.length >= noFmTotal}>Next</button>
            </div>
          )}
        </div>
      )}

      {!status?.content_ready && (
        <div className="admin-empty" style={{ padding: 'var(--space-lg)', textAlign: 'center' }}>
          No content model trained yet. Click "Retrain Content" to compute TF-IDF embeddings for all wiki pages.
        </div>
      )}

      {status?.content_ready && (
        <div>
          <h4 style={{ fontSize: '0.95em', marginBottom: 'var(--space-sm)' }}>
            Top {pairs.length} Most Similar Page Pairs
          </h4>
          <table className="admin-table">
            <thead>
              <tr>
                <th>#</th>
                <th>Page A</th>
                <th>Page B</th>
                <th>Similarity</th>
              </tr>
            </thead>
            <tbody>
              {pairs.map((p, i) => (
                <tr key={i}>
                  <td style={{ color: 'var(--text-muted)', width: '40px' }}>{i + 1}</td>
                  <td>
                    <PageLink name={p.nameA} />
                  </td>
                  <td>
                    <PageLink name={p.nameB} />
                  </td>
                  <td>{(p.score * 100).toFixed(1)}%</td>
                </tr>
              ))}
              {pairs.length === 0 && (
                <tr><td colSpan={4} style={{ textAlign: 'center' }}>No similar page pairs found.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
