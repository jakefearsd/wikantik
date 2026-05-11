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
    <div className="form-field" style={{ position: 'relative' }}>
      <label>{label}</label>
      <input
        type="text"
        value={query}
        onChange={onChange}
        disabled={disabled}
        aria-label={label}
      />
      {open && results.length > 0 && (
        <ul
          style={{
            listStyle: 'none',
            padding: 0,
            margin: '4px 0 0 0',
            border: '1px solid var(--border)',
            borderRadius: 'var(--radius-sm)',
            background: 'var(--bg-elevated)',
            maxHeight: '180px',
            overflowY: 'auto',
            position: 'absolute',
            left: 0,
            right: 0,
            zIndex: 1,
            boxShadow: '0 4px 12px var(--shadow)',
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
                  padding: 'var(--space-sm) var(--space-md)',
                  fontFamily: 'var(--font-ui)',
                  fontSize: '0.9rem',
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

  const onSubmit = async (e) => {
    e.preventDefault();
    if (!canSave) return;
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
    } catch (e2) {
      if (e2?.status === 409) setError(e2.message || 'This edge already exists.');
      else setError(e2?.message || 'Save failed.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div
      className="modal-overlay"
      role="dialog"
      aria-label={mode === 'edit' ? 'Edit edge' : 'New edge'}
      style={{ alignItems: 'center', paddingTop: 0, zIndex: 1000 }}
    >
      <div
        className="modal-content admin-modal"
        style={{ maxWidth: '560px' }}
        onClick={(e) => e.stopPropagation()}
      >
        <h2 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-lg)' }}>
          {mode === 'edit' ? 'Edit edge' : 'New edge'}
        </h2>

        {error && <div className="error-banner">{error}</div>}

        <form onSubmit={onSubmit}>
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
          <div className="form-field">
            <label>Relationship</label>
            <select
              value={relType}
              onChange={(e) => setRelType(e.target.value)}
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
          <div className="form-field">
            <label>Properties (JSON)</label>
            <textarea
              value={propsText}
              onChange={(e) => setPropsText(e.target.value)}
              rows={5}
              aria-label="Properties"
              style={{ fontFamily: 'var(--font-mono)' }}
            />
            {!propsValid && (
              <p className="form-hint" style={{ color: 'var(--error, #c0392b)' }}>
                Invalid JSON object.
              </p>
            )}
          </div>

          <div className="modal-actions">
            <button type="button" className="btn btn-ghost" onClick={onClose} disabled={saving}>
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={!canSave}>
              {saving ? 'Saving…' : 'Save'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
