import { useState, useEffect } from 'react';
import { api } from '../../api/client';

export default function ContentEmbeddingsTab() {
  const [status, setStatus] = useState(null);
  const [pairs, setPairs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [retraining, setRetraining] = useState(false);
  const [error, setError] = useState(null);

  const loadData = async () => {
    try {
      const s = await api.knowledge.getEmbeddingStatus();
      setStatus(s);
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
                    <a href={`/edit/${encodeURIComponent(p.nameA)}`}>{p.nameA}</a>
                  </td>
                  <td>
                    <a href={`/edit/${encodeURIComponent(p.nameB)}`}>{p.nameB}</a>
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
