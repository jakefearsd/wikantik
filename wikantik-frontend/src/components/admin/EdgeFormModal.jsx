import { useState, useEffect, useRef } from 'react';
import { api } from '../../api/client';

function NodeAutocomplete({ label, value, onSelect, disabled }) {
  const [query, setQuery] = useState(value?.name || '');
  const [results, setResults] = useState([]);
  const [open, setOpen] = useState(false);
  const debounceRef = useRef(null);

  useEffect(() => {
    setQuery(value?.name || '');
  }, [value]);

  const onChange = (e) => {
    const v = e.target.value;
    setQuery(v);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(async () => {
      if (!v.trim()) {
        setResults([]);
        return;
      }
      try {
        const data = await api.knowledge.queryNodes({ name: v, limit: 10 });
        setResults(data.nodes || []);
        setOpen(true);
      } catch {
        setResults([]);
      }
    }, 250);
  };

  return (
    <div style={{ display: 'block', marginBottom: 'var(--space-sm)' }}>
      <span style={{ display: 'block', fontWeight: 500, marginBottom: '4px' }}>{label}</span>
      <input
        type="text"
        value={query}
        onChange={onChange}
        disabled={disabled}
        className="form-input"
        style={{ width: '100%' }}
        aria-label={label}
      />
      {open && results.length > 0 && (
        <ul
          style={{
            listStyle: 'none',
            padding: 0,
            margin: 0,
            border: '1px solid var(--border)',
            maxHeight: '180px',
            overflowY: 'auto',
          }}
        >
          {results.map((n) => (
            <li key={n.id}>
              <button
                type="button"
                className="btn-link"
                style={{
                  display: 'block',
                  width: '100%',
                  textAlign: 'left',
                  padding: '6px 8px',
                }}
                onClick={() => {
                  onSelect(n);
                  setQuery(n.name);
                  setOpen(false);
                }}
              >
                {n.name}{' '}
                <small style={{ color: 'var(--text-muted)' }}>({n.node_type})</small>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export default function EdgeFormModal({
  mode,
  relTypes,
  initialEdge,
  initialSource,
  initialTarget,
  onClose,
  onSaved,
}) {
  const [source, setSource] = useState(initialSource || null);
  const [target, setTarget] = useState(initialTarget || null);
  const [relType, setRelType] = useState(initialEdge?.relationship_type || '');
  const [propsText, setPropsText] = useState(
    initialEdge?.properties ? JSON.stringify(initialEdge.properties, null, 2) : '{}'
  );
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);

  let propsValid = true;
  let parsedProps = {};
  try {
    parsedProps = propsText.trim() === '' ? {} : JSON.parse(propsText);
    if (typeof parsedProps !== 'object' || parsedProps === null || Array.isArray(parsedProps)) {
      propsValid = false;
    }
  } catch {
    propsValid = false;
  }

  const canSave = !!source && !!target && !!relType && propsValid && !saving;

  const onSave = async () => {
    setSaving(true);
    setError(null);
    try {
      const body = {
        source_id: source.id,
        target_id: target.id,
        relationship_type: relType,
        properties: parsedProps,
      };
      if (mode === 'edit' && initialEdge?.id) body.id = initialEdge.id;
      await api.knowledge.upsertEdge(body);
      onSaved();
    } catch (e) {
      if (e?.status === 409) setError(e.message || 'This edge already exists.');
      else setError(e?.message || 'Save failed.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div
      role="dialog"
      aria-label={mode === 'edit' ? 'Edit edge' : 'New edge'}
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.5)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1000,
      }}
    >
      <div
        style={{
          background: 'var(--surface-primary)',
          padding: 'var(--space-lg)',
          borderRadius: 'var(--radius-md)',
          minWidth: '480px',
          maxWidth: '640px',
        }}
      >
        <h3>{mode === 'edit' ? 'Edit edge' : 'New edge'}</h3>
        <NodeAutocomplete
          label="Source"
          value={source}
          onSelect={setSource}
          disabled={mode === 'edit'}
        />
        <NodeAutocomplete
          label="Target"
          value={target}
          onSelect={setTarget}
          disabled={mode === 'edit'}
        />
        <div style={{ display: 'block', marginBottom: 'var(--space-sm)' }}>
          <span style={{ display: 'block', fontWeight: 500, marginBottom: '4px' }}>Relationship</span>
          <select
            className="form-input"
            value={relType}
            onChange={(e) => setRelType(e.target.value)}
            style={{ width: '100%' }}
            aria-label="Relationship"
          >
            <option value="">— pick a type —</option>
            {relTypes.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </div>
        <div style={{ display: 'block', marginBottom: 'var(--space-sm)' }}>
          <span style={{ display: 'block', fontWeight: 500, marginBottom: '4px' }}>Properties (JSON)</span>
          <textarea
            className="form-input"
            value={propsText}
            onChange={(e) => setPropsText(e.target.value)}
            rows={5}
            style={{ width: '100%', fontFamily: 'monospace' }}
            aria-label="Properties"
          />
          {!propsValid && (
            <small style={{ color: 'var(--error)' }}>Invalid JSON object.</small>
          )}
        </div>
        {error && (
          <div className="admin-error" style={{ marginBottom: 'var(--space-sm)' }}>
            {error}
          </div>
        )}
        <div style={{ display: 'flex', gap: 'var(--space-sm)', justifyContent: 'flex-end' }}>
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={!canSave}
            onClick={onSave}
          >
            Save
          </button>
        </div>
      </div>
    </div>
  );
}
