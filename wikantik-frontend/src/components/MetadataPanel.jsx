import { useState } from 'react';
import { Link } from 'react-router-dom';

const KEY_ORDER = ['type', 'status', 'cluster', 'date', 'tags', 'related', 'summary'];

function labelFor(key) {
  return key.charAt(0).toUpperCase() + key.slice(1).replace(/-/g, ' ');
}

function sortedEntries(metadata) {
  const ordered = KEY_ORDER.filter(k => k in metadata && metadata[k] !== null && metadata[k] !== undefined);
  const rest = Object.keys(metadata)
    .filter(k => !KEY_ORDER.includes(k) && metadata[k] !== null && metadata[k] !== undefined)
    .sort();
  return [...ordered, ...rest];
}

export default function MetadataPanel({ metadata }) {
  const [expanded, setExpanded] = useState(false);

  if (!metadata || typeof metadata !== 'object') return null;
  const keys = sortedEntries(metadata);
  if (keys.length === 0) return null;

  const chevron = expanded ? '▾' : '▸';

  const gridKeys = keys.filter(k => k !== 'summary');
  const hasSummary = keys.includes('summary') && metadata.summary;

  return (
    <div style={{ marginBottom: 'var(--space-lg)' }}>
      <button
        onClick={() => setExpanded(v => !v)}
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
        Properties {chevron}
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
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: '1fr 1fr',
              gap: 'var(--space-sm) var(--space-md)',
            }}
          >
            {gridKeys.map(key => {
              const value = metadata[key];
              return (
                <div key={key}>
                  <div
                    style={{
                      color: 'var(--text-muted)',
                      fontSize: '0.75rem',
                      fontWeight: 500,
                      textTransform: 'uppercase',
                      letterSpacing: '0.04em',
                      marginBottom: '2px',
                    }}
                  >
                    {labelFor(key)}
                  </div>
                  <div style={{ fontSize: '0.875rem' }}>
                    {renderValue(key, value)}
                  </div>
                </div>
              );
            })}
          </div>

          {hasSummary && (
            <div style={{ marginTop: 'var(--space-sm)' }}>
              <div
                style={{
                  color: 'var(--text-muted)',
                  fontSize: '0.75rem',
                  fontWeight: 500,
                  textTransform: 'uppercase',
                  letterSpacing: '0.04em',
                  marginBottom: '2px',
                }}
              >
                Summary
              </div>
              <div style={{ fontSize: '0.875rem' }}>{metadata.summary}</div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function renderValue(key, value) {
  if (Array.isArray(value)) {
    if (value.length === 0) return <span style={{ color: 'var(--text-muted)' }}>—</span>;
    if (key === 'tags') {
      return (
        <span style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--space-xs)' }}>
          {value.map(item => (
            <span key={item} className="tag">{item}</span>
          ))}
        </span>
      );
    }
    if (key === 'related') {
      return (
        <span style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--space-xs)' }}>
          {value.map(item => (
            <Link key={item} to={`/wiki/${item}`} className="tag">{item}</Link>
          ))}
        </span>
      );
    }
    return value.join(', ');
  }
  return String(value);
}
