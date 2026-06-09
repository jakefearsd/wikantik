import { useState, useEffect, useCallback } from 'react';
import { api } from '../../api/client';
import Select from '../ui/Select';
import Badge from '../ui/Badge';
import EmptyState from '../ui/EmptyState';

// Vocab mirrors com.wikantik.api.kg.EntityTypeVocabulary and the 21 KG predicates.
// A server enum endpoint is a fast-follow; hardcoded here for now.
const ENTITY_TYPES = [
  'person',
  'organization',
  'place',
  'event',
  'product',
  'technology',
  'concept',
  'project',
  'version',
];

const PREDICATES = [
  'related_to',
  'part_of',
  'contains',
  'is_a',
  'instance_of',
  'generalizes',
  'requires',
  'enables',
  'uses',
  'produces',
  'replaces',
  'precedes',
  'extends',
  'implements',
  'alternative_to',
  'contrasts_with',
  'compatible_with',
  'mitigates',
  'defines',
  'applies_to',
  'located_in',
];

function provenanceVariant(provenance) {
  if (!provenance) return 'default';
  if (provenance === 'human-curated') return 'success';
  if (provenance === 'machine') return 'info';
  return 'default';
}

export default function KnowledgeGraphPanel({ pageName }) {
  const [entities, setEntities] = useState([]);
  const [edges, setEdges] = useState([]);
  const [loading, setLoading] = useState(true);
  const [fetchError, setFetchError] = useState(null);

  // Add-entity form state
  const [newEntityName, setNewEntityName] = useState('');
  const [newEntityType, setNewEntityType] = useState('concept');
  const [addEntityError, setAddEntityError] = useState(null);

  // Add-edge form state
  const [edgeSourceId, setEdgeSourceId] = useState('');
  const [edgeTargetId, setEdgeTargetId] = useState('');
  const [edgePredicate, setEdgePredicate] = useState('related_to');
  const [addEdgeError, setAddEdgeError] = useState(null);

  const fetchSlice = useCallback(async () => {
    setFetchError(null);
    try {
      const data = await api.getPageKnowledge(pageName);
      setEntities(data.entities || []);
      setEdges(data.edges || []);
    } catch (err) {
      setFetchError(err.message || 'Failed to load knowledge graph');
    } finally {
      setLoading(false);
    }
  }, [pageName]);

  useEffect(() => {
    fetchSlice();
  }, [fetchSlice]);

  const handleTypeChange = useCallback(async (entity, newType) => {
    try {
      await api.upsertEntity(pageName, { name: entity.name, nodeType: newType });
      await fetchSlice();
    } catch (err) {
      // Surface inline but don't block the UI — re-fetch to restore consistent state
      setFetchError(err.message || 'Failed to update entity type');
    }
  }, [pageName, fetchSlice]);

  const handleConfirmEntity = useCallback(async (id) => {
    try {
      await api.confirmEntity(pageName, id);
      await fetchSlice();
    } catch (err) {
      setFetchError(err.message || 'Failed to confirm entity');
    }
  }, [pageName, fetchSlice]);

  const handleDeleteEntity = useCallback(async (id) => {
    try {
      await api.deleteEntity(pageName, id);
      await fetchSlice();
    } catch (err) {
      setFetchError(err.message || 'Failed to remove entity');
    }
  }, [pageName, fetchSlice]);

  const handleAddEntity = useCallback(async () => {
    if (!newEntityName.trim()) return;
    setAddEntityError(null);
    try {
      await api.upsertEntity(pageName, { name: newEntityName.trim(), nodeType: newEntityType });
      setNewEntityName('');
      setNewEntityType('concept');
      await fetchSlice();
    } catch (err) {
      setAddEntityError(err.message || 'Failed to add entity');
    }
  }, [pageName, newEntityName, newEntityType, fetchSlice]);

  const handleConfirmEdge = useCallback(async (id) => {
    try {
      await api.confirmEdge(pageName, id);
      await fetchSlice();
    } catch (err) {
      setFetchError(err.message || 'Failed to confirm relation');
    }
  }, [pageName, fetchSlice]);

  const handleDeleteEdge = useCallback(async (id) => {
    try {
      await api.deleteEdge(pageName, id);
      await fetchSlice();
    } catch (err) {
      setFetchError(err.message || 'Failed to remove relation');
    }
  }, [pageName, fetchSlice]);

  const handleAddEdge = useCallback(async () => {
    if (!edgeSourceId || !edgeTargetId || !edgePredicate) return;
    setAddEdgeError(null);
    try {
      await api.upsertEdge(pageName, {
        sourceId: edgeSourceId,
        targetId: edgeTargetId,
        relationshipType: edgePredicate,
      });
      setEdgeSourceId('');
      setEdgeTargetId('');
      setEdgePredicate('related_to');
      await fetchSlice();
    } catch (err) {
      if (err.status === 422 && err.body?.violations?.length) {
        setAddEdgeError(err.body.violations[0].message);
      } else {
        setAddEdgeError(err.message || 'Failed to add relation');
      }
    }
  }, [pageName, edgeSourceId, edgeTargetId, edgePredicate, fetchSlice]);

  if (loading) {
    return <div className="kg-panel-loading">Loading knowledge graph…</div>;
  }

  if (fetchError) {
    return <div className="kg-panel-error" role="alert">{fetchError}</div>;
  }

  const entityOptions = entities.map((e) => ({ value: e.id, label: e.name }));

  return (
    <div className="kg-panel">
      {/* ── Entities ─────────────────────────────────────────────────── */}
      <section className="kg-panel-section">
        <h3 className="kg-panel-heading">Entities</h3>

        {entities.length === 0 ? (
          <EmptyState message="No entities on this page yet." />
        ) : (
          <ul className="kg-panel-list" aria-label="Entities">
            {entities.map((entity) => (
              <li key={entity.id} className="kg-panel-row">
                <span className="kg-panel-name">{entity.name}</span>
                <Select
                  value={entity.nodeType}
                  options={ENTITY_TYPES}
                  ariaLabel={`Entity type for ${entity.name}`}
                  onChange={(newType) => handleTypeChange(entity, newType)}
                />
                <Badge variant={provenanceVariant(entity.provenance)} title={entity.provenance || 'unknown'}>
                  {entity.provenance || 'unknown'}
                </Badge>
                <button
                  type="button"
                  className="btn btn-ghost btn-sm kg-panel-confirm"
                  onClick={() => handleConfirmEntity(entity.id)}
                  title="Confirm entity"
                >
                  Confirm
                </button>
                <button
                  type="button"
                  className="btn btn-ghost btn-sm kg-panel-remove"
                  onClick={() => handleDeleteEntity(entity.id)}
                  aria-label={`Remove entity ${entity.name}`}
                  title="Remove entity"
                >
                  ×
                </button>
              </li>
            ))}
          </ul>
        )}

        {/* Add entity row */}
        <div className="kg-panel-add-row">
          <input
            type="text"
            className="kg-panel-input"
            placeholder="Entity name"
            value={newEntityName}
            onChange={(e) => setNewEntityName(e.target.value)}
            aria-label="New entity name"
          />
          <Select
            value={newEntityType}
            options={ENTITY_TYPES}
            ariaLabel="New entity type"
            onChange={setNewEntityType}
          />
          <button
            type="button"
            className="btn btn-primary btn-sm"
            onClick={handleAddEntity}
            disabled={!newEntityName.trim()}
          >
            Add
          </button>
        </div>
        {addEntityError && (
          <div className="kg-panel-inline-error" role="alert">{addEntityError}</div>
        )}
      </section>

      {/* ── Relations ────────────────────────────────────────────────── */}
      <section className="kg-panel-section">
        <h3 className="kg-panel-heading">Relations (intra-page)</h3>

        {edges.length === 0 ? (
          <EmptyState message="No relations on this page yet." />
        ) : (
          <ul className="kg-panel-list" aria-label="Relations">
            {edges.map((edge) => (
              <li key={edge.id} className="kg-panel-row">
                <span className="kg-panel-edge-label">
                  {edge.sourceName}
                  <span className="kg-panel-predicate"> [{edge.relationshipType}] </span>
                  {edge.targetName}
                </span>
                <button
                  type="button"
                  className="btn btn-ghost btn-sm kg-panel-confirm"
                  onClick={() => handleConfirmEdge(edge.id)}
                  title="Confirm relation"
                >
                  Confirm
                </button>
                <button
                  type="button"
                  className="btn btn-ghost btn-sm kg-panel-remove"
                  onClick={() => handleDeleteEdge(edge.id)}
                  aria-label={`Remove relation ${edge.sourceName} ${edge.relationshipType} ${edge.targetName}`}
                  title="Remove relation"
                >
                  ×
                </button>
              </li>
            ))}
          </ul>
        )}

        {/* Add relation row */}
        <div className="kg-panel-add-row kg-panel-add-edge">
          <Select
            value={edgeSourceId}
            options={entityOptions}
            placeholder="Source entity"
            ariaLabel="Source entity"
            onChange={setEdgeSourceId}
          />
          <Select
            value={edgePredicate}
            options={PREDICATES}
            ariaLabel="Relationship type"
            onChange={setEdgePredicate}
          />
          <Select
            value={edgeTargetId}
            options={entityOptions}
            placeholder="Target entity"
            ariaLabel="Target entity"
            onChange={setEdgeTargetId}
          />
          <button
            type="button"
            className="btn btn-primary btn-sm"
            onClick={handleAddEdge}
            disabled={!edgeSourceId || !edgeTargetId}
          >
            Add
          </button>
        </div>
        {addEdgeError && (
          <div className="kg-panel-inline-error" data-testid="edge-add-error" role="alert">
            {addEdgeError}
          </div>
        )}
      </section>
    </div>
  );
}
