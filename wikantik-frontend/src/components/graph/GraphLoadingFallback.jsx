import { useState, useEffect } from 'react';

export default function GraphLoadingFallback() {
  const [slow, setSlow] = useState(false);

  useEffect(() => {
    const timer = setTimeout(() => setSlow(true), 2000);
    return () => clearTimeout(timer);
  }, []);

  return (
    <div className="graph-loading">
      <p>Loading knowledge graph...</p>
      {slow && <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary, #64748b)' }}>
        Still working — large graphs can take a few seconds.
      </p>}
    </div>
  );
}
