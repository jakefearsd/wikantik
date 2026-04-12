import { useState } from 'react';

const NODE_ROLES = [
  { role: 'hub', color: '#059669', label: 'Hub' },
  { role: 'normal', color: '#94a3b8', label: 'Normal' },
  { role: 'orphan', color: '#f59e0b', label: 'Orphan' },
  { role: 'stub', color: null, label: 'Stub', dashed: true },
  { role: 'restricted', color: '#e5e7eb', label: 'Restricted' },
];

export default function GraphLegend({ hubDegreeThreshold, edgeTypes, timestamp }) {
  const [collapsed, setCollapsed] = useState(false);

  return (
    <div className={`graph-legend ${collapsed ? 'collapsed' : ''}`}>
      <div className="graph-legend-toggle" onClick={() => setCollapsed(!collapsed)}>
        {collapsed ? 'Legend +' : 'Legend -'}
      </div>
      {!collapsed && (
        <>
          <div style={{ marginBottom: 'var(--space-xs)' }}>
            {NODE_ROLES.map(({ role, color, label, dashed }) => (
              <div key={role} className="graph-legend-item">
                <span
                  className={`graph-legend-swatch ${dashed ? 'dashed' : ''}`}
                  style={dashed ? {} : { backgroundColor: color }}
                />
                <span>{label}{role === 'hub' ? ` (\u2265${hubDegreeThreshold} connections)` : ''}</span>
              </div>
            ))}
          </div>
          <div style={{ borderTop: '1px solid var(--border)', paddingTop: 'var(--space-xs)' }}>
            {edgeTypes.map(t => (
              <div key={t} className="graph-legend-item">
                <span style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>{t}</span>
              </div>
            ))}
          </div>
          <div style={{ borderTop: '1px solid var(--border)', paddingTop: 'var(--space-xs)', marginTop: 'var(--space-xs)' }}>
            <div className="graph-legend-item">
              <span>{'\u2192'} one-way</span>
            </div>
            <div className="graph-legend-item">
              <span>{'\u2194'} bidirectional</span>
            </div>
          </div>
          <div style={{ marginTop: 'var(--space-xs)', fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
            snapshot: {timestamp}
          </div>
        </>
      )}
    </div>
  );
}
