export default function ProvenanceBadge({ value }) {
  const colors = {
    'human-authored': { bg: '#e8f5e9', color: '#2e7d32' },
    'ai-reviewed': { bg: '#e3f2fd', color: '#1565c0' },
    'ai-inferred': { bg: '#fff3e0', color: '#e65100' },
  };
  const style = colors[value] || { bg: '#f5f5f5', color: '#616161' };
  return (
    <span style={{
      display: 'inline-block', padding: '2px 8px', borderRadius: '12px',
      fontSize: '0.8em', fontWeight: 500, backgroundColor: style.bg, color: style.color,
    }}>
      {value}
    </span>
  );
}
