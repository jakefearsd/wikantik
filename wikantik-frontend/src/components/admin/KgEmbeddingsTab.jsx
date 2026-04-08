import { useState, useEffect } from 'react';
import { api } from '../../api/client';

export default function KgEmbeddingsTab() {
  const [status, setStatus] = useState(null);
  const [predictions, setPredictions] = useState([]);
  const [anomalous, setAnomalous] = useState([]);
  const [candidates, setCandidates] = useState([]);
  const [loading, setLoading] = useState(true);
  const [retraining, setRetraining] = useState(false);
  const [error, setError] = useState(null);
  const [proposing, setProposing] = useState(null);

  const loadData = async () => {
    try {
      const s = await api.knowledge.getEmbeddingStatus();
      setStatus(s);
      if (s.ready) {
        const [pred, anom, merge] = await Promise.all([
          api.knowledge.getPredictedEdges(20),
          api.knowledge.getAnomalousEdges(20),
          api.knowledge.getMergeCandidates(10),
        ]);
        setPredictions(pred.predictions || []);
        setAnomalous(anom.anomalous || []);
        setCandidates(merge.candidates || []);
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
    try {
      await api.knowledge.retrain();
      await loadData();
    } catch (err) {
      setError(err.message);
    } finally {
      setRetraining(false);
    }
  };

  const handlePropose = async (pred) => {
    setProposing(`${pred.sourceName}-${pred.targetName}`);
    try {
      const [sourceNode, targetNode] = await Promise.all([
        api.knowledge.getNode(pred.sourceName),
        api.knowledge.getNode(pred.targetName),
      ]);
      if (!sourceNode?.id || !targetNode?.id) {
        setError(`Could not resolve nodes: "${pred.sourceName}", "${pred.targetName}"`);
        return;
      }
      await api.knowledge.upsertEdge({
        source_id: sourceNode.id,
        target_id: targetNode.id,
        relationship_type: pred.relationshipType,
      });
      setPredictions(p => p.filter(x => x !== pred));
    } catch (err) {
      setError(err.message);
    } finally {
      setProposing(null);
    }
  };

  const handleMerge = async (candidate) => {
    if (!confirm(`Merge "${candidate.name_a}" into "${candidate.name_b}"?`)) return;
    try {
      const nodeA = await api.knowledge.getNode(candidate.name_a);
      const nodeB = await api.knowledge.getNode(candidate.name_b);
      if (nodeA?.id && nodeB?.id) {
        await api.knowledge.mergeNodes(nodeA.id, nodeB.id);
        setCandidates(c => c.filter(x => x !== candidate));
      }
    } catch (err) {
      setError(err.message);
    }
  };

  if (loading) return <div className="admin-loading">Loading embeddings...</div>;
  if (error) return <div className="admin-error">{error}</div>;

  return (
    <div>
      {/* Status bar */}
      <div style={{ padding: 'var(--space-sm)', background: 'var(--surface-secondary)', borderRadius: 'var(--radius-md)', marginBottom: 'var(--space-md)', fontSize: '0.85em' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-md)', flexWrap: 'wrap', marginBottom: '4px' }}>
          <span><strong>Structure:</strong> {status?.ready ? 'Ready' : 'Not trained'}</span>
          {status?.ready && (
            <>
              <span>v{status.model_version}</span>
              <span>{status.entity_count} entities</span>
              <span>{status.relation_count} relations</span>
              <span>dim {status.dimension}</span>
            </>
          )}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-md)', flexWrap: 'wrap' }}>
          {status?.last_trained && (
            <span><strong>Trained:</strong> {new Date(status.last_trained).toLocaleString()}</span>
          )}
          <button
            className="btn btn-primary btn-sm"
            onClick={handleRetrain}
            disabled={retraining}
          >
            {retraining ? 'Retraining...' : 'Retrain Now'}
          </button>
        </div>
      </div>

      {!status?.ready && (
        <div className="admin-empty" style={{ padding: 'var(--space-lg)', textAlign: 'center' }}>
          No embedding model trained yet. Click "Retrain Now" to train on the current graph.
        </div>
      )}

      {status?.ready && (
        <div style={{ display: 'flex', gap: 'var(--space-lg)', flexWrap: 'wrap' }}>
          {/* Predicted Edges */}
          <div style={{ flex: '1 1 45%', minWidth: '400px' }}>
            <h4 style={{ fontSize: '0.95em', marginBottom: 'var(--space-sm)' }}>Predicted Missing Edges</h4>
            <table className="admin-table">
              <thead>
                <tr>
                  <th>Source</th>
                  <th>Relationship</th>
                  <th>Target</th>
                  <th>Score</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {predictions.map((p, i) => (
                  <tr key={i}>
                    <td>{p.sourceName}</td>
                    <td style={{ whiteSpace: 'nowrap' }}>{p.relationshipType}</td>
                    <td>{p.targetName}</td>
                    <td>{p.score?.toFixed(2)}</td>
                    <td>
                      <button
                        className="btn btn-primary btn-sm"
                        disabled={proposing === `${p.sourceName}-${p.targetName}`}
                        onClick={() => handlePropose(p)}
                      >
                        Create
                      </button>
                    </td>
                  </tr>
                ))}
                {predictions.length === 0 && (
                  <tr><td colSpan={5} style={{ textAlign: 'center' }}>No predictions.</td></tr>
                )}
              </tbody>
            </table>
          </div>

          {/* Anomalous Edges */}
          <div style={{ flex: '1 1 45%', minWidth: '400px' }}>
            <h4 style={{ fontSize: '0.95em', marginBottom: 'var(--space-sm)' }}>Low-Plausibility Edges</h4>
            <table className="admin-table">
              <thead>
                <tr>
                  <th>Source</th>
                  <th>Relationship</th>
                  <th>Target</th>
                  <th>Score</th>
                </tr>
              </thead>
              <tbody>
                {anomalous.map((a, i) => (
                  <tr key={i}>
                    <td>{a.sourceName}</td>
                    <td style={{ whiteSpace: 'nowrap' }}>{a.relationshipType}</td>
                    <td>{a.targetName}</td>
                    <td>{a.score?.toFixed(2)}</td>
                  </tr>
                ))}
                {anomalous.length === 0 && (
                  <tr><td colSpan={4} style={{ textAlign: 'center' }}>No anomalous edges.</td></tr>
                )}
              </tbody>
            </table>
          </div>

          {/* Merge Candidates */}
          <div style={{ flex: '1 1 100%' }}>
            <h4 style={{ fontSize: '0.95em', marginBottom: 'var(--space-sm)' }}>Merge Candidates</h4>
            <table className="admin-table">
              <thead>
                <tr>
                  <th>Node A</th>
                  <th>Node B</th>
                  <th>Structure</th>
                  <th>Content</th>
                  <th>Combined</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {candidates.map((c, i) => (
                  <tr key={i}>
                    <td>{c.name_a}</td>
                    <td>{c.name_b}</td>
                    <td>{(c.structural * 100).toFixed(1)}%</td>
                    <td>{(c.content * 100).toFixed(1)}%</td>
                    <td><strong>{(c.combined * 100).toFixed(1)}%</strong></td>
                    <td>
                      <button className="btn btn-sm" onClick={() => handleMerge(c)}>Merge</button>
                    </td>
                  </tr>
                ))}
                {candidates.length === 0 && (
                  <tr><td colSpan={6} style={{ textAlign: 'center' }}>No merge candidates above threshold.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
